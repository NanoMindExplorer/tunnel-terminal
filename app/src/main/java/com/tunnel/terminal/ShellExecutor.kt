package com.tunnel.terminal

import android.os.ParcelFileDescriptor
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

class ShellExecutor {
    private var masterFd: Int = -1
    private var pfd: ParcelFileDescriptor? = null
    val id: Int = System.currentTimeMillis().toInt()

    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    private val _lastCommandOutput = MutableStateFlow("")
    val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
    private var outputBuffer = StringBuilder()

    suspend fun start() {
        withContext(Dispatchers.IO) {
            masterFd = TerminalJni.createSession(24, 80)
            if (masterFd < 0) {
                _output.value = _output.value + "Gagal memulai PTY (NDK Error)."
                return@withContext
            }

            pfd = ParcelFileDescriptor.adoptFd(masterFd)
            
            _output.value = _output.value + "Tunnel Terminal v2.0 (NDK PTY Engine)"
            _output.value = _output.value + "Mesin C/C++ Native berjalan. TUI didukung."
            _output.value = _output.value + ""
            
            // Set prompt kustom
            Thread.sleep(100)
            TerminalJni.write(masterFd, "PS1='tunnel@android:~$ '\n".toByteArray())

            // JALANKAN PEMBACAAN DI THREAD TERPISAH AGAR TIDAK BLOCKING (Anti-Deadlock)
            Thread {
                readLoop()
            }.start()
        }
    }

    private fun readLoop() {
        val inputStream = FileInputStream(pfd!!.fileDescriptor)
        val buffer = ByteArray(4096)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val byteBuffer = ByteBuffer.wrap(buffer)
        val charBuffer = CharBuffer.allocate(8192)

        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            byteBuffer.position(0)
            byteBuffer.limit(bytesRead)
            
            decoder.decode(byteBuffer, charBuffer, false)
            charBuffer.flip()
            
            val text = charBuffer.toString()
            charBuffer.clear()
            
            text.split("\n").forEach { line ->
                if (line.isNotEmpty()) {
                    _output.value = _output.value + line
                    outputBuffer.append(line).append("\n")
                    if (outputBuffer.length > 1000) {
                        outputBuffer = StringBuilder(outputBuffer.substring(outputBuffer.length - 1000))
                    }
                    _lastCommandOutput.value = outputBuffer.toString()
                }
            }
        }
    }

    fun executeCommand(command: String) {
        if (masterFd < 0) return
        val data = (command + "\n").toByteArray(StandardCharsets.UTF_8)
        TerminalJni.write(masterFd, data)
    }

    fun clearScreen() {
        _output.value = emptyList()
        outputBuffer.clear()
        _lastCommandOutput.value = ""
    }

    fun destroy() {
        try {
            if (masterFd >= 0) TerminalJni.close(masterFd)
            pfd?.close()
        } catch (e: Exception) { }
    }
}
