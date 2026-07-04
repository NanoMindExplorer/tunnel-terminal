package com.tunnel.terminal

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * ShellExecutor - Manages a single PTY shell session.
 *
 * Perubahan Phase 17 (Major Bug Fix):
 * - Track child pid untuk kill yang benar di destroy() (no more zombie)
 * - Reset outputBuffer di restart() (no bleed antar session)
 * - Strip ANSI codes dari lastCommandOutput untuk AI context
 * - Per-executor command history (tidak shared antar tab)
 * - Tunggu shell siap setelah start() sebelum kirim PS1
 * - Thread readLoop di-set sebagai daemon
 *
 * Phase 18: Accept themeHolder untuk TerminalEmulator (theme-aware rendering).
 */
class ShellExecutor(
    private val themeHolder: ThemeHolder = ThemeHolder(),
    /* Phase 44 fix (MED-04): Context untuk display metrics — dipakai menghitung
     * ukuran PTY awal yang lebih akurat dari hardcode 80x24. */
    private val context: android.content.Context? = null
) : TerminalSession {
    private val tag = "ShellExecutor"

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var pfd: ParcelFileDescriptor? = null
    /** Track readLoop thread untuk interrupt saat destroy. */
    @Volatile
    private var readThread: Thread? = null
    /* BUG-25 fix: AtomicInteger companion untuk ID unik global (bukan timestamp). */
    override val id: Int = globalIdCounter.incrementAndGet()

    /* BUG-26 fix: Guard untuk mencegah double-close fd antara readLoop exit vs destroy(). */
    private val fdClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    override var emulator = TerminalEmulator(themeHolder).also {
        /* BUG-09 fix: Wire writeCallback untuk DA/DSR responses. */
        it.writeCallback = { data -> writeRaw(data) }
    }

    override var isAlive by mutableStateOf(true)
        private set

    private val _screenDirty = MutableStateFlow(0)
    override val screenDirty: StateFlow<Int> = _screenDirty.asStateFlow()

    override fun triggerScreenUpdate() { _screenDirty.value++ }

    private val _lastCommandOutput = MutableStateFlow("")
    override val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
    /** Phase 21: outputBuffer diakses dari readLoop (write) + main (read di getCleanOutput).
     *  Synchronize dengan lock untuk thread safety. */
    private val outputLock = Any()
    private var outputBuffer = StringBuilder()

    /** Riwayat perintah per-executor (per-tab). Per-tab command history. */
    override val commandHistory = mutableListOf<String>()

    /** Per-tab current input line buffer (Phase 19.5: was global in MainActivity).
     * Saat user switch tab, input yang sedang diketik tidak hilang. */
    @Volatile
    override var currentCommandBuffer: String = ""

    /** Per-tab history navigation index (-1 = tidak browsing history). */
    @Volatile
    override var historyIndex: Int = -1

    /** Prompt saat ini (dideteksi dari output shell). Current prompt. */
    @Volatile
    override var currentPrompt: String = "tunnel@android:~$ "

    override val sessionType: String = "local"

    /** Phase 46 (Pilar 2): Deskripsi lingkungan untuk AI context. */
    override val environmentDescription: String
        get() = "Android shell lokal (toybox/mksh) — TIDAK ADA package manager (bukan apt, bukan pkg). Command tersedia: ls, cd, cat, echo, mkdir, rm, cp, mv, pwd, ps, kill, df, du, head, tail, grep, sed, awk. Tidak ada sudo."

    /**
     * Mulai sesi PTY baru.
     * Start a new PTY session.
     */
    override suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            outputBuffer.setLength(0)
            _lastCommandOutput.value = ""

            /* Phase 44 fix (MED-04): Hitung ukuran PTY awal dari display metrics
             * alih-alih hardcode 80x24. Compose layout belum tersedia saat start()
             * dipanggil, tapi display metrics sudah. Ini mengurangi flicker saat
             * tab baru dibuka (sebelumnya: 80x24 → onSizeChanged → resize ke actual).
             *
             * Asumsi: fontSize default 12sp, char width ≈ 0.6 × fontSize, char height ≈ 1.2 × fontSize.
             * Density dari resources. */
            val displayMetrics = android.util.DisplayMetrics()
            try {
                @Suppress("DEPRECATION")
                (context?.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)
                    ?.defaultDisplay?.getMetrics(displayMetrics)
            } catch (_: Exception) {}
            val density = displayMetrics.density.takeIf { it > 0 } ?: 2.0f
            val screenWidthPx = displayMetrics.widthPixels.takeIf { it > 0 } ?: (1080 * density).toInt()
            val screenHeightPx = displayMetrics.heightPixels.takeIf { it > 0 } ?: (1920 * density).toInt()
            val fontSizeSp = 12f
            val charWidthPx = (fontSizeSp * density * 0.6f).coerceAtLeast(1f)
            val charHeightPx = (fontSizeSp * density * 1.2f).coerceAtLeast(1f)
            val initialCols = (screenWidthPx / charWidthPx).toInt().coerceIn(20, 200)
            val initialRows = (screenHeightPx / charHeightPx).toInt().coerceIn(10, 100)

            val outFd = IntArray(1)
            /* M2 fix: Check apakah native library berhasil di-load. */
            if (!TerminalJni.isLoaded) {
                isAlive = false
                Log.e(tag, "Native library tidak ter-load — tidak bisa buat PTY session")
                emulator.process("\u001B[31m[ERROR] Native library (libtunnel_terminal.so) tidak dapat dimuat.\u001B[0m\n")
                emulator.process("\u001B[33mCoba reinstall APK atau cek ABI compatibility.\u001B[0m\n")
                triggerScreenUpdate()
                return@withContext
            }
            childPid = TerminalJni.createSession(initialRows, initialCols, outFd)
            masterFd = outFd.getOrElse(0) { -1 }

            if (childPid <= 0 || masterFd < 0) {
                isAlive = false
                Log.e(tag, "createSession gagal: pid=$childPid, fd=$masterFd")
                emulator.process("\u001B[31m[ERROR] Gagal membuat sesi PTY. Coba restart app.\u001B[0m\n")
                triggerScreenUpdate()
                return@withContext
            }

            pfd = ParcelFileDescriptor.adoptFd(masterFd)
            Log.i(tag, "Sesi dimulai: pid=$childPid, fd=$masterFd, id=$id, size=${initialRows}x${initialCols}")

            /* Thread pembaca output shell - set sebagai daemon agar tidak block JVM exit.
             * Shell output reader thread - daemon so it never blocks JVM exit. */
            readThread = Thread({ readLoop() }, "pty-read-$id").apply {
                isDaemon = true
                start()
            }

            /* Beri waktu singkat untuk shell siap, lalu set PS1.
             * Brief delay for shell readiness, then set PS1. */
            Thread.sleep(150)
            writeRaw("export PS1='tunnel@android:\$PWD\$ '\n")
            /* Kirim resize awal agar shell tahu ukuran terminal.
             * Send initial resize so shell knows terminal size. */
            TerminalJni.resize(masterFd, initialRows, initialCols)
        }
    }

    /**
     * Restart sesi yang sudah mati. Destroy lalu start ulang.
     * Restart a dead session: destroy then start fresh.
     */
    override suspend fun restart() {
        destroy()
        emulator = TerminalEmulator(themeHolder)
        start()
    }

    private fun readLoop() {
        val pfdRef = pfd
        if (pfdRef == null) {
            Log.e(tag, "pfd null di readLoop, abort")
            return
        }
        val inputStream = FileInputStream(pfdRef.fileDescriptor)
        val buffer = ByteArray(4096)
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
                /* Phase 21: Synchronize outputBuffer access (readLoop writes, main reads). */
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
            /* Thread di-interrupt saat destroy() — exit gracefully. */
            Log.i(tag, "readLoop interrupted (destroy)")
        } catch (e: Exception) {
            Log.w(tag, "readLoop berakhir: ${e.message}")
        } finally {
            /* Phase 19.5: Close FileInputStream untuk hindari fd leak. */
            try { inputStream.close() } catch (_: Exception) {}
            try { emulator.flush() } catch (_: Exception) {}
            isAlive = false
            emulator.process("\n\u001B[33m[Process Exited. Tap screen to restart session.]\u001B[0m\n")
            triggerScreenUpdate()
        }
    }

    override fun resizeTerminal(newRows: Int, newCols: Int, fontSize: Float) {
        if (masterFd < 0) return
        TerminalJni.resize(masterFd, newRows, newCols)
        emulator.resize(newRows, newCols, fontSize.sp)
        triggerScreenUpdate()
    }

    /** Phase 21: writeLock untuk serialize concurrent JNI writes dari multiple threads.
     *  Tanpa ini, write dari main + write dari Auto-Pilot bisa interleave -> corrupt input. */
    private val writeLock = Any()

    override fun executeCommand(command: String) {
        if (isAlive) writeRaw(command + "\n")
    }

    override fun writeRaw(data: String) {
        if (masterFd < 0 || !isAlive) return
        synchronized(writeLock) {
            if (masterFd >= 0 && isAlive) {
                TerminalJni.write(masterFd, data.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    /**
     * Bersihkan layar terminal (lokal, tidak kirim ke shell).
     * Clear terminal screen locally (does NOT send to shell).
     * Phase 21: Synchronized outputBuffer access.
     */
    override fun clearScreen() {
        emulator.process("\u001B[2J\u001B[H")
        synchronized(outputLock) {
            outputBuffer.setLength(0)
        }
        _lastCommandOutput.value = ""
        triggerScreenUpdate()
    }

    /**
     * Ambil output terminal yang sudah dibersihkan dari ANSI escape codes.
     * Get ANSI-stripped terminal output (clean for AI context).
     * Phase 21: Synchronized outputBuffer access (thread-safe read).
     */
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

    /**
     * Hancurkan sesi: kill child process, close fd, reap zombie.
     * Destroy session: kill child, close fd, reap zombie.
     *
     * Phase 20: Fix double-close fd. pfd owns the fd via adoptFd,
     * so ONLY close pfd (not TerminalJni.close + pfd.close).
     * Also: make destroy non-blocking by closing pfd FIRST to unblock
     * readLoop's inputStream.read(), then interrupt thread.
     */
    override fun destroy() {
        if (!isAlive && masterFd < 0 && childPid < 0 && readThread == null) return
        isAlive = false

        /* Phase 20: Close pfd FIRST to unblock readLoop's inputStream.read().
         * When pfd is closed, read() returns -1, readLoop exits naturally.
         * This is more reliable than Thread.interrupt() which doesn't unblock
         * FileInputStream.read() on all platforms. */
        /* BUG-26 fix: Gunakan AtomicBoolean compareAndSet untuk mencegah double-close. */
        try {
            if (fdClosed.compareAndSet(false, true)) {
                pfd?.close()
            }
        } catch (_: Exception) {}
        pfd = null

        /* Now interrupt + join readLoop thread (should exit quickly since pfd closed). */
        try {
            readThread?.interrupt()
            readThread?.join(300)  /* Reduced from 500ms since pfd close should unblock */
        } catch (_: Exception) {}
        readThread = null

        /* Kill child process. */
        try {
            if (childPid > 1) {
                TerminalJni.killSession(childPid, 15) // SIGTERM
                Thread.sleep(50)  /* Reduced from 100ms */
                TerminalJni.killSession(childPid, 9)  // SIGKILL + reap
                childPid = -1
            }
        } catch (e: Exception) {
            Log.w(tag, "killSession error: ${e.message}")
        }

        /* Phase 20: fd already closed via pfd.close() above. Just reset state.
         * Don't call TerminalJni.close(masterFd) — pfd owns it, double-close bug. */
        masterFd = -1
    }

    companion object {
        /* BUG-25 fix: Global counter untuk ID unik (bukan timestamp yang bisa collision). */
        private val globalIdCounter = AtomicInteger(0)
    }
}
