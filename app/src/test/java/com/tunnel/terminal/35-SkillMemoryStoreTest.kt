package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.Context

/**
 * v9.5.4 Phase 5: Unit tests for SkillMemoryStore.
 * Tests: findSkill (Jaccard matching), saveSkill, recordFailure, getAllSkills.
 *
 * Uses ContextWrapper mock (same pattern as PermissionManagerTest) because
 * SkillMemoryStore constructor needs Android Context for filesDir.
 */
@RunWith(RobolectricTestRunner::class)
class SkillMemoryStoreTest {

    private lateinit var store: SkillMemoryStore
    private lateinit var context: Context

    @Before
    fun setup() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        store = SkillMemoryStore(context)
    }

    @Test fun `findSkill returns null for empty store`() {
        val result = store.findSkill("open whatsapp")
        assertNull(result)
    }

    @Test fun `saveSkill creates new skill`() {
        val steps = listOf(
            ActionStep("open_app", mapOf("app_name" to "WhatsApp")),
            ActionStep("click_text", mapOf("text" to "Send"))
        )
        store.saveSkill("open whatsapp and send message", steps)
        val skills = store.getAllSkills()
        assertEquals(1, skills.size)
        assertTrue(skills[0].task.contains("whatsapp"))
        assertEquals(1, skills[0].successCount)
        assertEquals(0, skills[0].failCount)
        assertTrue(skills[0].isReliable)
    }

    @Test fun `findSkill matches by keyword similarity`() {
        val steps = listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp")))
        store.saveSkill("open whatsapp", steps)

        /* Strong overlap (not a loosely related longer goal). */
        val result = store.findSkill("open whatsapp")
        assertNotNull(result)
        assertTrue(result!!.isReliable)
    }

    @Test fun `findSkill returns null for dissimilar task`() {
        val steps = listOf(ActionStep("open_app", mapOf("app_name" to "Chrome")))
        store.saveSkill("open chrome browser", steps)

        val result = store.findSkill("set alarm for 7am")
        assertNull(result)
    }

    @Test fun `saveSkill updates existing for similar task`() {
        val steps1 = listOf(
            ActionStep("open_app", mapOf("app_name" to "WhatsApp")),
            ActionStep("click_text", mapOf("text" to "Contact")),
            ActionStep("type_text", mapOf("text" to "hello")),
            ActionStep("click_text", mapOf("text" to "Send"))
        )
        store.saveSkill("open whatsapp send hello to contact", steps1)

        /* Near-duplicate wording so Jaccard stays above UPDATE_THRESHOLD (0.8). */
        val steps2 = listOf(
            ActionStep("open_app", mapOf("app_name" to "WhatsApp"))
        )
        store.saveSkill("open whatsapp send hello to contact", steps2)

        val skills = store.getAllSkills()
        assertEquals(1, skills.size)  // Not 2 — should update existing
        assertEquals(2, skills[0].successCount)  // Incremented
    }

    @Test fun `recordFailure increments fail count`() {
        val steps = listOf(ActionStep("open_app", mapOf("app_name" to "Settings")))
        store.saveSkill("open settings", steps)

        val skills = store.getAllSkills()
        val skillId = skills[0].id

        store.recordFailure(skillId)

        val updated = store.getAllSkills()[0]
        assertEquals(1, updated.failCount)
    }

    @Test fun `isReliable becomes false after enough failures`() {
        val steps = listOf(ActionStep("open_app", mapOf("app_name" to "Camera")))
        store.saveSkill("open camera", steps)

        val skills = store.getAllSkills()
        val skillId = skills[0].id

        // Record 3 failures (success=1, fail=3 → 3/4=0.75 > 0.3)
        store.recordFailure(skillId)
        store.recordFailure(skillId)
        store.recordFailure(skillId)

        val updated = store.getAllSkills()[0]
        assertFalse(updated.isReliable)
    }

    @Test fun `clearAll removes all skills`() {
        store.saveSkill("task 1", listOf(ActionStep("wait", emptyMap())))
        store.saveSkill("task 2", listOf(ActionStep("wait", emptyMap())))
        assertEquals(2, store.getAllSkills().size)

        store.clearAll()
        assertEquals(0, store.getAllSkills().size)
    }

    @Test fun `findSkill returns null for empty goal`() {
        store.saveSkill("open whatsapp", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        val result = store.findSkill("")
        assertNull(result)
    }

    @Test fun `findSkill returns null for single stopword goal`() {
        store.saveSkill("open whatsapp", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        val result = store.findSkill("the")
        assertNull(result)
    }

    @Test fun `saveSkill with empty steps still saves`() {
        store.saveSkill("empty task", emptyList())
        assertEquals(1, store.getAllSkills().size)
    }

    @Test fun `multiple different skills can be saved`() {
        store.saveSkill("open whatsapp", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        store.saveSkill("open chrome", listOf(ActionStep("open_app", mapOf("app_name" to "Chrome"))))
        store.saveSkill("set alarm", listOf(ActionStep("open_app", mapOf("app_name" to "Clock"))))

        assertEquals(3, store.getAllSkills().size)
    }

    @Test fun `findSkill returns best match when multiple exist`() {
        store.saveSkill("open whatsapp", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        store.saveSkill("open chrome", listOf(ActionStep("open_app", mapOf("app_name" to "Chrome"))))

        val result = store.findSkill("open whatsapp")
        assertNotNull(result)
        assertTrue(result!!.task.contains("whatsapp"))
        assertFalse(result.task.contains("chrome"))
    }

    @Test fun `findSkill does not treat a longer compound goal as the short skill`() {
        store.saveSkill("open whatsapp", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        /* "open" is a stop word; Jaccard([whatsapp],[whatsapp,send,message]) = 1/3. */
        assertNull(store.findSkill("open whatsapp and send message"))
    }

    @Test fun `findSkill keeps non-ascii keywords`() {
        val steps = listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp")))
        store.saveSkill("buka whatsapp kirim pesan", steps)
        val result = store.findSkill("buka whatsapp kirim pesan")
        assertNotNull(result)
    }

    @Test fun `longer taught sequence is kept not shrunk`() {
        val longSteps = listOf(
            ActionStep("open_app", mapOf("app_name" to "WhatsApp")),
            ActionStep("click_text", mapOf("text" to "Budi")),
            ActionStep("type_text", mapOf("text" to "halo")),
            ActionStep("click_text", mapOf("text" to "Kirim"))
        )
        store.saveSkill("buka whatsapp kirim halo ke budi", longSteps)
        store.saveSkill("buka whatsapp kirim halo ke budi", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        val skills = store.getAllSkills()
        assertEquals(1, skills.size)
        assertEquals(4, skills[0].steps.size)
        assertEquals(2, skills[0].successCount)
    }

    @Test fun `findSkillMatch uses prefix for a longer follow-up goal`() {
        store.saveSkill("buka whatsapp", listOf(ActionStep("open_app", mapOf("app_name" to "WhatsApp"))))
        val match = store.findSkillMatch("buka whatsapp kirim pesan ke budi")
        assertNotNull(match)
        assertEquals(SkillMemoryStore.MatchKind.PREFIX, match!!.kind)
        assertTrue(match.skill.task.contains("whatsapp"))
    }
}
