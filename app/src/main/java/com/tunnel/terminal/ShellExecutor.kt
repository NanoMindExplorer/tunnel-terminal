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

    // StateFlow untuk menampung output yang akan ditampilkan ke UI
    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    // Marker unik untuk menandai akhir dari sebuah perintah
    private val endMarker = "___TUNNEL_CMD_END_${System.currentTimeMillis()}___"

    suspend fun start() {
        withContext(Dispatchers.IO) {
            try {
                process = ProcessBuilder("/system/bin/sh")
                    .redirectErrorStream(true) // Gabungkan stdout dan stderr
                    .start()

                outputWriter = OutputStreamWriter(process?.outputStream)

                // Thread untuk terus membaca output shell
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line == endMarker) {
                        // Abaikan marker, hanya penanda command selesai
                        continue
                    }
                    _output.value = _output.value + line!!
                }
            } catch (e: IOException) {
                _output.value = _output.value + "Gagal memulai shell: ${e.message}"
            }
        }
    }

    // Fungsi untuk mengirim perintah ke shell yang sedang berjalan
    fun executeCommand(command: String) {
        try {
            _output.value = _output.value + "tunnel@android:~$ $command"
            // Kirim perintah, lalu echo marker agar kita tahu perintah ini sudah selesai dieksekusi
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
        process?.destroy()
    }
}
