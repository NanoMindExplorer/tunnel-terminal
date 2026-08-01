package com.tunnel.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ProjectContext — Deteksi konteks project untuk AI.
 *
 * Phase 50 fix (B-5): Project Context Awareness.
 *
 * OLD GAP: AI tidak otomatis tahu branch git aktif, dependency proyek, atau struktur
 * file saat ini. User harus manual pakai @mentions satu per satu. Ini membatasi kualitas
 * jawaban AI dibanding Cursor/Aider/Claude Code.
 *
 * FIX: Deteksi otomatis:
 * - Git state (branch aktif, status modified/untracked)
 * - Manifest files (package.json, build.gradle, Cargo.toml, pyproject.toml, dll.)
 * - File tree 2 level (maks 200 entri)
 * Inject sebagai system message dengan token budget ~2K.
 *
 * Dipanggil dari AIAgent.buildRequestBody() supaya AI dapat context project setiap request.
 */
class ProjectContext(private val context: Context) {
    companion object {
        private const val TAG = "ProjectContext"
        private const val MAX_FILE_TREE_ENTRIES = 200
        private const val MAX_CONTEXT_CHARS = 2000
    }

    /**
     * v8.6.0 fix (H5): Optional git status provider lambda.
     * MainActivity sets ini ke lambda yang calls MarkerExecutor.executeWithMarker()
     * dengan active session untuk run `git status --porcelain`.
     *
     * Sebelumnya: detectGitState() hanya baca .git/HEAD untuk branch name.
     * Modified/untracked/staged count TIDAK pernah terisi (acknowledged TODO).
     * Sekarang: lambda provider supaya AI dapat real git status di Ubuntu tab.
     *
     * Cache 30 detik untuk avoid repeated PTY round-trips.
     */
    @Volatile
    private var gitStatusProvider: (() -> String?)? = null
    @Volatile
    private var cachedGitStatus: String? = null
    @Volatile
    private var cachedGitStatusTime: Long = 0
    private val GIT_STATUS_CACHE_MS = 30_000L  // 30 seconds

    fun setGitStatusProvider(provider: (() -> String?)?) {
        gitStatusProvider = provider
        cachedGitStatus = null  // invalidate cache
    }

    /**
     * Build project context string untuk AI system prompt.
     * @param workspaceRoot Root direktori project (dari ToolExecutor.getWorkspaceRoot())
     * @param sessionType active terminal session type (local/ssh/ubuntu) for git hints
     * @return Context string, atau empty string kalau tidak ada project yang terdeteksi
     */
    fun buildContext(workspaceRoot: File, sessionType: String = "local"): String {
        if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) return ""

        val sb = StringBuilder()
        var hasContent = false

        // 1. Git state
        val gitInfo = detectGitState(workspaceRoot, sessionType)
        if (gitInfo.isNotBlank()) {
            sb.append("=== Project Context ===\n")
            sb.append(gitInfo)
            hasContent = true
        }

        // 2. Manifest files (package.json, build.gradle, dll.)
        val manifestInfo = detectManifests(workspaceRoot)
        if (manifestInfo.isNotBlank()) {
            if (!hasContent) sb.append("=== Project Context ===\n")
            sb.append(manifestInfo)
            hasContent = true
        }

        // 3. File tree (2 level, maks 200 entri)
        val treeInfo = buildFileTree(workspaceRoot)
        if (treeInfo.isNotBlank()) {
            if (!hasContent) sb.append("=== Project Context ===\n")
            sb.append(treeInfo)
            hasContent = true
        }

        if (hasContent) {
            sb.append("=== End Project Context ===\n")
        }

        val result = sb.toString().take(MAX_CONTEXT_CHARS)
        if (result.length >= MAX_CONTEXT_CHARS) {
            Log.i(TAG, "Project context truncated to ${MAX_CONTEXT_CHARS} chars")
        }
        return result
    }

    /**
     * Deteksi git state: branch aktif + status singkat.
     *
     * Phase 60 fix (audit B-4): Sebelumnya pakai ProcessBuilder("git", ...)
     * yang langsung spawn process dari app Android — tapi Android TIDAK PUNYA
     * binary git terpasang di sistem. Panggilan selalu throw IOException,
     * tertangkap catch, dan section Modified/Untracked/Staged tidak pernah
     * terisi (cuma branch yang kebaca dari file .git/HEAD langsung).
     *
     * Fix:
     * - Untuk sesi "ubuntu": git ada di rootfs Ubuntu via proot, tapi hanya
     *   accessible lewat sesi terminal (MarkerExecutor), bukan ProcessBuilder
     *   langsung. Karena ProjectContext tidak punya referensi ke MarkerExecutor
     *   saat ini, kita skip status modified untuk sesi Ubuntu juga (TODO:
     *   wire MarkerExecutor di sini supaya bisa dapat status real).
     * - Untuk sesi "local" (Android shell): git tidak ada, skip status.
     * - Branch tetap bisa dibaca dari file .git/HEAD langsung (tidak butuh
     *   binary git eksternal).
     *
     * TODO: Untuk mendapatkan status modified/untracked di sesi Ubuntu,
     * ProjectContext perlu referensi ke MarkerExecutor (atau interface
     * abstrak) supaya bisa execute "git status --porcelain" lewat PTY.
     * Saat ini cuma branch yang reliable across all session types.
     */
    private fun detectGitState(root: File, sessionType: String): String {
        val gitDir = File(root, ".git")
        if (!gitDir.exists()) return ""

        val sb = StringBuilder()
        sb.append("Git: yes\n")

        // Coba baca branch aktif dari HEAD (tidak butuh binary git, baca file langsung)
        try {
            val headFile = File(gitDir, "HEAD")
            if (headFile.exists()) {
                val headContent = headFile.readText().trim()
                if (headContent.startsWith("ref: refs/heads/")) {
                    val branch = headContent.removePrefix("ref: refs/heads/")
                    sb.append("Branch: $branch\n")
                } else if (headContent.length == 40) {
                    sb.append("Detached HEAD: ${headContent.take(8)}\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal baca git HEAD: ${e.message}")
        }

        /* Wave-4: Best-effort git extras without spawning git binary:
         * - last commit message from .git/logs/HEAD or COMMIT_EDITMSG
         * - rough dirty signal: compare presence of index vs worktree not feasible
         *   without git; surface session hint for AI. */
        try {
            val commitMsg = File(gitDir, "COMMIT_EDITMSG")
            if (commitMsg.exists() && commitMsg.canRead()) {
                val msg = commitMsg.readText().trim().lineSequence().firstOrNull().orEmpty()
                if (msg.isNotBlank()) sb.append("Last commit message: ${msg.take(120)}\n")
            }
            val logHead = File(gitDir, "logs/HEAD")
            if (logHead.exists() && logHead.canRead()) {
                val last = logHead.readLines().lastOrNull().orEmpty()
                // format: <old> <new> <name> <email> <time> <tz>\t<message>
                val tab = last.indexOf('\t')
                if (tab >= 0 && tab + 1 < last.length) {
                    val logMsg = last.substring(tab + 1).trim()
                    if (logMsg.isNotBlank() && !sb.contains("Last commit message:")) {
                        sb.append("Last log: ${logMsg.take(120)}\n")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal baca git log: ${e.message}")
        }

        /* v8.6.0 fix (H5): Coba get real git status via provider (MarkerExecutor).
         * Provider di-set oleh MainActivity ke lambda yang calls executeWithMarker
         * dengan active session. Cache 30 detik untuk avoid repeated PTY calls.
         * Jika provider tidak tersedia (local Android shell), fallback ke hint. */
        val provider = gitStatusProvider
        if (provider != null) {
            val now = System.currentTimeMillis()
            if (cachedGitStatus != null && (now - cachedGitStatusTime) < GIT_STATUS_CACHE_MS) {
                sb.append(cachedGitStatus)
            } else {
                try {
                    val statusOutput = provider()
                    if (statusOutput != null && statusOutput.isNotBlank()) {
                        val lines = statusOutput.trim().lines().filter { it.isNotBlank() }
                        val modified = lines.count { it.startsWith(" M") || it.startsWith("M ") }
                        val untracked = lines.count { it.startsWith("??") }
                        val staged = lines.count { it.startsWith("A ") || it.startsWith("M  ") }
                        val statusLine = "Modified: $modified, Untracked: $untracked, Staged: $staged\n"
                        sb.append(statusLine)
                        cachedGitStatus = statusLine
                        cachedGitStatusTime = now
                    } else {
                        val cleanLine = "Status: clean (no modified files)\n"
                        sb.append(cleanLine)
                        cachedGitStatus = cleanLine
                        cachedGitStatusTime = now
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Git status provider failed: ${e.message}")
                    sb.append("Status: (git status unavailable: ${e.message?.take(50)})\n")
                }
            }
        } else {
            sb.append(
                when (sessionType) {
                    "ubuntu" -> "Status: run `git status --porcelain` in Ubuntu tab for full dirty list\n"
                    "ssh" -> "Status: run `git status` on remote for dirty files\n"
                    else -> "Status: (no system git on Android shell — use Ubuntu tab or remote)\n"
                }
            )
        }

        return sb.toString()
    }

    /** Deteksi manifest files dan ekstrak info singkat. */
    private fun detectManifests(root: File): String {
        val sb = StringBuilder()
        val manifests = listOf(
            "package.json" to "Node.js",
            "build.gradle" to "Gradle/Java",
            "build.gradle.kts" to "Gradle/Kotlin",
            "Cargo.toml" to "Rust",
            "pyproject.toml" to "Python",
            "requirements.txt" to "Python",
            "go.mod" to "Go",
            "pom.xml" to "Maven/Java",
            "CMakeLists.txt" to "C/C++",
            "Makefile" to "Make"
        )

        for ((filename, projectType) in manifests) {
            val file = File(root, filename)
            if (file.exists() && file.canRead()) {
                sb.append("Project type: $projectType ($filename)\n")
                // Ekstrak nama/versi untuk package.json
                if (filename == "package.json") {
                    try {
                        val content = file.readText()
                        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        nameMatch?.let { sb.append("  Name: ${it.groupValues[1]}\n") }
                        versionMatch?.let { sb.append("  Version: ${it.groupValues[1]}\n") }
                    } catch (_: Exception) {}
                }
                break  // hanya ambil manifest pertama yang ditemukan
            }
        }

        return sb.toString()
    }

    /** Build file tree 2 level (maks 200 entri). */
    private fun buildFileTree(root: File): String {
        val sb = StringBuilder()
        sb.append("File tree (top 2 levels):\n")

        var entryCount = 0
        try {
            val topFiles = root.listFiles()?.sortedBy { it.name } ?: return ""
            for (f in topFiles) {
                if (entryCount >= MAX_FILE_TREE_ENTRIES) {
                    sb.append("  ... (truncated, ${MAX_FILE_TREE_ENTRIES} entries max)\n")
                    break
                }
                val prefix = if (f.isDirectory) "📁" else "📄"
                sb.append("  $prefix ${f.name}\n")
                entryCount++

                // Level 2 untuk direktori
                if (f.isDirectory && entryCount < MAX_FILE_TREE_ENTRIES) {
                    try {
                        /* v9.3.0 fix (H-12): Cache listFiles() — sebelumnya dipanggil
                         * 2x per direktori (line 277 + 284). Untuk node_modules dengan
                         * 1000+ files, ini adalah 1000+ syscall tambahan. */
                        val allSubFiles = f.listFiles()?.sortedBy { it.name } ?: emptyList()
                        val subFiles = allSubFiles.take(20)
                        for (sf in subFiles) {
                            if (entryCount >= MAX_FILE_TREE_ENTRIES) break
                            val sPrefix = if (sf.isDirectory) "📁" else "📄"
                            sb.append("    $sPrefix ${sf.name}\n")
                            entryCount++
                        }
                        val total = allSubFiles.size
                        if (total > 20) {
                            sb.append("    ... (+${total - 20} more)\n")
                        }
                    } catch (e: Exception) {
                        // Permission denied atau lainnya — skip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal build file tree: ${e.message}")
            return ""
        }

        return sb.toString()
    }
}
