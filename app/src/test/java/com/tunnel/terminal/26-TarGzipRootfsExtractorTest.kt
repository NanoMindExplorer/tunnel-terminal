package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Wave-26: pure-Java tar.gz rootfs extract + validity helpers.
 */
class TarGzipRootfsExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `rootfsLooksValid requires bash and etc`() {
        val root = tmp.newFolder("empty")
        assertFalse(TarGzipRootfsExtractor.rootfsLooksValid(root))
        File(root, "etc").mkdirs()
        assertFalse(TarGzipRootfsExtractor.rootfsLooksValid(root))
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/bash").writeText("#!/bin/bash\n")
        assertTrue(TarGzipRootfsExtractor.rootfsLooksValid(root))
    }

    @Test
    fun `extract regular file dir and symlink`() {
        val tarGz = tmp.newFile("mini.tar.gz")
        writeMiniRootfsTarGz(tarGz)

        val dest = tmp.newFolder("out")
        TarGzipRootfsExtractor.extract(tarGz, dest)

        assertTrue(File(dest, "etc").isDirectory)
        assertTrue(File(dest, "usr/bin/bash").isFile)
        assertEquals("#!/bin/bash\necho hi\n", File(dest, "usr/bin/bash").readText())
        /* bin -> usr/bin */
        val bin = File(dest, "bin")
        assertTrue("bin should exist as symlink or dir", bin.exists())
        assertTrue(TarGzipRootfsExtractor.rootfsLooksValid(dest))
    }

    @Test
    fun `extract rejects path escape`() {
        val tarGz = tmp.newFile("evil.tar.gz")
        writeTarGzWithName(tarGz, "../evil.txt", "nope".toByteArray())
        val dest = tmp.newFolder("safe")
        TarGzipRootfsExtractor.extract(tarGz, dest)
        assertFalse(File(dest.parentFile, "evil.txt").exists())
        assertFalse(File(dest, "evil.txt").exists())
    }

    @Test
    fun `finalize creates tmp sticky layout`() {
        val root = tmp.newFolder("rootfs")
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/bash").writeText("x")
        File(root, "etc").mkdirs()
        TarGzipRootfsExtractor.finalizeRootfsPermissions(root, null, null)
        assertTrue(File(root, "tmp").isDirectory)
        assertTrue(File(root, "var/tmp").isDirectory)
        assertTrue(File(root, "root").isDirectory)
        assertTrue(File(root, "mnt/workspace").isDirectory)
        assertTrue(File(root, "etc/resolv.conf").parentFile!!.isDirectory || File(root, "etc").isDirectory)
    }

    @Test
    fun `normalize skips device-only archives still succeeds empty-ish`() {
        /* Archive with only a directory — not valid rootfs but must not crash. */
        val tarGz = tmp.newFile("dironly.tar.gz")
        writeTarGzWithDir(tarGz, "opt/empty")
        val dest = tmp.newFolder("d")
        TarGzipRootfsExtractor.extract(tarGz, dest)
        assertTrue(File(dest, "opt/empty").isDirectory)
        assertFalse(TarGzipRootfsExtractor.rootfsLooksValid(dest))
    }

    // --- minimal tar writers (ustar) ---

    private fun writeMiniRootfsTarGz(out: File) {
        val entries = mutableListOf<Pair<String, ByteArray?>>()
        entries += "etc/" to null
        entries += "usr/" to null
        entries += "usr/bin/" to null
        entries += "usr/bin/bash" to "#!/bin/bash\necho hi\n".toByteArray()
        entries += "bin" to null /* will write as symlink via special */
        writeTarGz(out) { sink ->
            for ((name, data) in entries) {
                if (name == "bin") {
                    sink.writeSymlink("bin", "usr/bin")
                } else if (data == null) {
                    sink.writeDir(name)
                } else {
                    sink.writeFile(name, data)
                }
            }
        }
    }

    private fun writeTarGzWithName(out: File, name: String, data: ByteArray) {
        writeTarGz(out) { it.writeFile(name, data) }
    }

    private fun writeTarGzWithDir(out: File, name: String) {
        writeTarGz(out) { it.writeDir(name) }
    }

    private fun writeTarGz(out: File, block: (TarSink) -> Unit) {
        GZIPOutputStream(out.outputStream()).use { gz ->
            val sink = TarSink(gz)
            block(sink)
            sink.finish()
        }
    }

    /** Minimal ustar writer for tests. */
    private class TarSink(private val out: java.io.OutputStream) {
        fun writeDir(name: String) {
            val n = if (name.endsWith("/")) name else "$name/"
            writeHeader(n, 0, '5', "")
        }

        fun writeFile(name: String, data: ByteArray) {
            writeHeader(name, data.size.toLong(), '0', "")
            out.write(data)
            pad(data.size.toLong())
        }

        fun writeSymlink(name: String, target: String) {
            writeHeader(name, 0, '2', target)
        }

        fun finish() {
            out.write(ByteArray(512))
            out.write(ByteArray(512))
        }

        private fun writeHeader(name: String, size: Long, type: Char, link: String) {
            val h = ByteArray(512)
            putString(h, 0, 100, name)
            putOctal(h, 100, 8, 0o755)
            putOctal(h, 108, 8, 0) // uid
            putOctal(h, 116, 8, 0) // gid
            putOctal(h, 124, 12, size)
            putOctal(h, 136, 12, System.currentTimeMillis() / 1000)
            // checksum blank
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            putString(h, 157, 100, link)
            putString(h, 257, 6, "ustar")
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            var sum = 0
            for (b in h) sum += b.toInt() and 0xff
            putOctal(h, 148, 8, sum.toLong())
            out.write(h)
        }

        private fun pad(size: Long) {
            val rem = (512 - (size % 512)) % 512
            if (rem > 0) out.write(ByteArray(rem.toInt()))
        }

        private fun putString(buf: ByteArray, off: Int, len: Int, s: String) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            val n = minOf(len - 1, bytes.size)
            System.arraycopy(bytes, 0, buf, off, n)
        }

        private fun putOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
            val s = value.toString(8)
            val padded = s.padStart(len - 1, '0')
            putString(buf, off, len, padded)
            buf[off + len - 1] = 0
        }
    }
}
