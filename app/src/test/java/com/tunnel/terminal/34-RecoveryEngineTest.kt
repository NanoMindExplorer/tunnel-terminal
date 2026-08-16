package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * v9.5.4 Phase 5: Unit tests for RecoveryEngine.
 * Tests all recovery patterns: loading, keyboard, click failed, app open failed, unknown.
 */
class RecoveryEngineTest {

    private lateinit var engine: RecoveryEngine

    @Before
    fun setup() {
        engine = RecoveryEngine()
    }

    @Test fun `loading spinner detected → wait action`() {
        val result = engine.diagnose("click_text", "Loading... Please wait")
        assertEquals("wait", result.action)
        assertTrue(result.description.contains("loading"))
    }

    @Test fun `progress bar detected → wait action`() {
        val result = engine.diagnose("click_text", "Progress: 50%")
        assertEquals("wait", result.action)
    }

    @Test fun `spinner keyword detected → wait action`() {
        val result = engine.diagnose("click_text", "spinner visible")
        assertEquals("wait", result.action)
    }

    @Test fun `keyboard blocking → press back`() {
        val result = engine.diagnose("type_text", "Gboard is visible")
        assertEquals("press_back", result.action)
        assertTrue(result.description.contains("Keyboard"))
    }

    @Test fun `keyboard keyword detected → press back`() {
        val result = engine.diagnose("type_text", "Some keyboard input")
        assertEquals("press_back", result.action)
    }

    @Test fun `click failed + scrollable → scroll down`() {
        val result = engine.diagnose("click_text", "List view is scrollable")
        assertEquals("scroll", result.action)
        assertEquals("down", result.params["direction"])
        assertTrue(result.description.contains("scroll"))
    }

    @Test fun `click_at failed + scrollable → scroll down`() {
        val result = engine.diagnose("click_at", "Content is scrollable")
        assertEquals("scroll", result.action)
    }

    @Test fun `click failed + not scrollable → press back`() {
        val result = engine.diagnose("click_text", "Static screen, no scrolling")
        assertEquals("press_back", result.action)
        assertTrue(result.description.contains("back"))
    }

    @Test fun `open_app failed → press home`() {
        val result = engine.diagnose("open_app", "Home screen")
        assertEquals("press_home", result.action)
        assertTrue(result.description.contains("home"))
    }

    @Test fun `unknown failure → press back (safe default)`() {
        val result = engine.diagnose("type_text", "Normal screen content")
        assertEquals("press_back", result.action)
        assertTrue(result.description.contains("Unknown"))
    }

    @Test fun `loading takes priority over keyboard`() {
        val result = engine.diagnose("click_text", "Loading Gboard...")
        assertEquals("wait", result.action)
    }

    @Test fun `keyboard takes priority over click failure`() {
        val result = engine.diagnose("click_text", "Gboard keyboard visible")
        assertEquals("press_back", result.action)
        assertTrue(result.description.contains("Keyboard"))
    }

    @Test fun `empty screen content → press back`() {
        val result = engine.diagnose("click_text", "")
        assertEquals("press_back", result.action)
    }

    @Test fun `case insensitive matching`() {
        val result = engine.diagnose("click_text", "LOADING DATA")
        assertEquals("wait", result.action)
    }

    @Test fun `RecoveryAction data class has all fields`() {
        val action = RecoveryEngine.RecoveryAction(
            action = "scroll",
            params = mapOf("direction" to "up"),
            description = "Scroll up to find target"
        )
        assertEquals("scroll", action.action)
        assertEquals("up", action.params["direction"])
        assertEquals("Scroll up to find target", action.description)
    }
}
