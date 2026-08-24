package com.tunnel.terminal

import java.io.File

/**
 * v9.4.0: Local pseudo-commands for SAF / MediaStore storage.
 * Uses [StorageManager] public API; side effects via callbacks.
 */
object StorageCommands {

    fun isStorageCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return c == "setup-storage" ||
            c == "storage-status" || c == "storage-reset" || c == "storage-grant-all" ||
            c == "storage-ls" || c.startsWith("storage-ls ") ||
            c == "storage-put" || c.startsWith("storage-put ") ||
            c == "storage-get" || c.startsWith("storage-get ") ||
            c == "storage-save-download" || c.startsWith("storage-save-download ") ||
            c == "storage-write" || c.startsWith("storage-write ") ||
            c == "storage-rm" || c.startsWith("storage-rm ")
    }

    /**
     * @return true if handled (caller must not forward to shell).
     */
    fun handle(
        cmd: String,
        storage: StorageManager,
        resolveLocalFile: (String) -> File?,
        print: (String) -> Unit,
        requestTreePicker: () -> Unit,
        requestAllFiles: () -> Unit
    ): Boolean {
        if (!isStorageCommand(cmd)) return false
        val c = cmd.trim()

        when {
            c == "setup-storage" -> {
                print(
                    "\n\u001B[36m[Setup Storage] Membuka picker folder perangkat...\u001B[0m\n" +
                        "\u001B[33mDisarankan pilih folder Download (atau Documents).\u001B[0m\n" +
                        "\u001B[33mSetelah grant, gunakan storage-ls / storage-put / storage-save-download.\u001B[0m\n"
                )
                requestTreePicker()
            }
            c == "storage-status" -> {
                print("\n${storage.statusReport()}\n")
            }
            c == "storage-reset" -> {
                storage.clearSetup()
                print(
                    "\n\u001B[33m[Storage] Setup direset. Ketik 'setup-storage' untuk pilih folder lagi.\u001B[0m\n"
                )
            }
            c == "storage-grant-all" -> {
                print(
                    "\n\u001B[36m[Storage] Membuka pengaturan \"Akses semua file\"...\u001B[0m\n" +
                        "\u001B[33mIzinkan Tunnel Terminal, lalu kembali ke app.\u001B[0m\n" +
                        "\u001B[33mIni opsional — storage-* (SAF) tetap bekerja tanpa ini.\u001B[0m\n"
                )
                requestAllFiles()
            }
            c == "storage-ls" || c.startsWith("storage-ls ") -> {
                val sub = c.removePrefix("storage-ls").trim()
                val result = storage.listRelative(sub)
                val out = result.fold(
                    onSuccess = { rows ->
                        if (rows.isEmpty()) "(kosong) ${storage.getDisplayName()}/${sub.trim('/')}"
                        else rows.joinToString("\n")
                    },
                    onFailure = { "Error: ${it.message}" }
                )
                print("\n\u001B[36m$out\u001B[0m\n")
            }
            c == "storage-put" || c.startsWith("storage-put ") -> {
                val rest = if (c == "storage-put") "" else c.removePrefix("storage-put ").trim()
                val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
                val srcName = parts.getOrNull(0).orEmpty()
                val dest = parts.getOrNull(1)
                if (srcName.isBlank()) {
                    print("\n\u001B[31mUsage: storage-put <file-workspace|path> [nama-di-folder-SAF]\u001B[0m\n")
                } else {
                    val local = resolveLocalFile(srcName)
                    val r = if (local != null) storage.putLocalFile(local, dest)
                    else Result.failure(IllegalArgumentException("File tidak ditemukan: $srcName"))
                    val msg = r.fold(
                        onSuccess = { "\u001B[32m$it\u001B[0m" },
                        onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                    )
                    print("\n$msg\n")
                }
            }
            c == "storage-get" || c.startsWith("storage-get ") -> {
                val rest = if (c == "storage-get") "" else c.removePrefix("storage-get ").trim()
                val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
                val remote = parts.getOrNull(0).orEmpty()
                val localName = File(parts.getOrNull(1) ?: File(remote).name).name
                if (remote.isBlank()) {
                    print("\n\u001B[31mUsage: storage-get <file-di-folder-SAF> [nama-lokal-di-workspace]\u001B[0m\n")
                } else {
                    val dest = File(storage.workspaceDir, localName)
                    val r = storage.getToLocalFile(remote, dest)
                    val msg = r.fold(
                        onSuccess = { "\u001B[32m$it\u001B[0m" },
                        onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                    )
                    print("\n$msg\n")
                }
            }
            c == "storage-save-download" || c.startsWith("storage-save-download ") -> {
                val rest =
                    if (c == "storage-save-download") "" else c.removePrefix("storage-save-download ").trim()
                val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
                val srcName = parts.getOrNull(0).orEmpty()
                val displayName = parts.getOrNull(1)
                if (srcName.isBlank()) {
                    print(
                        "\n\u001B[31mUsage: storage-save-download <file-workspace> [nama-di-Download]\u001B[0m\n" +
                            "\u001B[33mMenyimpan ke Download publik (MediaStore).\u001B[0m\n"
                    )
                } else {
                    val local = resolveLocalFile(srcName)
                    val r = if (local != null) storage.saveLocalFileToPublicDownloads(local, displayName)
                    else Result.failure(IllegalArgumentException("File tidak ditemukan: $srcName"))
                    val msg = r.fold(
                        onSuccess = { "\u001B[32m$it\u001B[0m" },
                        onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                    )
                    print("\n$msg\n")
                }
            }
            c == "storage-write" || c.startsWith("storage-write ") -> {
                val rest = if (c == "storage-write") "" else c.removePrefix("storage-write ").trim()
                val sp = rest.indexOf(' ')
                if (sp <= 0) {
                    print(
                        "\n\u001B[31mUsage: storage-write <path-relatif-SAF> <teks>\u001B[0m\n" +
                            "\u001B[33mContoh: storage-write catatan.txt Halo dari Tunnel\u001B[0m\n"
                    )
                } else {
                    val rel = rest.substring(0, sp).trim()
                    val text = rest.substring(sp + 1)
                    val r = storage.writeTextRelative(rel, text)
                    val msg = r.fold(
                        onSuccess = { "\u001B[32m$it\u001B[0m" },
                        onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                    )
                    print("\n$msg\n")
                }
            }
            c == "storage-rm" || c.startsWith("storage-rm ") -> {
                val rel = if (c == "storage-rm") "" else c.removePrefix("storage-rm ").trim()
                if (rel.isBlank()) {
                    print("\n\u001B[31mUsage: storage-rm <path-relatif-SAF>\u001B[0m\n")
                } else {
                    val r = storage.deleteRelative(rel)
                    val msg = r.fold(
                        onSuccess = { "\u001B[32m$it\u001B[0m" },
                        onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                    )
                    print("\n$msg\n")
                }
            }
        }
        return true
    }
}
