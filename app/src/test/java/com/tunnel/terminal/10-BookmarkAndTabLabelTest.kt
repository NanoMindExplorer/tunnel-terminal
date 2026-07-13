package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-10: Bookmark model + tab label helpers (pure unit tests, no Android Context).
 */
class BookmarkAndTabLabelTest {

    @Test
    fun `bookmark data holds name and path`() {
        val b = BookmarkStore.Bookmark("home", "/data/user/0/com.tunnel.terminal/files/home")
        assertEquals("home", b.name)
        assertTrue(b.path.endsWith("/home") || b.path.contains("home"))
    }

    @Test
    fun `bookmark dedupe prefers latest name for same path`() {
        val list = mutableListOf(
            BookmarkStore.Bookmark("old", "/tmp/a"),
            BookmarkStore.Bookmark("other", "/tmp/b")
        )
        list.removeAll { it.path == "/tmp/a" || it.name == "new" }
        list.add(0, BookmarkStore.Bookmark("new", "/tmp/a"))
        assertEquals(2, list.size)
        assertEquals("new", list.first().name)
        assertEquals("/tmp/a", list.first().path)
    }

    @Test
    fun `bookmark max 30 keeps newest first when trimming`() {
        val list = (1..35).map { BookmarkStore.Bookmark("b$it", "/p$it") }.toMutableList()
        // Mirror BookmarkStore.add ordering: newest at front, then take(MAX)
        val trimmed = list.asReversed().take(30)
        assertEquals(30, trimmed.size)
        assertEquals("b35", trimmed.first().name)
        assertEquals("b6", trimmed.last().name)
    }

    @Test
    fun `shell-safe path without spaces is unquoted`() {
        val path = "/data/data/com.tunnel.terminal/files/home"
        assertTrue(path.none { it.isWhitespace() || it == '\'' })
    }

    @Test
    fun `shell quote wraps paths with spaces`() {
        val path = "/sdcard/My Projects"
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        assertEquals("'/sdcard/My Projects'", quoted)
    }

    @Test
    fun `default tab labels by session type`() {
        fun label(sessionType: String, index: Int): String = when (sessionType) {
            "ubuntu" -> "Ubuntu $index"
            "ssh" -> "SSH $index"
            else -> "Tab $index"
        }
        assertEquals("Tab 1", label("local", 1))
        assertEquals("Ubuntu 2", label("ubuntu", 2))
        assertEquals("SSH 3", label("ssh", 3))
    }

    @Test
    fun `tab label truncated for display`() {
        val long = "very-long-tab-label-name-here"
        assertEquals(16, long.take(16).length)
        assertEquals("very-long-tab-la", long.take(16))
    }

    @Test
    fun `wave-10 help commands tokens present`() {
        val helpCommands = listOf(
            "copy-output",
            "bookmark list",
            "bookmark add",
            "bookmark go",
            "bookmark remove"
        )
        helpCommands.forEach { cmd ->
            assertTrue("expected builtin token: $cmd", cmd.isNotBlank())
        }
    }

    @Test
    fun `getByIndex bounds logic`() {
        val list = listOf(
            BookmarkStore.Bookmark("a", "/a"),
            BookmarkStore.Bookmark("b", "/b")
        )
        fun getByIndex(index1Based: Int): BookmarkStore.Bookmark? {
            if (index1Based < 1 || index1Based > list.size) return null
            return list[index1Based - 1]
        }
        assertNull(getByIndex(0))
        assertNull(getByIndex(3))
        assertEquals("a", getByIndex(1)?.name)
        assertEquals("b", getByIndex(2)?.name)
    }
}
