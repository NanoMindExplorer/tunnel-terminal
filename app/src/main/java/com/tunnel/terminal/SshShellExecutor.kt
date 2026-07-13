package com.tunnel.terminal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * SshConnectionConfig - Konfigurasi koneksi SSH.
 * SSH connection configuration.
 */
data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val privateKeyPath: String = "",
    val privateKeyPassphrase: String = "",
    val name: String = ""  /* Display name untuk tab */
)

/**
 * Phase 41 fix (CRIT-02): State untuk SSH host key change blocking dialog.
 *
 * Saat fingerprint server berubah (potential MITM), dialog ini harus tampil
 * dan user harus actively approve/reject sebelum koneksi diteruskan.
 * Default = reject (paling aman).
 */
data class SshHostKeyDialogState(
    val host: String,
    val oldFingerprint: String,
    val newFingerprint: String,
    /** Dipanggil saat user pilih tombol. true = lanjutkan (risky), false = batalkan. */
    val onResolve: (Boolean) -> Unit
)

/**
 * SshShellExecutor - Remote SSH terminal session using JSch.
 *
 * Phase 21: SSH client implementation. Implements TerminalSession interface
 * sama seperti ShellExecutor (local PTY), sehingga bisa dipakai interchangeably
 * di TabBar dan terminal view.
 *
 * Cara kerja:
 * 1. JSch connect + auth (password atau private key)
 * 2. Open ChannelShell (interactive shell, bukan exec)
 * 3. Set PTY type "xterm-256color" + size
 * 4. Read loop baca dari channel InputStream, process via TerminalEmulator
 * 5. writeRaw tulis ke channel OutputStream
 * 6. destroy: disconnect channel + session
 *
 * Thread safety: sama seperti ShellExecutor (outputLock + writeLock + emulator lock).
 */
class SshShellExecutor(
    private val themeHolder: ThemeHolder,
    private val config: SshConnectionConfig,
    private val context: Context? = null,
    /** Phase 41 fix (CRIT-02): Callback untuk konfirmasi user saat host key berubah.
     *  Return true = user pilih "Tetap lanjutkan" (tidak disarankan), false = batalkan. */
    private val hostKeyChangeCallback: ((oldKey: String, newKey: String) -> Boolean)? = null
) : TerminalSession {
    private val tag = "SshShellExecutor"

    private var session: Session? = null
    private var channel: ChannelShell? = null
    @Volatile
    private var readThread: Thread? = null
    private var channelOutputStream: OutputStream? = null
    /** Phase 58: SFTP channel for file I/O (write_file/read_file via SSH). */
    private var sftpChannel: ChannelSftp? = null

    override val id: Int = globalIdCounter.incrementAndGet()

    override var emulator = TerminalEmulator(themeHolder).also {
        /* Wave-2: Wire DA/DSR responses back to SSH channel. */
        it.writeCallback = { data -> writeRaw(data) }
    }

    override var isAlive by mutableStateOf(false)
        private set

    private val _screenDirty = MutableStateFlow(0)
    override val screenDirty: StateFlow<Int> = _screenDirty.asStateFlow()

    /** Phase 48 fix (F-5): Throttle screenDirty ke ~30fps (33ms min interval). */
    @Volatile
    private var lastScreenDirtyTime: Long = 0
    override fun triggerScreenUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastScreenDirtyTime >= 33) {
            lastScreenDirtyTime = now
            _screenDirty.value++
        }
    }

    private val _lastCommandOutput = MutableStateFlow("")
    override val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
    private val outputLock = Any()
    private var outputBuffer = StringBuilder()

    override val commandHistory = mutableListOf<String>()

    /* Wave-4: Compose state for live autocomplete. */
    override var currentCommandBuffer by mutableStateOf("")

    @Volatile
    override var historyIndex: Int = -1

    @Volatile
    override var currentPrompt: String = "${config.username}@${config.host}:~$ "

    override val sessionType: String = "ssh"

    /** Phase 46 (Pilar 2): Deskripsi lingkungan untuk AI context. */
    override val environmentDescription: String
        get() = "Sesi SSH remote: ${config.username}@${config.host} — OS/package manager tergantung server tujuan, jangan diasumsikan. Tanya user distribusi apa yang dipakai sebelum rekomendasi package manager (apt/yum/pacman/dnf)."

    private val writeLock = Any()

    /**
     * Connect ke SSH server dan mulai shell session.
     * Connect to SSH server and start shell session.
     */
    override suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            outputBuffer.setLength(0)
            _lastCommandOutput.value = ""

            try {
                val jsch = JSch()

                /* Add private key jika ada. */
                if (config.privateKeyPath.isNotBlank()) {
                    jsch.addIdentity(
                        config.privateKeyPath,
                        config.privateKeyPassphrase.ifBlank { null }
                    )
                }

                session = jsch.getSession(config.username, config.host, config.port)

                if (config.password.isNotBlank()) {
                    session?.setPassword(config.password)
                }

                /* Wave-2: Host key verification DURING key exchange (before password auth).
                 *
                 * OLD BUG: StrictHostKeyChecking=no + verify AFTER connect() — password/auth
                 * already sent to a potentially MITM host before fingerprint check.
                 *
                 * FIX: Custom HostKeyRepository + StrictHostKeyChecking=ask so JSch checks
                 * the server key in the KEX phase. promptYesNo handles TOFU (first connect)
                 * and changed-key approval. Auth credentials are not sent until key accepted.
                 */
                val hostKeyPrefs = context?.getSharedPreferences(
                    SecureStorage.SSH_HOSTKEYS_PREFS, Context.MODE_PRIVATE
                )
                val hostKeyId = "${config.host}:${config.port}"
                val knownFingerprint = hostKeyPrefs?.getString(hostKeyId, null)

                if (hostKeyPrefs != null) {
                    jsch.hostKeyRepository = PrefsHostKeyRepository(hostKeyPrefs, config.host, config.port)
                }

                session?.userInfo = object : UserInfo {
                    override fun getPassphrase(): String? = config.privateKeyPassphrase
                    override fun getPassword(): String? = config.password
                    override fun promptPassword(message: String?): Boolean = true
                    override fun promptPassphrase(message: String?): Boolean = true
                    override fun promptYesNo(message: String?): Boolean {
                        val msg = message ?: return false
                        val looksLikeChange = msg.contains("changed", ignoreCase = true) ||
                            msg.contains("mismatch", ignoreCase = true) ||
                            msg.contains("WARNING", ignoreCase = true) ||
                            (knownFingerprint != null && msg.contains("authenticity", ignoreCase = true).not()
                                && msg.contains("changed", ignoreCase = true))
                        /* Changed host key → blocking user dialog (default deny). */
                        if (knownFingerprint != null && (
                                msg.contains("changed", ignoreCase = true) ||
                                    msg.contains("mismatch", ignoreCase = true)
                                )
                        ) {
                            val approved = hostKeyChangeCallback?.invoke(
                                knownFingerprint,
                                msg.take(200)
                            ) ?: false
                            if (approved) {
                                Log.w(tag, "SSH TOFU: User APPROVED host key change for $hostKeyId")
                            }
                            return approved
                        }
                        /* First connect (TOFU) — accept and let repository store the key. */
                        if (knownFingerprint == null) {
                            Log.i(tag, "SSH TOFU: First connect to $hostKeyId — accepting host key")
                            return true
                        }
                        /* Unknown prompt with known host — deny by default. */
                        return hostKeyChangeCallback?.invoke(knownFingerprint, msg.take(200)) ?: false
                    }
                    override fun showMessage(message: String?) {
                        if (!message.isNullOrBlank()) {
                            Log.i(tag, "SSH UserInfo: $message")
                        }
                    }
                }

                session?.setConfig("StrictHostKeyChecking", "ask")
                session?.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                session?.connect(30000)

                /* After successful KEX+auth, mirror fingerprint into prefs for UI/reset. */
                val actualFingerprint = try {
                    val hk = session?.hostKey
                    "${hk?.type ?: ""}:${hk?.key ?: ""}"
                } catch (_: Exception) { "" }

                if (actualFingerprint.isNotBlank() && hostKeyPrefs != null) {
                    val previous = hostKeyPrefs.getString(hostKeyId, null)
                    if (previous == null) {
                        hostKeyPrefs.edit().putString(hostKeyId, actualFingerprint).apply()
                        emulator.process(
                            "\u001B[33m[SSH] TOFU: host key disimpan untuk $hostKeyId\u001B[0m\n" +
                                "\u001B[33m  $actualFingerprint\u001B[0m\n"
                        )
                        Log.i(tag, "SSH TOFU: First connect fingerprint saved for $hostKeyId")
                    } else if (previous != actualFingerprint) {
                        hostKeyPrefs.edit().putString(hostKeyId, actualFingerprint).apply()
                        emulator.process(
                            "\u001B[33m[SSH] ⚠ Host key berubah — Anda memilih untuk melanjutkan. Fingerprint diperbarui.\u001B[0m\n"
                        )
                    } else {
                        Log.i(tag, "SSH TOFU: Host key verified for $hostKeyId")
                    }
                }

                Log.i(tag, "SSH connected: ${config.username}@${config.host}:${config.port}")

                /* Open shell channel. */
                channel = session?.openChannel("shell") as? ChannelShell
                if (channel == null) {
                    throw Exception("Failed to open shell channel")
                }

                /* Wave-13: PTY size from display metrics (was hard-coded 80×24). */
                channel?.setPtyType("xterm-256color")
                val geo = TerminalSize.fromDisplay(context, fontSizeSp = 12f)
                channel?.setPtySize(geo.cols, geo.rows, geo.cols * 8, geo.rows * 12)
                Log.i(tag, "SSH PTY size=${geo.rows}x${geo.cols}")

                /* Connect channel. */
                channel?.connect(10000)

                channelOutputStream = channel?.getOutputStream()

                /* Welcome message. */
                emulator.process("\u001B[32m[SSH] Connected to ${config.host}:${config.port}\u001B[0m\n")
                triggerScreenUpdate()

                /* Start read loop. */
                readThread = Thread({ readLoop() }, "ssh-read-$id").apply {
                    isDaemon = true
                    start()
                }

            } catch (e: Exception) {
                Log.e(tag, "SSH connect failed: ${e.message}")
                isAlive = false
                emulator.process("\u001B[31m[SSH ERROR] ${e.message}\u001B[0m\n")
                triggerScreenUpdate()
            }
        }
    }

    private fun readLoop() {
        val ch = channel ?: return
        val inputStream = try { ch.getInputStream() } catch (e: Exception) {
            Log.e(tag, "getInputStream failed: ${e.message}")
            return
        }
        val buffer = ByteArray(4096)
        /* Phase 26: Fix UTF-8 corruption — pakai ByteBuffer + CharsetDecoder
         * (sama seperti ShellExecutor). Old code: String(buffer, 0, bytesRead, UTF_8)
         * bisa split multi-byte char di chunk boundary → karakter rusak. */
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val byteBuffer = ByteBuffer.wrap(buffer)
        val charBuffer = CharBuffer.allocate(8192)

        var bytesRead: Int = 0
        try {
            while (isAlive && inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isAlive) break
                if (bytesRead <= 0) continue

                byteBuffer.position(0)
                byteBuffer.limit(bytesRead)
                decoder.decode(byteBuffer, charBuffer, false)
                charBuffer.flip()
                val text = charBuffer.toString()
                charBuffer.clear()

                emulator.process(text)
                val outputStr = synchronized(outputLock) {
                    outputBuffer.append(text)
                    if (outputBuffer.length > 16000) {
                        outputBuffer = StringBuilder(outputBuffer.substring(outputBuffer.length - 16000))
                    }
                    outputBuffer.toString()
                }
                _lastCommandOutput.value = outputStr
                triggerScreenUpdate()
            }
        } catch (e: InterruptedException) {
            Log.i(tag, "readLoop interrupted (destroy)")
        } catch (e: Exception) {
            Log.w(tag, "readLoop ended: ${e.message}")
        } finally {
            /* BUG-14 + Wave-2: Clean up channel + SFTP + session on natural disconnect. */
            try { inputStream.close() } catch (_: Exception) {}
            try { sftpChannel?.disconnect() } catch (_: Exception) {}
            sftpChannel = null
            try { channel?.disconnect() } catch (_: Exception) {}
            channel = null
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
            channelOutputStream = null
            try { emulator.flush() } catch (_: Exception) {}
            isAlive = false
            emulator.process("\n\u001B[33m[SSH Disconnected. Tap screen to reconnect.]\u001B[0m\n")
            triggerScreenUpdate()
        }
    }

    override suspend fun restart() {
        destroy()
        emulator = TerminalEmulator(themeHolder).also {
            it.writeCallback = { data -> writeRaw(data) }
        }
        start()
    }

    override fun resizeTerminal(newRows: Int, newCols: Int, fontSize: Float) {
        /* BUG-15 fix: Wrap setPtySize dalam try-catch (channel mungkin sudah disconnect). */
        try {
            channel?.setPtySize(newCols, newRows, newCols * 8, newRows * 12)
        } catch (_: Exception) {}
        emulator.resize(newRows, newCols, fontSize.sp)
        triggerScreenUpdate()
    }

    override fun executeCommand(command: String) {
        if (isAlive) writeRaw(command + "\n")
    }

    override fun writeRaw(data: String) {
        if (!isAlive) return
        synchronized(writeLock) {
            try {
                channelOutputStream?.write(data.toByteArray(StandardCharsets.UTF_8))
                channelOutputStream?.flush()
            } catch (e: Exception) {
                Log.e(tag, "writeRaw failed: ${e.message}")
            }
        }
    }

    override fun clearScreen() {
        emulator.process("\u001B[2J\u001B[H")
        /* Wave-12: Parity with PtySessionBase — drop scrollback on clear. */
        emulator.clearScrollback()
        synchronized(outputLock) { outputBuffer.setLength(0) }
        _lastCommandOutput.value = ""
        triggerScreenUpdate()
    }

    override fun getCleanOutput(): String {
        val raw = synchronized(outputLock) { outputBuffer.toString() }
        val sb = StringBuilder(raw.length)
        val regex = Regex("\u001B\\[[;?\\d]*[A-Za-z]|\u001B\\][^\\u0007]*\\u0007|\u001B\\[[0-9;]*[A-Za-z]")
        var lastEnd = 0
        regex.findAll(raw).forEach { m ->
            sb.append(raw, lastEnd, m.range.first)
            lastEnd = m.range.last + 1
        }
        sb.append(raw, lastEnd, raw.length)
        return sb.toString().trim().take(8000)
    }

    override fun destroy() {
        if (!isAlive && session == null && channel == null && readThread == null) return
        isAlive = false

        /* Phase 58: Disconnect SFTP channel too. */
        try { sftpChannel?.disconnect() } catch (_: Exception) {}
        sftpChannel = null

        try { channel?.disconnect() } catch (_: Exception) {}
        channel = null

        try {
            readThread?.interrupt()
            readThread?.join(300)
        } catch (_: Exception) {}
        readThread = null

        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        channelOutputStream = null
    }

    /**
     * Phase 58: SFTP file operations untuk target SSH.
     * Dipanggil oleh ToolExecutor (via SessionTargetResolver) saat tab SSH aktif.
     */

    /** Buka SFTP channel dari session yang sudah terhubung. */
    private fun ensureSftpChannel(): ChannelSftp? {
        sftpChannel?.let { if (it.isConnected) return it }
        return try {
            val sftp = session?.openChannel("sftp") as? ChannelSftp
            sftp?.connect(10000)
            sftpChannel = sftp
            Log.i(tag, "SFTP channel opened")
            sftp
        } catch (e: Exception) {
            Log.e(tag, "Gagal buka SFTP channel: ${e.message}")
            null
        }
    }

    /**
     * Wave-6: mkdir -p that preserves absolute remote paths.
     * OLD: always built relative segments (dropped leading /).
     */
    private fun mkdirRecursive(sftp: ChannelSftp, path: String) {
        val absolute = path.startsWith("/")
        val parts = path.trim('/').split("/").filter { it.isNotEmpty() }
        var current = if (absolute) "" else ""
        for (part in parts) {
            current = if (current.isEmpty()) {
                if (absolute) "/$part" else part
            } else {
                "$current/$part"
            }
            try { sftp.mkdir(current) } catch (_: Exception) { /* already exists */ }
        }
    }

    /** Tulis file ke remote via SFTP. */
    fun writeFileRemote(path: String, content: String): Boolean {
        if (content.length > MAX_SFTP_CHARS) {
            Log.e(tag, "SFTP write ditolak: content ${content.length} > $MAX_SFTP_CHARS chars")
            return false
        }
        val sftp = ensureSftpChannel() ?: return false
        return try {
            val parent = path.substringBeforeLast("/", "")
            if (parent.isNotEmpty() && parent != path) {
                mkdirRecursive(sftp, parent)
            }
            sftp.put(content.byteInputStream(Charsets.UTF_8), path)
            Log.i(tag, "SFTP write: $path (${content.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(tag, "SFTP write gagal: ${e.message}")
            false
        }
    }

    /** Baca file dari remote via SFTP (capped). */
    fun readFileRemote(path: String): String? {
        val sftp = ensureSftpChannel() ?: return null
        return try {
            val stream = sftp.get(path)
            val sb = StringBuilder()
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                var total = 0
                while (total < MAX_SFTP_CHARS) {
                    val n = reader.read(buf, 0, minOf(buf.size, MAX_SFTP_CHARS - total))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                if (reader.read() != -1) {
                    sb.append("\n... (truncated at $MAX_SFTP_CHARS chars)")
                }
            }
            Log.i(tag, "SFTP read: $path (${sb.length} chars)")
            sb.toString()
        } catch (e: Exception) {
            Log.e(tag, "SFTP read gagal: ${e.message}")
            null
        }
    }

    /** Hapus file di remote via SFTP. */
    fun deleteFileRemote(path: String): Boolean {
        val sftp = ensureSftpChannel() ?: return false
        return try {
            sftp.rm(path)
            Log.i(tag, "SFTP delete: $path")
            true
        } catch (e: Exception) {
            Log.e(tag, "SFTP delete gagal: ${e.message}")
            false
        }
    }

    /** List direktori di remote via SFTP. */
    fun listFilesRemote(dir: String): List<String>? {
        val sftp = ensureSftpChannel() ?: return null
        return try {
            val entries = sftp.ls(dir)
            entries.map { it.filename }
        } catch (e: Exception) {
            Log.e(tag, "SFTP ls gagal: ${e.message}")
            null
        }
    }
    companion object {
        private val globalIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
        /** Wave-6: Cap remote file I/O to avoid OOM on huge remote files. */
        private const val MAX_SFTP_CHARS = 512_000
    }
}

/**
 * Wave-2: HostKeyRepository backed by SharedPreferences.
 * Used so JSch verifies the server host key during KEX (before password auth).
 */
private class PrefsHostKeyRepository(
    private val prefs: android.content.SharedPreferences,
    private val configHost: String,
    private val configPort: Int
) : HostKeyRepository {

    private fun storageId(host: String): String {
        /* JSch may pass "host", "[host]:port", or "host:port". */
        val cleaned = host.removePrefix("[").replace("]", "")
        return if (cleaned.contains(":")) cleaned else "$cleaned:$configPort"
    }

    private fun encodeKey(key: ByteArray): String =
        Base64.encodeToString(key, Base64.NO_WRAP)

    override fun check(host: String?, key: ByteArray?): Int {
        if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED
        val id = storageId(host)
        val known = prefs.getString(id, null) ?: return HostKeyRepository.NOT_INCLUDED
        val b64 = encodeKey(key)
        val knownKey = known.substringAfter(':', known)
        return if (knownKey == b64 || known.endsWith(b64) || known == b64) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {
        if (hostkey == null) return
        val id = storageId(hostkey.host ?: "$configHost:$configPort")
        val value = "${hostkey.type}:${hostkey.key}"
        prefs.edit().putString(id, value).apply()
        /* Also store under config host:port for UI/reset helpers. */
        prefs.edit().putString("$configHost:$configPort", value).apply()
    }

    override fun remove(host: String?, type: String?) {
        if (host == null) return
        prefs.edit().remove(storageId(host)).apply()
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        remove(host, type)
    }

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    override fun getKnownHostsRepositoryID(): String = SecureStorage.SSH_HOSTKEYS_PREFS
}
