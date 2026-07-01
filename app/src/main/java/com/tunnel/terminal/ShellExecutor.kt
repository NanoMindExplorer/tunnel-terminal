package com.tunnel.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class ShellExecutor {
    private var process: Process? = null
    private var outputWriter: OutputStreamWriter? = null

    val id: Int = System.currentTimeMillis().toInt()

    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    private val endMarker = "___TUNNEL_CMD_END_${id}___"

    suspend fun start() {
        withContext(Dispatchers.IO) {
            try {
                process = ProcessBuilder("/system/bin/sh")
                    .redirectErrorStream(true)
                    .start()

                outputWriter = OutputStreamWriter(process?.outputStream)

                _output.value = _output.value + "Tunnel Terminal v2.0 (Tab ID: $id)"
                _output.value = _output.value + "Mesin eksekusi aktif. Ketik 'ls', 'pwd', dll."
                _output.value = _output.value + ""

                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line == endMarker) continue
                    _output.value = _output.value + line!!
                }
            } catch (e: IOException) {
                _output.value = _output.value + "Gagal memulai shell: ${e.message}"
            }
        }
    }

    fun executeCommand(command: String) {
        try {
            _output.value = _output.value + "tunnel@android:~$ $command"
            outputWriter?.write("$command\necho $endMarker\n")
            outputWriter?.flush()
        } catch (e: IOException) {
            _output.value = _output.value + "Gagal mengeksekusi: ${e.message}"
        }
    }

    fun clearScreen() {
        _output.value = emptyList()
    }

    fun destroy() {
        try {
            outputWriter?.close()
            process?.destroy()
        } catch (e: Exception) {
            // Abaikan error saat penutupan
        }
    }
}
