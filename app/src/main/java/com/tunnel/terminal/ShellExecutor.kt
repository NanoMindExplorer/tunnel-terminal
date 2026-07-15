package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ShellExecutor - Local Android PTY shell (/system/bin/sh).
 *
 * Wave-5: Shared I/O lifecycle lives in [PtySessionBase]; this class only
 * spawns the local shell and sets PS1.
 */
class ShellExecutor(
    themeHolder: ThemeHolder = ThemeHolder(),
    private val context: android.content.Context? = null
) : PtySessionBase(themeHolder, "ShellExecutor") {

    private val tag = "ShellExecutor"

    override var currentPrompt: String = "tunnel@android:~$ "

    override val sessionType: String = "local"

    override val environmentDescription: String
        get() = "Android shell lokal (toybox/mksh) — TIDAK ADA package manager (bukan apt, bukan pkg). " +
            "Command tersedia: ls, cd, cat, echo, mkdir, rm, cp, mv, pwd, ps, kill, df, du, head, tail, " +
            "grep, sed, awk. Tidak ada sudo."

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            resetSessionBuffers()

            /* Wave-13/20: Shared geometry — use persisted font size (not hard-coded 12sp). */
            val fontSp = TerminalSize.readPersistedFontSp(context)
            val geo = TerminalSize.fromDisplay(context, fontSizeSp = fontSp)
            val initialCols = geo.cols
            val initialRows = geo.rows

            if (!TerminalJni.isLoaded) {
                failStart("Native library (libtunnel_terminal.so) tidak dapat dimuat.")
                emulator.process("\u001B[33mCoba reinstall APK atau cek ABI compatibility.\u001B[0m\n")
                return@withContext
            }

            val homePath = java.io.File(
                context?.filesDir ?: java.io.File("/data/data/com.tunnel.terminal/files"),
                "home"
            ).absolutePath
            val outFd = IntArray(1)
            val pid = TerminalJni.createSession(initialRows, initialCols, outFd, homePath)
            val fd = outFd.getOrElse(0) { -1 }

            if (pid <= 0 || fd < 0) {
                failStart("Gagal membuat sesi PTY. Coba restart app.")
                return@withContext
            }

            adoptMasterAndStartReader(pid, fd, "pty-read-$id")
            Log.i(tag, "Local shell size=${initialRows}x${initialCols}")

            Thread.sleep(150)
            writeRaw("export PS1='tunnel@android:\$PWD\$ '\n")
            TerminalJni.resize(masterFd, initialRows, initialCols)
        }
    }

    override suspend fun restart() {
        destroy()
        /* Wave-14: Keep emulator/scrollback; only rebind callback + respawn shell. */
        rebindEmulatorCallback()
        emulator.process(reconnectBanner())
        triggerScreenUpdate()
        start()
    }
}
