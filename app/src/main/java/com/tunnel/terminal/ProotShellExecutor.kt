package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ProotShellExecutor — Ubuntu Linux environment via proot.
 *
 * Wave-5: Extends [PtySessionBase] for shared PTY I/O. Only spawn/proot argv
 * and readiness latch remain specialized.
 */
class ProotShellExecutor(
    themeHolder: ThemeHolder = ThemeHolder(),
    private val bootstrap: ProotBootstrap,
    private val disableSeccomp: Boolean = false
) : PtySessionBase(themeHolder, "ProotShellExecutor") {

    private val tag = "ProotShellExecutor"

    private var firstByteLatch: CountDownLatch? = null

    @Volatile
    private var startTime: Long = 0L

    override var currentPrompt: String = "root@ubuntu:~# "

    override val sessionType: String = "ubuntu"

    override val environmentDescription: String
        get() = "Ubuntu 24.04 LTS via proot — apt-get & dpkg tersedia. TIDAK ADA systemd " +
            "(systemctl/service tidak berfungsi — jalankan servis sebagai proses biasa dengan &). " +
            "sudo tidak perlu (proot fake-root dengan -0). Untuk install package: " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y <package>."

    override fun onFirstOutputByte() {
        firstByteLatch?.let { latch ->
            latch.countDown()
            firstByteLatch = null
        }
    }

    override fun processExitMessage(): String {
        val uptime = System.currentTimeMillis() - startTime
        return if (uptime in 1 until 2000) {
            buildString {
                append("\n\u001B[31m[Ubuntu session mati prematur dalam ${uptime}ms.\u001B[0m\n")
                if (!disableSeccomp) {
                    append("\u001B[33mKemungkinan SECCOMP filter Android tidak kompatibel. ")
                    append("Coba restart — sistem akan retry dengan PROOT_NO_SECCOMP=1.\u001B[0m\n")
                } else {
                    append("\u001B[33mPROOT_NO_SECCOMP=1 sudah dicoba tapi tetap gagal. ")
                    append("Cek log: kemungkinan libtalloc.so.2 atau libandroid-shmem.so tidak ditemukan.\u001B[0m\n")
                }
            }
        } else {
            "\n\u001B[33m[Ubuntu session exited. Tap screen to restart.]\u001B[0m\n"
        }
    }

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            resetSessionBuffers()
            startTime = System.currentTimeMillis()

            if (!TerminalJni.isLoaded) {
                failStart("Native library (libtunnel_terminal.so) tidak dapat dimuat.")
                emulator.process("\u001B[33mCoba reinstall APK atau cek ABI compatibility.\u001B[0m\n")
                return@withContext
            }

            if (!bootstrap.isInstalled) {
                failStart("Ubuntu rootfs belum terinstal.")
                emulator.process("\u001B[33mJalankan instalasi dari menu Ubuntu terlebih dahulu.\u001B[0m\n")
                return@withContext
            }

            try { bootstrap.setupResolvConf() } catch (_: Exception) {}

            val rootfsPath = bootstrap.rootfsDir.absolutePath
            val prootPath = bootstrap.prootBin.absolutePath
            val libPath = bootstrap.libDir.absolutePath

            if (!java.io.File(prootPath).exists()) {
                failStart("proot binary tidak ditemukan: $prootPath")
                return@withContext
            }
            if (!java.io.File(prootPath).canExecute()) {
                failStart("proot binary tidak executable: $prootPath")
                emulator.process("\u001B[33mDevice ini mungkin memblokir eksekusi binary dari app storage (W^X policy).\u001B[0m\n")
                return@withContext
            }

            val missingLibsFile = java.io.File(bootstrap.baseDir, ".missing_libs")
            if (missingLibsFile.exists()) {
                val missingLibs = missingLibsFile.readText().trim()
                if (missingLibs.isNotEmpty()) {
                    failStart("Shared library proot tidak ditemukan di assets APK:")
                    emulator.process("\u001B[33mMissing: $missingLibs\u001B[0m\n")
                    emulator.process("\u001B[33mLihat app/src/main/assets/proot/README.md.\u001B[0m\n")
                    return@withContext
                }
            }

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

            val envp = mutableListOf(
                "PROOT_TMP_DIR=${rootfsPath}/tmp",
                "LD_LIBRARY_PATH=$libPath",
                "PATH=${System.getenv("PATH") ?: "/system/bin"}"
            )
            if (disableSeccomp) {
                envp.add("PROOT_NO_SECCOMP=1")
                Log.i(tag, "Retry dengan PROOT_NO_SECCOMP=1")
            }

            val outFd = IntArray(1)
            firstByteLatch = CountDownLatch(1)
            val pid = TerminalJni.createSessionExec(
                24, 80, outFd, prootPath,
                argv.toTypedArray(),
                envp.toTypedArray()
            )
            val fd = outFd.getOrElse(0) { -1 }

            if (pid <= 0 || fd < 0) {
                firstByteLatch = null
                failStart("Gagal membuat sesi proot (pid=$pid).")
                emulator.process("\u001B[33mKemungkinan binary proot tidak kompatibel, atau Android memblokir eksekusi.\u001B[0m\n")
                if (!disableSeccomp) {
                    emulator.process("\u001B[33mCoba restart sesi — sistem akan retry dengan PROOT_NO_SECCOMP=1.\u001B[0m\n")
                }
                return@withContext
            }

            adoptMasterAndStartReader(pid, fd, "proot-read-$id")
            Log.i(tag, "Ubuntu proot: rootfs=$rootfsPath lib=$libPath")

            try {
                val ready = firstByteLatch!!.await(5, TimeUnit.SECONDS)
                if (ready) Log.i(tag, "Proot readiness signal diterima")
                else Log.w(tag, "Timeout 5s menunggu proot readiness — kirim PS1 anyway")
            } catch (_: InterruptedException) {
                Log.w(tag, "Interrupted menunggu proot readiness")
            }
            Thread.sleep(100)
            writeRaw("export PS1='\\u@\\h:\\w\\$ '\n")
            TerminalJni.resize(masterFd, 24, 80)
        }
    }

    override suspend fun restart() {
        destroy()
        rewireEmulator()
        start()
    }
}
