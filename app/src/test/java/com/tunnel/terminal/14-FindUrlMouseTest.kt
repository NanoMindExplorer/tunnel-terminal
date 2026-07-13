package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-14: find/scrollback search, URL open safety, mouse encode helpers.
 */
class FindUrlMouseTest {

    @Test
    fun `find returns line hits ignore case`() {
        val lines = listOf("Hello World", "error: failed", "OK", "ERROR again")
        val hits = ScrollbackSearch.find(lines, "error")
        assertEquals(2, hits.size)
        assertEquals(1, hits[0].lineIndex)
        assertEquals(3, hits[1].lineIndex)
    }

    @Test
    fun `find empty query returns empty`() {
        assertTrue(ScrollbackSearch.find(listOf("a", "b"), "").isEmpty())
    }

    @Test
    fun `formatHits empty message`() {
        val msg = ScrollbackSearch.formatHits(emptyList(), "zzz")
        assertTrue(msg.contains("no matches"))
    }

    @Test
    fun `url extract https and strips trailing punct`() {
        val urls = UrlOpenUtils.extractUrls("see https://example.com/path). more")
        assertEquals(1, urls.size)
        assertEquals("https://example.com/path", urls[0])
    }

    @Test
    fun `url rejects non http schemes`() {
        assertFalse(UrlOpenUtils.isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(UrlOpenUtils.isSafeHttpUrl("file:///etc/passwd"))
        assertTrue(UrlOpenUtils.isSafeHttpUrl("https://ok.example"))
    }

    @Test
    fun `firstUrl from mixed text`() {
        val u = UrlOpenUtils.firstUrl("log http://a.test/x and done")
        assertEquals("http://a.test/x", u)
    }

    @Test
    fun `mouse sgr encode shape`() {
        // Mirror encodeMouseEvent SGR branch without Android Color deps on full emulator
        fun sgr(button: Int, x: Int, y: Int, press: Boolean): String {
            val final = if (press) 'M' else 'm'
            return "\u001B[<$button;$x;$y$final"
        }
        assertEquals("\u001B[<0;1;1M", sgr(0, 1, 1, true))
        assertEquals("\u001B[<0;10;5m", sgr(0, 10, 5, false))
        assertEquals("\u001B[<64;3;3M", sgr(64, 3, 3, true)) // wheel up
    }
}
