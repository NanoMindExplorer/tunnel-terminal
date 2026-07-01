package com.tunnel.terminal

object TerminalJni {
    init {
        // Memuat library C++ yang sudah di-compile
        System.loadLibrary("tunnel_terminal")
    }

    external fun createSession(rows: Int, cols: Int): Int
    external fun write(fd: Int, data: ByteArray)
    external fun resize(fd: Int, rows: Int, cols: Int)
    external fun close(fd: Int)
}
