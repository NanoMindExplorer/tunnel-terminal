package com.tunnel.terminal

import java.io.File

/**
 * SessionTargetResolver — Lapisan Abstraksi Path & Eksekusi.
 *
 * Phase 57 fix (§4.1): ToolExecutor perlu tahu path fisik yang benar berdasarkan
 * tab/sesi yang sedang aktif. Sebelumnya, write_file/read_file/delete_file selalu
 * pakai workspaceRoot lokal Android — kalau user di tab Ubuntu, AI menulis file
 * ke workspace Android, tapi run_command mencarinya di rootfs Ubuntu → "file not found".
 *
 * FIX: SessionTargetResolver mengetahui tipe sesi aktif dan me-resolve path AI
 * ke lokasi fisik yang benar:
 * - Local → workspaceRoot (filesDir/workspace/...)
 * - Ubuntu → rootfsDir + path (filesDir/linux/ubuntu/rootfs/...)
 * - SSH → error (butuh SFTP, tidak bisa pakai java.io.File langsung)
 *
 * Untuk Ubuntu, operasi file TIDAK perlu lewat proot/PTY — rootfs Ubuntu hanyalah
 * folder biasa di penyimpanan Android, jadi Kotlin bisa baca/tulis langsung dengan
 * java.io.File, jauh lebih cepat dan aman daripada "mengetik" isi file lewat shell.
 */
class SessionTargetResolver(
    val sessionType: String,
    private val workspaceRoot: File,
    private val rootfsDir: File?
) {
    /**
     * Resolve path AI ke File fisik Android yang benar, berdasarkan sesi aktif.
     *
     * @param logicalPath Path yang AI "lihat" (mis. "/root/app.py" atau "main.py")
     * @return File fisik yang bisa di-read/write langsung dengan java.io.File
     */
    fun resolvePhysicalPath(logicalPath: String): File {
        return when (sessionType) {
            "ubuntu" -> {
                /* Ubuntu: prefix dengan rootfsDir. Path relatif dianggap relatif terhadap /root. */
                val rootfs = rootfsDir ?: throw IllegalStateException("Ubuntu rootfs belum diinstall")
                val cleanPath = logicalPath.removePrefix("/")
                if (logicalPath.startsWith("/")) {
                    File(rootfs, cleanPath)
                } else {
                    /* Path relatif → /root/ di rootfs Ubuntu */
                    File(rootfs, "root/$logicalPath")
                }
            }
            "ssh" -> {
                /* SSH: tidak bisa pakai java.io.File langsung.
                 * Untuk sekarang, fallback ke workspaceRoot lokal (file tidak akan
                 * terlihat di remote shell, tapi setidaknya tidak crash).
                 * TODO: Implementasi SFTP via JSch ChannelSftp. */
                if (logicalPath.startsWith("/")) {
                    File(workspaceRoot, logicalPath.removePrefix("/"))
                } else {
                    File(workspaceRoot, logicalPath)
                }
            }
            else -> {
                /* Local: relative → workspace; absolute → real filesystem (sandbox
                 * still enforced by isPathAllowed + SAF check in ToolExecutor).
                 * Wave-1: do NOT remap /sdcard/... under workspaceRoot. */
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

        /* Untuk Ubuntu, izinkan akses ke dalam rootfs. */
        if (sessionType == "ubuntu" && rootfsDir != null) {
            val rootfsPath = try { rootfsDir.canonicalPath } catch (e: Exception) { rootfsDir.absolutePath }
            return isPathInside(canonicalPath, rootfsPath)
        }

        /* Untuk path di dalam SAF tree yang sudah di-grant, cek via StorageManager. */
        return false
    }

    companion object {
        private const val TAG = "SessionTargetResolver"

        /**
         * Wave-1: Prefix check with path boundary — prevents `workspace_evil` matching
         * prefix of `.../workspace`.
         */
        fun isPathInside(childPath: String, parentPath: String): Boolean {
            val parent = parentPath.trimEnd('/')
            return childPath == parent || childPath.startsWith("$parent/")
        }
    }

    /** Deskripsi target untuk AI context. */
    fun describeTarget(): String {
        return when (sessionType) {
            "ubuntu" -> "Ubuntu rootfs (${rootfsDir?.absolutePath ?: "not installed"})"
            "ssh" -> "SSH remote (file I/O limited — use run_command for file ops)"
            else -> "Local workspace (${workspaceRoot.absolutePath})"
        }
    }
}
