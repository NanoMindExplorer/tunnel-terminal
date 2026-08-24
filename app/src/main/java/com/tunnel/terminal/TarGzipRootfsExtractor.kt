package com.tunnel.terminal

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Stream-extract a gzip-compressed POSIX tar (ustar/pax) into a rootfs directory.
 *
 * Designed for Android app-private storage where:
 * - mknod / device nodes are not allowed (skipped)
 * - hard links are best-effort copies
 * - directory modes must keep owner write so subsequent members can be written
 * - absolute-path symlinks are stored as-is (proot --link2symlink rewrites at runtime)
 *
 * Algorithm mirrors Termux proot-distro tar_extract invariants (skip devices,
 * no path escape, hard-link deferred copy, dirs always owner-writable).
 */
object TarGzipRootfsExtractor {

    private const val BLOCK = 512
    private val ZERO = ByteArray(BLOCK)

    fun extract(
        tarball: File,
        destDir: File,
        onProgress: (percent: Int) -> Unit = {}
    ) {
        /* Gzip header is 10+ bytes; the 15MB Ubuntu floor lives in ProotBootstrap. */
        if (!tarball.isFile || tarball.length() < 18L) {
            throw IOException("Tarball tidak valid: ${tarball.absolutePath}")
        }
        destDir.mkdirs()
        val total = tarball.length().coerceAtLeast(1L)
        var lastPct = -1

        /* Count compressed bytes via a counting stream for progress. */
        CountingInputStream(BufferedInputStream(FileInputStream(tarball), 64 * 1024)).use { counted ->
            GZIPInputStream(counted, 64 * 1024).use { gis ->
                val deferredHardLinks = mutableListOf<Pair<String, String>>()
                var emptyBlocks = 0
                val header = ByteArray(BLOCK)

                /* No `continue` inside inline use{} (Kotlin experimental). */
                var done = false
                while (!done) {
                    val n = readFully(gis, header)
                    if (n == 0) {
                        done = true
                    } else if (n < BLOCK) {
                        throw IOException("Tar header truncated ($n bytes)")
                    } else if (header.contentEquals(ZERO)) {
                        emptyBlocks++
                        if (emptyBlocks >= 2) done = true
                    } else {
                        emptyBlocks = 0
                        val type = header[156].toInt().toChar()
                        val size = parseOctal(header, 124, 12)
                        val name = resolveName(header)
                        val linkname = headerString(header, 157, 100)

                        when {
                            type == 'x' || type == 'g' || type == 'X' || type == 'L' || type == 'K' -> {
                                /* pax / GNU long-name metadata — skip payload. */
                                skipPayload(gis, size)
                            }
                            else -> {
                                val rel = normalizeMemberPath(name)
                                if (rel == null) {
                                    skipPayload(gis, size)
                                } else {
                                    when (type) {
                                        '5', '/' -> {
                                            ensureDir(File(destDir, rel), ownerWritable = true)
                                            skipPayload(gis, size)
                                        }
                                        '2' -> {
                                            writeSymlink(File(destDir, rel), linkname)
                                            skipPayload(gis, size)
                                        }
                                        '1' -> {
                                            val target = normalizeMemberPath(linkname)
                                            if (target != null) deferredHardLinks.add(rel to target)
                                            skipPayload(gis, size)
                                        }
                                        '3', '4', '6' -> {
                                            /* Char/block/fifo — skip (Android cannot mknod). */
                                            skipPayload(gis, size)
                                        }
                                        '0', '\u0000', '7' -> {
                                            if (rel.endsWith("/")) {
                                                ensureDir(File(destDir, rel.trimEnd('/')), ownerWritable = true)
                                                skipPayload(gis, size)
                                            } else {
                                                writeRegular(File(destDir, rel), gis, size, modeFromHeader(header))
                                            }
                                        }
                                        else -> skipPayload(gis, size)
                                    }
                                }
                            }
                        }
                        lastPct = report(counted.count, total, onProgress, lastPct)
                    }
                }

                for ((rel, targetRel) in deferredHardLinks) {
                    val dest = File(destDir, rel)
                    val src = File(destDir, targetRel)
                    try {
                        dest.parentFile?.mkdirs()
                        if (src.isFile) {
                            if (dest.exists()) dest.delete()
                            src.copyTo(dest, overwrite = true)
                            dest.setReadable(true, false)
                            dest.setWritable(true, true)
                            if (src.canExecute()) dest.setExecutable(true, false)
                        }
                    } catch (_: Exception) {
                        /* non-fatal */
                    }
                }
            }
        }
        onProgress(100)
    }

    private fun modeFromHeader(header: ByteArray): Int {
        val mode = parseOctal(header, 100, 8).toInt()
        return if (mode == 0) 0x1A4 else mode and 0xFFF
    }

    private fun writeRegular(dest: File, input: InputStream, size: Long, mode: Int) {
        dest.parentFile?.let { ensureDir(it, ownerWritable = true) }
        if (dest.exists()) {
            if (dest.isDirectory) return
            dest.delete()
        }
        FileOutputStream(dest).use { out ->
            var remaining = size
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val r = input.read(buf, 0, toRead)
                if (r < 0) throw IOException("Unexpected EOF extracting ${dest.name}")
                out.write(buf, 0, r)
                remaining -= r
            }
        }
        /* Pad to 512-byte boundary already handled by skip of padding in caller via size+pad. */
        val pad = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
        if (pad > 0) skipBytes(input, pad.toLong())

        applyFileMode(dest, mode)
    }

    private fun writeSymlink(dest: File, target: String) {
        dest.parentFile?.let { ensureDir(it, ownerWritable = true) }
        if (dest.exists()) {
            val isSymlink = try {
                Files.isSymbolicLink(dest.toPath())
            } catch (_: Throwable) {
                false
            }
            if (dest.isDirectory && !isSymlink) {
                val children = dest.list()
                if (children != null && children.isNotEmpty()) {
                    /* Populated dir — keep contents; do not replace with a link. */
                    return
                }
                dest.delete()
            } else {
                dest.delete()
            }
        }
        /* Store the tar link target verbatim. File(target).toPath() would resolve a
         * relative "usr/bin" against the JVM cwd and break Ubuntu usr-merge
         * (bin → /usr/bin on the host, guest loader missing → proot dies in ~20ms). */
        val destPath = dest.toPath()
        val targetPath = destPath.fileSystem.getPath(target)
        try {
            Files.createSymbolicLink(destPath, targetPath)
            return
        } catch (_: Throwable) {
        }
        try {
            android.system.Os.symlink(target, dest.absolutePath)
            return
        } catch (t: Throwable) {
            throw IOException("Gagal membuat symlink ${dest.name} → $target: ${t.message}")
        }
    }

    private fun ensureDir(dir: File, ownerWritable: Boolean) {
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Gagal membuat direktori: ${dir.absolutePath}")
            }
        }
        if (ownerWritable) {
            dir.setReadable(true, false)
            dir.setWritable(true, true)
            dir.setExecutable(true, false)
        }
    }

    private fun applyFileMode(file: File, mode: Int) {
        /* v9.1.0 fix (H-10): Hapus '|| true' dead code yang membuat ownerRead dan
         * ownerWrite selalu true. Sebelumnya: files yang should be 0o600 (owner-only)
         * become world-readable. Sekarang: respect tar mode bits, but ensure owner
         * write for app updates via Os.chmod below. */
        val ownerExec = (mode and 0x40) != 0 || (mode and 0x49) != 0
        val ownerWrite = (mode and 0x80) != 0
        val ownerRead = (mode and 0x100) != 0
        file.setReadable(ownerRead, false)
        file.setWritable(ownerWrite, true)
        file.setExecutable(ownerExec, false)
        try {
            /* Ensure owner write bit set for app updates, regardless of tar mode. */
            android.system.Os.chmod(file.absolutePath, mode or 0x80)
        } catch (_: Throwable) {
        }
    }

    private fun normalizeMemberPath(raw: String): String? {
        var p = raw.trim()
        if (p.isEmpty() || p == "." || p == "./") return null
        if (p.startsWith("./")) p = p.removePrefix("./")
        while (p.startsWith("/")) p = p.removePrefix("/")
        if (p.isEmpty()) return null
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun resolveName(header: ByteArray): String {
        val name = headerString(header, 0, 100)
        val prefix = headerString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun headerString(header: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && header[end] != 0.toByte()) end++
        if (end == off) return ""
        return String(header, off, end - off, StandardCharsets.UTF_8)
    }

    private fun parseOctal(header: ByteArray, off: Int, len: Int): Long {
        var value = 0L
        val end = off + len
        var i = off
        while (i < end && (header[i] == ' '.code.toByte() || header[i] == 0.toByte())) i++
        while (i < end) {
            val c = header[i].toInt().toChar()
            if (c in '0'..'7') {
                value = (value shl 3) + (c - '0')
            } else {
                break
            }
            i++
        }
        return value
    }

    private fun skipPayload(input: InputStream, size: Long) {
        if (size <= 0) return
        val padded = size + ((BLOCK - (size % BLOCK)) % BLOCK)
        skipBytes(input, padded)
    }

    private fun skipBytes(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            /* skip() may return 0 — fall back to read */
            if (input.read() < 0) throw IOException("EOF while skipping tar payload")
            left--
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return off
            off += r
        }
        return off
    }

    private fun report(count: Long, total: Long, onProgress: (Int) -> Unit, last: Int): Int {
        val pct = ((count * 100) / total).toInt().coerceIn(0, 99)
        if (pct != last) onProgress(pct)
        return pct
    }

    /** InputStream that counts bytes read (compressed side for progress). */
    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val r = inner.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = inner.read(b, off, len)
            if (r > 0) count += r
            return r
        }

        override fun close() = inner.close()
        override fun available(): Int = inner.available()
    }

    /**
     * Finalize modes after any extract strategy: owner-writable dirs, sticky tmp,
     * executable shells, and proot host binaries.
     */
    fun finalizeRootfsPermissions(rootfsDir: File, prootBin: File?, libDir: File?) {
        if (!rootfsDir.isDirectory) return

        val essential = listOf(
            "tmp", "var/tmp", "dev", "proc", "sys", "root", "home",
            "run", "mnt", "mnt/workspace", "etc", "etc/profile.d"
        )
        for (rel in essential) {
            val d = File(rootfsDir, rel)
            d.mkdirs()
            val mode = if (rel == "tmp" || rel == "var/tmp") 0x3FF else 0x1ED
            chmodBestEffort(d, mode)
        }

        /* Walk dirs (bounded) — keep owner rwx so apt/AI can write. */
        try {
            rootfsDir.walkTopDown()
                .maxDepth(64)
                .filter { it.isDirectory }
                .forEach { dir ->
                    val path = dir.absolutePath
                    val mode = when {
                        path.endsWith("/tmp") || path.endsWith("/var/tmp") -> 0x3FF
                        else -> 0x1ED
                    }
                    chmodBestEffort(dir, mode)
                }
        } catch (_: Exception) {
        }

        val execCandidates = listOf(
            "usr/bin/bash", "bin/bash", "usr/bin/sh", "bin/sh",
            "usr/bin/dash", "usr/bin/env", "usr/bin/apt-get", "usr/bin/dpkg"
        )
        for (rel in execCandidates) {
            val f = File(rootfsDir, rel)
            if (f.isFile) chmodBestEffort(f, 0x1ED)
        }

        /* Host-side proot must stay 0555 — 0755 is owner-writable and hits W^X EACCES 13. */
        if (prootBin != null && prootBin.isFile) {
            chmodBestEffort(prootBin, 0x16D)
            prootBin.setReadable(true, false)
            prootBin.setWritable(false, false)
            prootBin.setExecutable(true, false)
        }
        if (libDir != null && libDir.isDirectory) {
            libDir.listFiles()?.forEach { lib ->
                if (lib.isFile) {
                    chmodBestEffort(lib, 0x1ED)
                    lib.setReadable(true, false)
                }
            }
        }

        /* Ensure /root is usable as HOME. */
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()
        chmodBestEffort(rootHome, 0x1C0)
        val bashrc = File(rootHome, ".bashrc")
        if (!bashrc.exists()) {
            try {
                bashrc.writeText(
                    "export DEBIAN_FRONTEND=noninteractive\n" +
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                )
            } catch (_: Exception) {
            }
        }
    }

    fun chmodBestEffort(file: File, mode: Int) {
        try {
            android.system.Os.chmod(file.absolutePath, mode)
        } catch (_: Throwable) {
            /* v9.1.0 fix (H-10): Hapus '|| true' — respect mode bits. */
            val ownerExec = (mode and 0x40) != 0
            val ownerWrite = (mode and 0x80) != 0
            val ownerRead = (mode and 0x100) != 0
            file.setReadable(ownerRead, false)
            file.setWritable(ownerWrite, true)
            file.setExecutable(ownerExec || file.isDirectory, false)
        }
    }

    fun rootfsLooksValid(rootfsDir: File): Boolean {
        if (!rootfsDir.isDirectory) return false
        val hasBash =
            File(rootfsDir, "usr/bin/bash").isFile ||
                File(rootfsDir, "bin/bash").exists()
        val hasEtc = File(rootfsDir, "etc").isDirectory
        return hasBash && hasEtc
    }
}
