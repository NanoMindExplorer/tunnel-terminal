package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-25: pure logic tests for skill scope / keyword matching.
 */
class AiSkillTest {

    @Test
    fun `always scope matches any session and mode`() {
        val s = AiSkill(1, "A", "", "body", enabled = true, scopes = setOf("always"))
        assertTrue(s.matchesScope("local", "chat"))
        assertTrue(s.matchesScope("ubuntu", "agent"))
        assertTrue(s.matchesScope("ssh", "chat"))
    }

    @Test
    fun `ubuntu scope only on ubuntu`() {
        val s = AiSkill(1, "U", "", "body", enabled = true, scopes = setOf("ubuntu"))
        assertTrue(s.matchesScope("ubuntu", "chat"))
        assertFalse(s.matchesScope("local", "chat"))
        assertTrue(s.matchesScope("ubuntu", "agent")) // mode agent still ok via session
    }

    @Test
    fun `disabled never matches`() {
        val s = AiSkill(1, "X", "", "body", enabled = false, scopes = setOf("always"))
        assertFalse(s.matchesScope("local", "chat"))
    }

    @Test
    fun `keyword gate`() {
        val s = AiSkill(
            1, "Py", "", "use python3",
            enabled = true,
            scopes = setOf("always"),
            triggerKeywords = listOf("python", "pip")
        )
        assertTrue(s.matchesKeywords("install python package"))
        assertTrue(s.matchesKeywords("PIP freeze"))
        assertFalse(s.matchesKeywords("install nginx only"))
    }

    @Test
    fun `empty keywords always pass`() {
        val s = AiSkill(1, "A", "", "x", triggerKeywords = emptyList())
        assertTrue(s.matchesKeywords("anything"))
    }

    @Test
    fun `scope labels cover all scopes`() {
        SkillManager.ALL_SCOPES.forEach { sc ->
            assertTrue(SkillManager.scopeLabel(sc).isNotBlank())
        }
    }
}
