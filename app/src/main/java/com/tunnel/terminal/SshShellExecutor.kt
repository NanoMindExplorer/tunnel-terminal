package com.tunnel.terminal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
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
    private val context: Context? = null
) : TerminalSession {
    private val tag = "SshShellExecutor"

    private var session: Session? = null
    private var channel: ChannelShell? = null
    @Volatile
    private var readThread: Thread? = null
    private var channelOutputStream: OutputStream? = null

    override val id: Int = globalIdCounter.incrementAndGet()

    override var emulator = TerminalEmulator(themeHolder)

    override var isAlive by mutableStateOf(false)
        private set

    private val _screenDirty = MutableStateFlow(0)
    override val screenDirty: StateFlow<Int> = _screenDirty.asStateFlow()

    override fun triggerScreenUpdate() { _screenDirty.value++ }

    private val _lastCommandOutput = MutableStateFlow("")
    override val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
    private val outputLock = Any()
    private var outputBuffer = StringBuilder()

    override val commandHistory = mutableListOf<String>()

    @Volatile
    override var currentCommandBuffer: String = ""

    @Volatile
    override var historyIndex: Int = -1

    @Volatile
    override var currentPrompt: String = "${config.username}@${config.host}:~$ "

    override val sessionType: String = "ssh"

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

                /* BUG-02 REAL FIX (Phase 34): Implementasi TOFU yang sebenarnya.
                 * Phase 27 hanya ganti "no" → "ask" + baca knownHostKey tapi TIDAK PERNAH
                 * membandingkan/menyimpan fingerprint. promptYesNo tetap return true.
                 *
                 * Fix sekarang:
                 * 1. StrictHostKeyChecking="no" (JSch tidak block, kita verifikasi manual)
                 * 2. Set connect, ambil host key dari session.getHostKey()
                 * 3. Bandingkan dengan fingerprint tersimpan
                 * 4. Jika baru → simpan. Jika berubah → disconnect + error (MITM).
                 */
                val hostKeyPrefs = context?.getSharedPreferences("TunnelSshHostKeys", Context.MODE_PRIVATE)
                val hostKeyId = "${config.host}:${config.port}"

                session?.userInfo = object : UserInfo {
                    override fun getPassphrase(): String? = config.privateKeyPassphrase
                    override fun getPassword(): String? = config.password
                    override fun promptPassword(message: String?): Boolean = true
                    override fun promptPassphrase(message: String?): Boolean = true
                    override fun promptYesNo(message: String?): Boolean = true
                    override fun showMessage(message: String?) {}
                }

                session?.setConfig("StrictHostKeyChecking", "no")
                session?.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                session?.connect(30000)

                /* BUG-02 REAL FIX: Verifikasi host key SETELAH connect.
                 * Phase 35 hotfix: getHostKey() call — JSch 0.2.21 needs explicit method call. */
                val actualFingerprint = try {
                    session?.getHostKey()?.getFingerPrint()?.toString() ?: ""
                } catch (_: Exception) { "" }

                if (actualFingerprint.isNotBlank()) {
                    val knownFingerprint = hostKeyPrefs?.getString(hostKeyId, null)
                    if (knownFingerprint == null) {
                        /* First connect — simpan fingerprint. */
                        hostKeyPrefs?.edit()?.putString(hostKeyId, actualFingerprint)?.apply()
                        Log.i(tag, "SSH TOFU: First connect to $hostKeyId, fingerprint saved")
                    } else if (knownFingerprint != actualFingerprint) {
                        /* FINGERPRINT BERUBAH — potensi MITM! Disconnect segera. */
                        session?.disconnect()
                        throw SecurityException(
                            "PERINGATAN KEAMANAN: Host key untuk ${config.host}:${config.port} telah berubah!\n" +
                            "Ini bisa berarti server diganti, atau ada serangan Man-in-the-Middle.\n" +
                            "Fingerprint sebelumnya: $knownFingerprint\n" +
                            "Fingerprint sekarang: $actualFingerprint\n" +
                            "Jika Anda yakin ini aman, ketik 'storage-reset' di terminal untuk reset host keys."
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

                /* Set PTY type + size. */
                channel?.setPtyType("xterm-256color")
                channel?.setPtySize(80, 24, 80 * 8, 24 * 12)

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
                    if (outputBuffer.length > 4000) {
                        outputBuffer = StringBuilder(outputBuffer.substring(outputBuffer.length - 4000))
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
            /* BUG-14 fix: Clean up channel + session saat disconnect alami.
             * Old code: hanya close inputStream — channel/session JSch leak. */
            try { inputStream.close() } catch (_: Exception) {}
            try { channel?.disconnect() } catch (_: Exception) {}
            try { emulator.flush() } catch (_: Exception) {}
            isAlive = false
            emulator.process("\n\u001B[33m[SSH Disconnected. Tap screen to reconnect.]\u001B[0m\n")
            triggerScreenUpdate()
        }
    }

    override suspend fun restart() {
        destroy()
        emulator = TerminalEmulator(themeHolder)
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
        return sb.toString().trim().take(2000)
    }

    override fun destroy() {
        if (!isAlive && session == null && channel == null && readThread == null) return
        isAlive = false

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
    companion object {
        private val globalIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
