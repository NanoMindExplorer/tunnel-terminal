package com.tunnel.terminal

import java.io.File

/**
 * SessionTargetResolver — Lapisan Abstraksi Path & Eksekusi.
 *
 * Phase 57 + Wave-23: ToolExecutor path resolution per active session.
 *
 * - Local → workspaceRoot (filesDir/workspace/...)
 * - Ubuntu → rootfs on disk (filesDir/linux/ubuntu/...) so write_file lands where
 *   proot bash sees it under /root or absolute guest paths.
 *   Guest `/mnt/workspace/*` maps to Android workspace (bind-mounted by proot).
 * - SSH → SFTP via ToolExecutor (resolver returns placeholder File only).
 *
 * File I/O for Ubuntu uses java.io.File on the host rootfs tree (not shell typing).
 */
class SessionTargetResolver(
    val sessionType: String,
    private val workspaceRoot: File,
    private val rootfsDir: File?
) {
    /** Guest HOME for Ubuntu sessions. */
    val guestHome: String
        get() = when (sessionType) {
            "ubuntu" -> "/root"
            else -> workspaceRoot.absolutePath
        }

    /**
     * Resolve path AI ke File fisik Android yang benar, berdasarkan sesi aktif.
     *
     * @param logicalPath Path yang AI "lihat" (mis. "/root/app.py" atau "main.py")
     * @return File fisik yang bisa di-read/write langsung dengan java.io.File
     */
    fun resolvePhysicalPath(logicalPath: String): File {
        return when (sessionType) {
            "ubuntu" -> {
                val rootfs = rootfsDir
                    ?: throw IllegalStateException("Ubuntu rootfs belum diinstall")
                val path = logicalPath.trim().ifEmpty { "." }
                /* Bind-mount alias: /mnt/workspace → Android workspaceRoot */
                if (path == "/mnt/workspace" || path.startsWith("/mnt/workspace/")) {
                    val rel = path.removePrefix("/mnt/workspace").trimStart('/')
                    return if (rel.isEmpty()) workspaceRoot else File(workspaceRoot, rel)
                }
                if (path.startsWith("/")) {
                    File(rootfs, path.removePrefix("/"))
                } else {
                    /* Relative → guest $HOME (/root/...) */
                    val rel = if (path == ".") "" else path.removePrefix("./")
                    if (rel.isEmpty()) File(rootfs, "root")
                    else File(rootfs, "root/$rel")
                }
            }
            "ssh" -> {
                if (logicalPath.startsWith("/")) {
                    File(workspaceRoot, logicalPath.removePrefix("/"))
                } else {
                    File(workspaceRoot, logicalPath)
                }
            }
            else -> {
                if (logicalPath.startsWith("/")) {
                    File(logicalPath)
                } else {
                    File(workspaceRoot, logicalPath)
                }
            }
        }
    }

    /** Cek apakah path berada di dalam area yang diizinkan (sandbox check). */
    fun isPathAllowed(file: File): Boolean {
        val canonicalPath = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
        val workspacePath = try { workspaceRoot.canonicalPath } catch (e: Exception) { workspaceRoot.absolutePath }
        if (isPathInside(canonicalPath, workspacePath)) return true

        if (sessionType == "ubuntu" && rootfsDir != null) {
            val rootfsPath = try { rootfsDir.canonicalPath } catch (e: Exception) { rootfsDir.absolutePath }
            return isPathInside(canonicalPath, rootfsPath)
        }

        return false
    }

    companion object {
        /**
         * Wave-1: Prefix check with path boundary — prevents `workspace_evil` matching
         * prefix of `.../workspace`.
         */
        fun isPathInside(childPath: String, parentPath: String): Boolean {
            val parent = parentPath.trimEnd('/')
            return childPath == parent || childPath.startsWith("$parent/")
        }
    }

    /** Deskripsi target untuk AI context + tool prompts. */
    fun describeTarget(): String {
        return when (sessionType) {
            "ubuntu" -> buildString {
                append("Ubuntu 24.04 proot rootfs")
                rootfsDir?.let { append(" (host: ${it.absolutePath})") }
                append(". Guest cwd default: /root. ")
                append("write_file path relatif → /root/… ; absolute /foo → rootfs/foo. ")
                append("Android workspace ter-bind di /mnt/workspace.")
            }
            "ssh" -> "SSH remote (file I/O via SFTP; prefer run_command for shell ops)"
            else -> "Local workspace (${workspaceRoot.absolutePath})"
        }
    }

    /**
     * Map a host File under rootfs back to the path the Ubuntu shell sees.
     * E.g. …/linux/ubuntu/root/demo.py → /root/demo.py
     */
    fun guestPathForPhysical(file: File): String? {
        if (sessionType != "ubuntu" || rootfsDir == null) return null
        val rootfsPath = try { rootfsDir.canonicalPath } catch (_: Exception) { rootfsDir.absolutePath }
        val filePath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (!isPathInside(filePath, rootfsPath)) {
            /* Maybe under Android workspace bind */
            val ws = try { workspaceRoot.canonicalPath } catch (_: Exception) { workspaceRoot.absolutePath }
            if (isPathInside(filePath, ws)) {
                val rel = filePath.removePrefix(ws).trimStart('/')
                return if (rel.isEmpty()) "/mnt/workspace" else "/mnt/workspace/$rel"
            }
            return null
        }
        val rel = filePath.removePrefix(rootfsPath).trimStart('/')
        return if (rel.isEmpty()) "/" else "/$rel"
    }

    /** Tool-prompt fragment: how paths work for this session. */
    fun pathInstructionsForAi(): String {
        return when (sessionType) {
            "ubuntu" -> """
                ## PATH FILE DI SESI UBUNTU (WAJIB IKUTI)
                - Path RELATIF (mis. "demo.py", "src/main.py") → file di /root/ di dalam Ubuntu.
                  Host disk: linux/ubuntu/root/… — shell proot melihat /root/demo.py.
                - Path ABSOLUT guest (mis. "/tmp/x.txt", "/root/app.py") → di rootfs Ubuntu.
                - /mnt/workspace/... → workspace Android (bind-mount), jarang dipakai.
                - run_command dijalankan di bash Ubuntu (cwd biasanya /root).
                  Setelah write_file "x.py", jalankan: python3 /root/x.py atau python3 x.py
                - JANGAN pakai path Android /data/data/... di run_command.
                - apt: DEBIAN_FRONTEND=noninteractive apt-get install -y <pkg>
            """.trimIndent()
            "ssh" -> """
                ## PATH DI SESI SSH
                - Prefer run_command untuk operasi file remote.
                - write_file/read_file memakai SFTP ke path remote.
            """.trimIndent()
            else -> """
                ## PATH DI SESI LOCAL ANDROID
                - Path relatif → workspace app (filesDir/workspace/...).
                - Path absolut device (Download) hanya setelah setup-storage (SAF).
            """.trimIndent()
        }
    }
}
