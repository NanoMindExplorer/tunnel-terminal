package com.tunnel.terminal

import android.util.Log

/**
 * JNI Bridge ke library C++ native (libtunnel_terminal.so).
 * Menyediakan operasi PTY: create, write, resize, close, kill.
 *
 * Phase 21: isAlive dihapus (dead code + PID recycling risk).
 *
 * JNI bridge to native C++ library providing PTY operations.
 */
object TerminalJni {
    private const val TAG = "TerminalJni"

    /* M2 fix: Flag untuk track apakah native library berhasil di-load.
     * Jika gagal, app tidak crash — tampilkan error message di terminal. */
    val isLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("tunnel_terminal")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Gagal load libtunnel_terminal.so: ${e.message}")
            /* M2 fix: Jangan re-throw — biarkan app jalan dengan fallback. */
        }
        isLoaded = loaded
    }

    /**
     * Membuat sesi PTY baru.
     * Creates a new PTY session.
     *
     * @param rows jumlah baris awal
     * @param cols jumlah kolom awal
     * @param outFd array int[1] untuk menerima master file descriptor
     * @return child pid (>0) jika sukses, -1 jika gagal
     */
    external fun createSession(rows: Int, cols: Int, outFd: IntArray): Int

    /** Menulis data byte ke PTY. Writes bytes to PTY. */
    external fun write(fd: Int, data: ByteArray)

    /** Mengatur ukuran terminal. Resizes PTY window. */
    external fun resize(fd: Int, rows: Int, cols: Int)

    /** Menutup master fd. Closes master fd. */
    external fun close(fd: Int)

    /**
     * Mengirim sinyal ke child process dan reap zombie.
     * Sends signal to child and reaps zombie.
     *
     * Phase 21: Safe PID check — cek waitpid sebelum kill untuk hindari
     * PID recycling (mengirim sinyal ke process lain yang dapat PID yang sama).
     *
     * @param pid child process id
     * @param signal 0 = SIGKILL, otherwise signal number (e.g. 15 = SIGTERM)
     * @return 0 sukses, -1 gagal
     */
    external fun killSession(pid: Int, signal: Int): Int
}
