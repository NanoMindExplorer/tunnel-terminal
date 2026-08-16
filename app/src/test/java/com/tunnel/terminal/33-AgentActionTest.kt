package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * v9.5.4 Phase 5: Unit tests for AgentAction data class + JSON parsing.
 * Tests: fromJson, extractJson, isSafe, displayText, SUPPORTED_ACTIONS.
 */
class AgentActionTest {

    @Test fun `fromJson parses valid JSON`() {
        val json = """{"action":"click_text","params":{"text":"OK"},"reasoning":"click button","is_complete":false}"""
        val action = AgentAction.fromJson(json)
        assertNotNull(action)
        assertEquals("click_text", action!!.action)
        assertEquals("OK", action.params["text"])
        assertEquals("click button", action.reasoning)
        assertFalse(action.isComplete)
    }

    @Test fun `fromJson parses done action`() {
        val json = """{"action":"done","params":{},"reasoning":"task complete","is_complete":true}"""
        val action = AgentAction.fromJson(json)
        assertNotNull(action)
        assertEquals("done", action!!.action)
        assertTrue(action.isComplete)
    }

    @Test fun `fromJson returns null for invalid JSON`() {
        val action = AgentAction.fromJson("not json at all")
        assertNull(action)
    }

    @Test fun `fromJson returns null for empty string`() {
        val action = AgentAction.fromJson("")
        assertNull(action)
    }

    @Test fun `fromJson handles missing params`() {
        val json = """{"action":"press_back","reasoning":"go back","is_complete":false}"""
        val action = AgentAction.fromJson(json)
        assertNotNull(action)
        assertEquals("press_back", action!!.action)
        assertTrue(action.params.isEmpty())
    }

    @Test fun `fromJson handles missing reasoning`() {
        val json = """{"action":"scroll","params":{"direction":"down"},"is_complete":false}"""
        val action = AgentAction.fromJson(json)
        assertNotNull(action)
        assertEquals("", action!!.reasoning)
    }

    @Test fun `fromJson handles missing is_complete`() {
        val json = """{"action":"wait","params":{}}"""
        val action = AgentAction.fromJson(json)
        assertNotNull(action)
        assertFalse(action!!.isComplete)
    }

    @Test fun `extractJson from markdown code block`() {
        val text = "Here is my response:\n```json\n{\"action\":\"click_text\",\"params\":{\"text\":\"OK\"}}\n```\nDone."
        val json = AgentAction.extractJson(text)
        assertTrue(json.contains("\"action\""))
        assertTrue(json.contains("\"click_text\""))
    }

    @Test fun `extractJson from plain JSON`() {
        val text = """{"action":"scroll","params":{"direction":"down"}}"""
        val json = AgentAction.extractJson(text)
        assertEquals(text, json)
    }

    @Test fun `extractJson from text with JSON embedded`() {
        val text = "I think you should {\"action\":\"wait\",\"params\":{}} now."
        val json = AgentAction.extractJson(text)
        assertTrue(json.contains("\"action\""))
        assertTrue(json.contains("\"wait\""))
    }

    @Test fun `extractJson from empty string`() {
        val json = AgentAction.extractJson("")
        assertEquals("", json)
    }

    @Test fun `isSafe returns true for supported actions`() {
        for (action in AgentAction.SUPPORTED_ACTIONS) {
            val a = AgentAction(action, emptyMap())
            assertTrue("$action should be safe", a.isSafe)
        }
    }

    @Test fun `isSafe returns false for run_adb_command`() {
        val action = AgentAction("run_adb_command", mapOf("command" to "rm -rf /"))
        assertFalse(action.isSafe)
    }

    @Test fun `isSafe returns false for unknown action`() {
        val action = AgentAction("hack_device", emptyMap())
        assertFalse(action.isSafe)
    }

    @Test fun `displayText for click_text`() {
        val action = AgentAction("click_text", mapOf("text" to "Submit"))
        assertEquals("Click: Submit", action.displayText)
    }

    @Test fun `displayText for click_at`() {
        val action = AgentAction("click_at", mapOf("x" to "540", "y" to "960"))
        assertEquals("Click at: (540, 960)", action.displayText)
    }

    @Test fun `displayText for type_text`() {
        val action = AgentAction("type_text", mapOf("text" to "hello"))
        assertEquals("Type: hello", action.displayText)
    }

    @Test fun `displayText for press_enter`() {
        val action = AgentAction("press_enter", emptyMap())
        assertEquals("Press Enter", action.displayText)
    }

    @Test fun `displayText for scroll`() {
        val action = AgentAction("scroll", mapOf("direction" to "up"))
        assertEquals("Scroll up", action.displayText)
    }

    @Test fun `displayText for open_app`() {
        val action = AgentAction("open_app", mapOf("app_name" to "WhatsApp"))
        assertEquals("Open: WhatsApp", action.displayText)
    }

    @Test fun `displayText for done`() {
        val action = AgentAction("done", emptyMap())
        assertEquals("Done", action.displayText)
    }

    @Test fun `displayText for unknown action returns action name`() {
        val action = AgentAction("custom_action", emptyMap())
        assertEquals("custom_action", action.displayText)
    }

    @Test fun `SUPPORTED_ACTIONS contains 11 actions`() {
        assertEquals(11, AgentAction.SUPPORTED_ACTIONS.size)
    }

    @Test fun `SUPPORTED_ACTIONS does not contain run_adb_command`() {
        assertFalse(AgentAction.SUPPORTED_ACTIONS.contains("run_adb_command"))
    }

    @Test fun `SUPPORTED_ACTIONS contains all expected actions`() {
        val expected = listOf(
            "click_text", "click_at", "type_text", "press_enter",
            "scroll", "swipe", "press_back", "press_home",
            "open_app", "wait", "done"
        )
        for (action in expected) {
            assertTrue("Missing: $action", AgentAction.SUPPORTED_ACTIONS.contains(action))
        }
    }

    // ─── ActionStep tests ───

    @Test fun `ActionStep toJson and fromJson roundtrip`() {
        val step = ActionStep("click_text", mapOf("text" to "OK"))
        val json = step.toJson()
        val restored = ActionStep.fromJson(json)
        assertEquals(step.action, restored.action)
        assertEquals(step.params["text"], restored.params["text"])
    }

    // ─── SavedSkill tests ───

    @Test fun `SavedSkill isReliable with success and low fail rate`() {
        val skill = SavedSkill(
            id = "skill_1",
            task = "open whatsapp",
            taskKeywords = listOf("open", "whatsapp"),
            successCount = 5,
            failCount = 1,
            lastUsed = System.currentTimeMillis(),
            steps = listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp")))
        )
        assertTrue(skill.isReliable)
    }

    @Test fun `SavedSkill isReliable false with high fail rate`() {
        val skill = SavedSkill(
            id = "skill_2",
            task = "open settings",
            taskKeywords = listOf("open", "settings"),
            successCount = 2,
            failCount = 5,
            lastUsed = System.currentTimeMillis(),
            steps = emptyList()
        )
        assertFalse(skill.isReliable)
    }

    @Test fun `SavedSkill isReliable false with zero success`() {
        val skill = SavedSkill(
            id = "skill_3",
            task = "test task",
            taskKeywords = listOf("test"),
            successCount = 0,
            failCount = 1,
            lastUsed = System.currentTimeMillis(),
            steps = emptyList()
        )
        assertFalse(skill.isReliable)
    }

    @Test fun `SavedSkill isReliable true at 30 percent fail boundary`() {
        // failCount / (success + fail) = 3/10 = 0.3 — NOT < 0.3, so NOT reliable
        val skill = SavedSkill(
            id = "skill_4",
            task = "boundary test",
            taskKeywords = listOf("boundary"),
            successCount = 7,
            failCount = 3,
            lastUsed = System.currentTimeMillis(),
            steps = emptyList()
        )
        assertFalse(skill.isReliable) // 3/10 = 0.3, not < 0.3
    }

    @Test fun `SavedSkill isReliable true just below 30 percent`() {
        // failCount / (success + fail) = 2/10 = 0.2 — < 0.3, so reliable
        val skill = SavedSkill(
            id = "skill_5",
            task = "below boundary",
            taskKeywords = listOf("below"),
            successCount = 8,
            failCount = 2,
            lastUsed = System.currentTimeMillis(),
            steps = emptyList()
        )
        assertTrue(skill.isReliable)
    }

    @Test fun `SavedSkill toJson and fromJson roundtrip`() {
        val skill = SavedSkill(
            id = "skill_test",
            task = "open chrome",
            taskKeywords = listOf("open", "chrome"),
            successCount = 3,
            failCount = 1,
            lastUsed = 1234567890L,
            steps = listOf(
                ActionStep("open_app", mapOf("app_name" to "Chrome")),
                ActionStep("click_text", mapOf("text" to "Search"))
            )
        )
        val json = skill.toJson()
        val restored = SavedSkill.fromJson(json)
        assertNotNull(restored)
        assertEquals(skill.id, restored!!.id)
        assertEquals(skill.task, restored.task)
        assertEquals(skill.successCount, restored.successCount)
        assertEquals(skill.failCount, restored.failCount)
        assertEquals(skill.lastUsed, restored.lastUsed)
        assertEquals(skill.steps.size, restored.steps.size)
        assertEquals(skill.steps[0].action, restored.steps[0].action)
    }
}
