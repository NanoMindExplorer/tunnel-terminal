package com.tunnel.terminal

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    val emulator = TerminalEmulator()
    
    // State untuk melacak apakah proses shell hidup atau mati
    var isAlive by mutableStateOf(true)
        private set

    private val _screenDirty = MutableStateFlow(0)
    val screenDirty: StateFlow<Int> = _screenDirty.asStateFlow()

    private val _lastCommandOutput = MutableStateFlow("")
    val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
    private var outputBuffer = StringBuilder()

    suspend fun start() {
        withContext(Dispatchers.IO) {
            isAlive = true
            masterFd = TerminalJni.createSession(24, 80)
            if (masterFd < 0) {
                isAlive = false
                return@withContext
            }

            pfd = ParcelFileDescriptor.adoptFd(masterFd)
            emulator.process("Tunnel Terminal v3.2 (Lifecycle Guard)\n")
            emulator.process("NDK PTY + AI Copilot Active.\n\n")
            _screenDirty.value++
            
            Thread.sleep(100)
            writeRaw("PS1='tunnel@android:~$ '\n")
            Thread { readLoop() }.start()
        }
    }

    // Fungsi untuk memulai ulang sesi yang sudah mati
    suspend fun restart() {
        destroy()
        emulator = TerminalEmulator() // Reset layar
        start()
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

            emulator.process(text)
            outputBuffer.append(text)
            if (outputBuffer.length > 2000) outputBuffer = StringBuilder(outputBuffer.substring(outputBuffer.length - 2000))
            _lastCommandOutput.value = outputBuffer.toString()
            _screenDirty.value++
        }
        
        // Jika keluar dari while loop, berarti proses shell (sh) telah di-exit
        isAlive = false
        emulator.process("\n[Process Exited. Tap screen to restart session.]\n")
        _screenDirty.value++
    }

    fun resizeTerminal(newRows: Int, newCols: Int, fontSize: Float) {
        if (masterFd < 0) return
        TerminalJni.resize(masterFd, newRows, newCols)
        emulator.resize(newRows, newCols, androidx.compose.ui.unit.sp(fontSize))
        _screenDirty.value++
    }

    fun executeCommand(command: String) { if (isAlive) writeRaw(command + "\n") }
    fun writeRaw(data: String) {
        if (masterFd < 0 || !isAlive) return
        TerminalJni.write(masterFd, data.toByteArray(StandardCharsets.UTF_8))
    }

    fun clearScreen() {
        emulator.process("\u001B[2J\u001B[H")
        outputBuffer.clear()
        _lastCommandOutput.value = ""
        _screenDirty.value++
    }

    fun destroy() {
        try {
            isAlive = false
            if (masterFd >= 0) TerminalJni.close(masterFd)
            pfd?.close()
        } catch (e: Exception) { }
    }
}
