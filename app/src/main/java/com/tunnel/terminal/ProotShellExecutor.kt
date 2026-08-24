package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ProotShellExecutor — Ubuntu Linux environment via proot.
 *
 * Wave-5: Extends [PtySessionBase] for shared PTY I/O.
 * Wave-30: Fix premature exit (~20ms) — ensure host libs, full linker env
 * (createSessionExec merges parent environ), preflight probe, clearer diagnostics.
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

    /** Last host-side error detail for processExitMessage / UI. */
    @Volatile
    private var lastStartError: String? = null

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
        if (closedByClient) return ""
        val uptime = System.currentTimeMillis() - startTime
        return if (uptime in 1 until 2000) {
            buildString {
                append("\n\u001B[31m[Ubuntu session mati prematur dalam ${uptime}ms.]\u001B[0m\n")
                lastStartError?.let {
                    append("\u001B[33mDetail: $it\u001B[0m\n")
                }
                if (!disableSeccomp && !bootstrap.getSeccompFallbackEnabled()) {
                    bootstrap.setSeccompFallbackEnabled(true)
                    append("\u001B[33mFlag PROOT_NO_SECCOMP disimpan. Tap layar untuk restart dengan fallback.\u001B[0m\n")
                } else {
                    append("\u001B[33mPROOT_NO_SECCOMP=1 sudah aktif. Cek:\u001B[0m\n")
                    append("\u001B[33m• Library host: libtalloc.so.2 + libandroid-shmem.so di filesDir/linux/lib\u001B[0m\n")
                    append("\u001B[33m• Rootfs: bin/bash ada; Install ulang Ubuntu jika corrupt\u001B[0m\n")
                    append("\u001B[33m• APK harus flavor Full (GitHub Releases), bukan Play Store\u001B[0m\n")
                    try {
                        append("\u001B[36m${bootstrap.listRuntimeDiagnostics()}\u001B[0m")
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            "\n\u001B[33m[Ubuntu session exited. Tap screen to restart — history kept.]\u001B[0m\n"
        }
    }

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            resetSessionBuffers()
            startTime = System.currentTimeMillis()
            lastStartError = null

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

            /* Wave-30: Always refresh proot + libs from APK assets; probe before spawn. */
            try {
                val ver = bootstrap.probeProotOrThrow()
                Log.i(tag, "Preflight proot OK: ${ver.take(80)}")
            } catch (e: Exception) {
                lastStartError = e.message
                failStart("Preflight proot gagal: ${e.message}")
                emulator.process("\u001B[33m${bootstrap.listRuntimeDiagnostics()}\u001B[0m\n")
                return@withContext
            }

            try {
                bootstrap.setupResolvConf()
            } catch (_: Exception) {
            }

            val rootfsPath = bootstrap.rootfsDir.absolutePath
            val libPath = bootstrap.hostLibraryPath()
            val tmpHost = File(bootstrap.baseDir, "tmp").apply { mkdirs() }.absolutePath
            val tmpGuest = File(bootstrap.rootfsDir, "tmp").apply { mkdirs() }.absolutePath
            val prootPath = bootstrap.prootExecFile().absolutePath

            /* Prefer real bash path inside rootfs (symlink-safe). */
            val bashGuest = when {
                File(bootstrap.rootfsDir, "usr/bin/bash").isFile -> "/usr/bin/bash"
                File(bootstrap.rootfsDir, "bin/bash").exists() -> "/bin/bash"
                else -> {
                    failStart("bash tidak ada di rootfs — extract corrupt. Uninstall lalu Install ulang Ubuntu.")
                    return@withContext
                }
            }

            val androidWorkspace = File(
                bootstrap.appContext.filesDir, "workspace"
            ).apply { mkdirs() }.absolutePath

            var useNoSeccomp = disableSeccomp || bootstrap.getSeccompFallbackEnabled()
            val envp = mutableListOf(
                "LD_LIBRARY_PATH=$libPath",
                "PROOT_TMP_DIR=$tmpHost",
                "TMPDIR=$tmpGuest",
                "HOME=/root",
                "TERM=xterm-256color",
                "PATH=/system/bin:/system/xbin:/vendor/bin"
            )
            if (useNoSeccomp) {
                envp.add("PROOT_NO_SECCOMP=1")
                Log.i(tag, "Spawn dengan PROOT_NO_SECCOMP=1")
            }

            /* Flags after proot binary (prootSpawn may prefix linker64). */
            val prootArgs = listOf(
                "--link2symlink",
                "--kill-on-exit",
                "-0",
                "-r", rootfsPath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/system",
                "-b", "$androidWorkspace:/mnt/workspace",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "TERM=xterm-256color",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8",
                "DEBIAN_FRONTEND=noninteractive",
                "TMPDIR=/tmp",
                bashGuest, "--login"
            )
            val (execPath, argv) = bootstrap.prootSpawn(prootArgs)
            Log.i(tag, "Spawn exec=$execPath argv0=${argv.firstOrNull()} proot=$prootPath")

            val fontSp = TerminalSize.readPersistedFontSp(bootstrap.appContext)
            val geo = TerminalSize.fromDisplay(bootstrap.appContext, fontSizeSp = fontSp)
            val outFd = IntArray(1)
            firstByteLatch = CountDownLatch(1)
            val pid = TerminalJni.createSessionExec(
                geo.rows, geo.cols, outFd, execPath,
                argv,
                envp.toTypedArray()
            )
            val fd = outFd.getOrElse(0) { -1 }

            if (pid <= 0 || fd < 0) {
                firstByteLatch = null
                lastStartError = "createSessionExec pid=$pid fd=$fd"
                if (!useNoSeccomp) bootstrap.setSeccompFallbackEnabled(true)
                failStart("Gagal membuat sesi proot (pid=$pid).")
                emulator.process("\u001B[33m${bootstrap.listRuntimeDiagnostics()}\u001B[0m\n")
                if (!useNoSeccomp) {
                    emulator.process(
                        "\u001B[33mFlag PROOT_NO_SECCOMP disimpan. Tap layar untuk restart dengan fallback.\u001B[0m\n"
                    )
                }
                return@withContext
            }

            adoptMasterAndStartReader(pid, fd, "proot-read-$id")
            Log.i(tag, "Ubuntu proot: rootfs=$rootfsPath lib=$libPath size=${geo.rows}x${geo.cols} pid=$pid")

            try {
                val ready = firstByteLatch!!.await(5, TimeUnit.SECONDS)
                if (ready) Log.i(tag, "Proot readiness signal diterima")
                else Log.w(tag, "Timeout 5s menunggu proot readiness — kirim PS1 anyway")
            } catch (_: InterruptedException) {
                Log.w(tag, "Interrupted menunggu proot readiness")
            }
            /* If already dead, readLoop already printed processExitMessage
             * (and persisted the SECCOMP fallback). Do not respawn here. */
            if (!isAlive) {
                lastStartError = lastStartError ?: "process exited before first prompt"
                triggerScreenUpdate()
                return@withContext
            }
            delay(100)
            writeRaw("export PS1='\\u@\\h:\\w\\$ '\n")
            TerminalJni.resize(masterFd, geo.rows, geo.cols)
        }
    }

    override suspend fun restart() {
        destroy()
        rebindEmulatorCallback()
        emulator.process(reconnectBanner())
        triggerScreenUpdate()
        start()
    }
}
