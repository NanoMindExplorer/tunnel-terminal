package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Wave-23: Ubuntu path mapping for AI tools must land under rootfs /root,
 * not the Android app workspace.
 */
class UbuntuSessionPathTest {

    private val workspace = File("/tmp/tt-test-workspace").apply { mkdirs() }
    private val rootfs = File("/tmp/tt-test-rootfs").apply {
        mkdirs()
        File(this, "root").mkdirs()
    }

    @Test
    fun `ubuntu relative path maps to guest home`() {
        val r = SessionTargetResolver("ubuntu", workspace, rootfs)
        val f = r.resolvePhysicalPath("demo.py")
        assertTrue(f.absolutePath.replace('\\', '/').endsWith("/root/demo.py"))
        assertTrue(r.isPathAllowed(f))
    }

    @Test
    fun `ubuntu absolute guest path maps into rootfs`() {
        val r = SessionTargetResolver("ubuntu", workspace, rootfs)
        val f = r.resolvePhysicalPath("/tmp/out.txt")
        assertTrue(f.absolutePath.replace('\\', '/').endsWith("/tmp/out.txt"))
        assertTrue(f.absolutePath.contains("tt-test-rootfs"))
    }

    @Test
    fun `ubuntu mnt workspace maps to android workspace`() {
        val r = SessionTargetResolver("ubuntu", workspace, rootfs)
        val f = r.resolvePhysicalPath("/mnt/workspace/a.txt")
        assertEquals(File(workspace, "a.txt").absolutePath, f.absolutePath)
    }

    @Test
    fun `guestPathForPhysical reverses mapping`() {
        val r = SessionTargetResolver("ubuntu", workspace, rootfs)
        val host = File(rootfs, "root/app.py")
        host.parentFile?.mkdirs()
        host.writeText("x")
        assertEquals("/root/app.py", r.guestPathForPhysical(host))
    }

    @Test
    fun `local relative still uses workspace`() {
        val r = SessionTargetResolver("local", workspace, null)
        val f = r.resolvePhysicalPath("x.py")
        assertEquals(File(workspace, "x.py").absolutePath, f.absolutePath)
    }

    @Test
    fun `guest home for ubuntu is root`() {
        val r = SessionTargetResolver("ubuntu", workspace, rootfs)
        assertEquals("/root", r.guestHome)
    }

    @Test
    fun `host absolute path under rootfs is not wrapped again`() {
        val r = SessionTargetResolver("ubuntu", workspace, rootfs)
        val host = File(rootfs, "root/demo.py")
        host.parentFile?.mkdirs()
        host.writeText("ok")
        val f = r.resolvePhysicalPath(host.absolutePath)
        assertEquals(host.canonicalPath, f.canonicalPath)
    }
}
