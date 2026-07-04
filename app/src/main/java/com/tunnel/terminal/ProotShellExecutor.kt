package com.tunnel.terminal

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ProotShellExecutor — Sesi terminal Linux environment (Ubuntu via proot).
 *
 * Phase 38 (proot/Ubuntu): Hampir identik dengan ShellExecutor (local PTY), bedanya
 * hanya di start(): panggil TerminalJni.createSessionExec() dengan argv proot, alih-alih
 * createSession() yang hardcode /system/bin/sh.
 *
 * Phase 39.1: Updated untuk set LD_LIBRARY_PATH supaya proot bisa temukan libtalloc.so.2
 * dan libandroid-shmem.so yang di-bundle di baseDir/lib/.
 *
 * Semua logic readLoop/writeRaw/destroy/resizeTerminal SAMA PERSIS seperti ShellExecutor
 * karena itu semua generic PTY handling yang tidak peduli program apa yang jalan di
 * ujung lain. proot hanya menjembatani syscall translate — tetap beroperasi di fd PTY
 * yang sama.
 *
 * proot argv yang dipakai:
 *   proot --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root \
 *         /usr/bin/env -i HOME=/root TERM=xterm-256color PATH=... LANG=C.UTF-8 \
 *         /bin/bash --login
 *
 * envp yang di-set:
 *   PROOT_TMP_DIR=<rootfs>/tmp   — temp dir untuk proot internal
 *   LD_LIBRARY_PATH=<baseDir>/lib — cari libtalloc.so.2 + libandroid-shmem.so di sini
 *   PATH=<system path>           — supaya proot bisa find /system/bin utilities
 *   PROOT_NO_SECCOMP=1           — (opsional) untuk device dengan SECCOMP filter strict
 */
class ProotShellExecutor(
    private val themeHolder: ThemeHolder = ThemeHolder(),
    private val bootstrap: ProotBootstrap,
    /** Set true untuk retry dengan PROOT_NO_SECCOMP=1 (untuk device SECCOMP strict). */
    private val disableSeccomp: Boolean = false
) : TerminalSession {
    private val tag = "ProotShellExecutor"

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var pfd: ParcelFileDescriptor? = null

    @Volatile
    private var readThread: Thread? = null

    override val id: Int = globalIdCounter.incrementAndGet()

    private val fdClosed = AtomicBoolean(false)

    @Volatile
    private var startTime: Long = 0L

    override var emulator = TerminalEmulator(themeHolder).also {
        it.writeCallback = { data -> writeRaw(data) }
    }

    override var isAlive by mutableStateOf(true)
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
    override var currentPrompt: String = "root@ubuntu:~# "

    override val sessionType: String = "ubuntu"

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            outputBuffer.setLength(0)
            _lastCommandOutput.value = ""
            startTime = System.currentTimeMillis()

            if (!TerminalJni.isLoaded) {
                isAlive = false
                Log.e(tag, "Native library tidak ter-load — tidak bisa buat PTY session")
                emulator.process("\u001B[31m[ERROR] Native library (libtunnel_terminal.so) tidak dapat dimuat.\u001B[0m\n")
                emulator.process("\u001B[33mCoba reinstall APK atau cek ABI compatibility.\u001B[0m\n")
                triggerScreenUpdate()
                return@withContext
            }

            if (!bootstrap.isInstalled) {
                isAlive = false
                Log.e(tag, "Ubuntu belum terinstal — panggil ProotBootstrap.install() dulu")
                emulator.process("\u001B[31m[ERROR] Ubuntu rootfs belum terinstal.\u001B[0m\n")
                emulator.process("\u001B[33mJalankan instalasi dari menu Ubuntu terlebih dahulu.\u001B[0m\n")
                triggerScreenUpdate()
                return@withContext
            }

            try { bootstrap.setupResolvConf() } catch (_: Exception) {}

            val rootfsPath = bootstrap.rootfsDir.absolutePath
            val prootPath = bootstrap.prootBin.absolutePath
            val libPath = bootstrap.libDir.absolutePath

            // Build argv untuk proot.
            val argv = mutableListOf(
                prootPath,
                "--link2symlink",
                "-0",
                "-r", rootfsPath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "TERM=xterm-256color",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8",
                "/bin/bash", "--login"
            )

            // Build envp (environment yang diteruskan ke execve).
            // Phase 39.1: Tambah LD_LIBRARY_PATH supaya proot bisa find libtalloc + libandroid-shmem.
            val envp = mutableListOf(
                "PROOT_TMP_DIR=${rootfsPath}/tmp",
                "LD_LIBRARY_PATH=$libPath",
                "PATH=${System.getenv("PATH") ?: "/system/bin"}"
            )
            if (disableSeccomp) {
                envp.add("PROOT_NO_SECCOMP=1")
                Log.i(tag, "Retry dengan PROOT_NO_SECCOMP=1 (fallback untuk device SECCOMP strict)")
            }

            val outFd = IntArray(1)
            childPid = TerminalJni.createSessionExec(
                24, 80, outFd, prootPath,
                argv.toTypedArray(),
                envp.toTypedArray()
            )
            masterFd = outFd.getOrElse(0) { -1 }

            if (childPid <= 0 || masterFd < 0) {
                isAlive = false
                Log.e(tag, "createSessionExec gagal: pid=$childPid, fd=$masterFd")
                emulator.process("\u001B[31m[ERROR] Gagal membuat sesi proot (pid=$childPid).\u001B[0m\n")
                emulator.process("\u001B[33mKemungkinan binary proot tidak kompatibel dengan device ini, atau Android memblokir eksekusi.\u001B[0m\n")
                if (!disableSeccomp) {
                    emulator.process("\u001B[33mCoba restart sesi — sistem akan retry dengan PROOT_NO_SECCOMP=1.\u001B[0m\n")
                }
                triggerScreenUpdate()
                return@withContext
            }

            pfd = ParcelFileDescriptor.adoptFd(masterFd)
            Log.i(tag, "Sesi Ubuntu proot dimulai: pid=$childPid, fd=$masterFd, id=$id, rootfs=$rootfsPath, libPath=$libPath")

            readThread = Thread({ readLoop() }, "proot-read-$id").apply {
                isDaemon = true
                start()
            }

            Thread.sleep(200)
            writeRaw("export PS1='\\u@\\h:\\w\\$ '\n")
            writeRaw("clear\n")
            TerminalJni.resize(masterFd, 24, 80)
        }
    }

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
            Log.w(tag, "readLoop berakhir: ${e.message}")
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            try { emulator.flush() } catch (_: Exception) {}
            isAlive = false

            val uptime = System.currentTimeMillis() - startTime
            if (uptime < 2000) {
                Log.w(tag, "Sesi Ubuntu mati dalam ${uptime}ms — kemungkinan SECCOMP issue atau lib tidak ditemukan")
                emulator.process("\n\u001B[31m[Ubuntu session mati prematur dalam ${uptime}ms.\u001B[0m\n")
                if (!disableSeccomp) {
                    emulator.process("\u001B[33mKemungkinan SECCOMP filter Android tidak kompatibel. Coba restart — sistem akan retry dengan PROOT_NO_SECCOMP=1.\u001B[0m\n")
                } else {
                    emulator.process("\u001B[33mPROOT_NO_SECCOMP=1 sudah dicoba tapi tetap gagal. Cek log: kemungkinan libtalloc.so.2 atau libandroid-shmem.so tidak ditemukan.\u001B[0m\n")
                }
            } else {
                emulator.process("\n\u001B[33m[Ubuntu session exited. Tap screen to restart.]\u001B[0m\n")
            }
            triggerScreenUpdate()
        }
    }

    override fun resizeTerminal(newRows: Int, newCols: Int, fontSize: Float) {
        if (masterFd < 0) return
        TerminalJni.resize(masterFd, newRows, newCols)
        emulator.resize(newRows, newCols, fontSize.sp)
        triggerScreenUpdate()
    }

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

    override fun clearScreen() {
        emulator.process("\u001B[2J\u001B[H")
        synchronized(outputLock) {
            outputBuffer.setLength(0)
        }
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
        if (!isAlive && masterFd < 0 && childPid < 0 && readThread == null) return
        isAlive = false

        try {
            if (fdClosed.compareAndSet(false, true)) {
                pfd?.close()
            }
        } catch (_: Exception) {}
        pfd = null

        try {
            readThread?.interrupt()
            readThread?.join(300)
        } catch (_: Exception) {}
        readThread = null

        try {
            if (childPid > 1) {
                TerminalJni.killSession(childPid, 15)
                Thread.sleep(50)
                TerminalJni.killSession(childPid, 9)
                childPid = -1
            }
        } catch (e: Exception) {
            Log.w(tag, "killSession error: ${e.message}")
        }

        masterFd = -1
    }

    companion object {
        private val globalIdCounter = AtomicInteger(0)
    }
}
