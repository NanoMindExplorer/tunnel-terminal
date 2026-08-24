package com.tunnel.terminal

import java.io.File

/**
 * SessionTargetResolver - path abstraction for AI tools per active session.
 *
 * Phase 57 + Wave-23:
 * - Local: workspaceRoot (filesDir/workspace)
 * - Ubuntu: host rootfs (filesDir/linux/ubuntu) so write_file matches proot bash
 *   under /root; guest /mnt/workspace maps to Android workspace (proot bind)
 * - SSH: SFTP via ToolExecutor (resolver returns placeholder File only)
 *
 * Ubuntu file I/O uses java.io.File on the host rootfs tree (not shell typing).
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
     * Resolve AI path to the correct physical Android File for this session.
     *
     * @param logicalPath path the AI sees (e.g. "/root/app.py" or "main.py")
     */
    fun resolvePhysicalPath(logicalPath: String): File {
        /* Chat Apply re-submits host absolute paths (filesDir/...). Don't wrap
         * those again under rootfs or they nest as rootfs/data/data/... */
        if (logicalPath.startsWith("/")) {
            val host = try { File(logicalPath).canonicalFile } catch (_: Exception) { File(logicalPath) }
            val ws = try { workspaceRoot.canonicalPath } catch (_: Exception) { workspaceRoot.absolutePath }
            if (isPathInside(host.absolutePath, ws) || isPathInside(host.canonicalPath, ws)) {
                return host
            }
            if (sessionType == "ubuntu" && rootfsDir != null) {
                val rf = try { rootfsDir.canonicalPath } catch (_: Exception) { rootfsDir.absolutePath }
                if (isPathInside(host.absolutePath, rf) || isPathInside(host.canonicalPath, rf)) {
                    return host
                }
            }
        }
        return when (sessionType) {
            "ubuntu" -> {
                val rootfs = rootfsDir
                    ?: throw IllegalStateException("Ubuntu rootfs belum diinstall")
                val path = logicalPath.trim().ifEmpty { "." }
                // Bind-mount alias: /mnt/workspace -> Android workspaceRoot
                if (path == "/mnt/workspace" || path.startsWith("/mnt/workspace/")) {
                    val rel = path.removePrefix("/mnt/workspace").trimStart('/')
                    return if (rel.isEmpty()) workspaceRoot else File(workspaceRoot, rel)
                }
                if (path.startsWith("/")) {
                    File(rootfs, path.removePrefix("/"))
                } else {
                    // Relative -> guest HOME /root
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

    /** Sandbox check: path must stay inside workspace and/or Ubuntu rootfs. */
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
         * Boundary-aware prefix check (prevents workspace_evil matching workspace).
         */
        fun isPathInside(childPath: String, parentPath: String): Boolean {
            val parent = parentPath.trimEnd('/')
            return childPath == parent || childPath.startsWith("$parent/")
        }
    }

    /** Human-readable target description for AI context. */
    fun describeTarget(): String {
        return when (sessionType) {
            "ubuntu" -> buildString {
                append("Ubuntu 24.04 proot rootfs")
                rootfsDir?.let { append(" (host: ${it.absolutePath})") }
                append(". Guest cwd default: /root. ")
                append("write_file relative path -> /root/ ; absolute /foo -> rootfs/foo. ")
                append("Android workspace bind-mounted at /mnt/workspace.")
            }
            "ssh" -> "SSH remote (file I/O via SFTP; prefer run_command for shell ops)"
            else -> "Local workspace (${workspaceRoot.absolutePath})"
        }
    }

    /**
     * Map a host File under rootfs back to the path the Ubuntu shell sees.
     * Example: .../linux/ubuntu/root/demo.py -> /root/demo.py
     */
    fun guestPathForPhysical(file: File): String? {
        if (sessionType != "ubuntu" || rootfsDir == null) return null
        val rootfsPath = try { rootfsDir.canonicalPath } catch (_: Exception) { rootfsDir.absolutePath }
        val filePath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (!isPathInside(filePath, rootfsPath)) {
            // Maybe under Android workspace bind
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

    /** Tool-prompt fragment describing how paths work for this session. */
    fun pathInstructionsForAi(): String {
        return when (sessionType) {
            "ubuntu" -> """
                ## PATH FILE DI SESI UBUNTU (WAJIB IKUTI)
                - Path RELATIF (mis. "demo.py", "src/main.py") -> file di /root/ di dalam Ubuntu.
                  Host disk: linux/ubuntu/root/... - shell proot melihat /root/demo.py.
                - Path ABSOLUT guest (mis. "/tmp/x.txt", "/root/app.py") -> di rootfs Ubuntu.
                - /mnt/workspace/... -> workspace Android (bind-mount), jarang dipakai.
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
                - Path relatif -> workspace app (filesDir/workspace/...).
                - Path absolut device (Download) hanya setelah setup-storage (SAF).
            """.trimIndent()
        }
    }
}
