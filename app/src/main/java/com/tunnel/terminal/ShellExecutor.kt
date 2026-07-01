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

    // Emulator instance
    val emulator = TerminalEmulator()
    
    // Trigger untuk memberi tahu UI bahwa layar perlu digambar ulang
    private val _screenDirty = MutableStateFlow(0)
    val screenDirty: StateFlow<Int> = _screenDirty.asStateFlow()

    private val _lastCommandOutput = MutableStateFlow("")
    val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
    private var outputBuffer = StringBuilder()

    suspend fun start() {
        withContext(Dispatchers.IO) {
            masterFd = TerminalJni.createSession(24, 80)
            if (masterFd < 0) return@withContext

            pfd = ParcelFileDescriptor.adoptFd(masterFd)
            
            // Tulis MOTD langsung ke emulator screen
            emulator.process("Tunnel Terminal v2.0 (True Emulator)\n")
            emulator.process("NDK PTY Engine Aktif. TUI Supported.\n\n")
            Thread.sleep(100)
            TerminalJni.write(masterFd, "PS1='tunnel@android:~$ '\n".toByteArray())
            _screenDirty.value++ // Update UI

            Thread { readLoop() }.start()
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

            // Kirim teks mentah ke emulator
            emulator.process(text)
            
            // Buffer untuk AI
            outputBuffer.append(text)
            if (outputBuffer.length > 2000) {
                outputBuffer = StringBuilder(outputBuffer.substring(outputBuffer.length - 2000))
            }
            _lastCommandOutput.value = outputBuffer.toString()

            // Tandai layar kotor agar UI Compose menggambar ulang
            _screenDirty.value++
        }
    }

    fun executeCommand(command: String) {
        if (masterFd < 0) return
        val data = (command + "\n").toByteArray(StandardCharsets.UTF_8)
        TerminalJni.write(masterFd, data)
    }

    fun clearScreen() {
        emulator.process("\u001B[2J\u001B[H") // Kirim kode clear screen ANSI
        outputBuffer.clear()
        _lastCommandOutput.value = ""
        _screenDirty.value++
    }

    fun destroy() {
        try {
            if (masterFd >= 0) TerminalJni.close(masterFd)
            pfd?.close()
        } catch (e: Exception) { }
    }
}
