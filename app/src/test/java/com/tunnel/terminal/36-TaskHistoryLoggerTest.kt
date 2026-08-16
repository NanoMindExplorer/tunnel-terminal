package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.Context

/**
 * v9.5.4 Phase 5: Unit tests for TaskHistoryLogger.
 * Tests: logTask, readHistory, getAnalytics, clearHistory.
 *
 * Uses Robolectric for Android Context (filesDir).
 */
@RunWith(RobolectricTestRunner::class)
class TaskHistoryLoggerTest {

    private lateinit var logger: TaskHistoryLogger
    private lateinit var context: Context

    @Before
    fun setup() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        logger = TaskHistoryLogger(context)
        logger.clearHistory()  // Start clean
    }

    @Test fun `readHistory returns empty for new logger`() {
        val history = logger.readHistory()
        assertTrue(history.isEmpty())
    }

    @Test fun `logTask creates one record`() {
        logger.logTask("open whatsapp", "Success", 500, 3, listOf("step 1", "step 2", "step 3"))
        val history = logger.readHistory()
        assertEquals(1, history.size)
        assertEquals("open whatsapp", history[0].goal)
        assertEquals("Success", history[0].status)
        assertEquals(500, history[0].totalTokens)
        assertEquals(3, history[0].stepsTaken)
        assertEquals(3, history[0].trace.size)
    }

    @Test fun `readHistory returns newest first`() {
        logger.logTask("task 1", "Success", 100, 1, listOf("a"))
        Thread.sleep(10)
        logger.logTask("task 2", "Failed", 200, 2, listOf("b"))
        Thread.sleep(10)
        logger.logTask("task 3", "Success", 300, 3, listOf("c"))

        val history = logger.readHistory()
        assertEquals(3, history.size)
        assertEquals("task 3", history[0].goal)  // Newest first
        assertEquals("task 2", history[1].goal)
        assertEquals("task 1", history[2].goal)
    }

    @Test fun `getAnalytics returns correct counts`() {
        logger.logTask("task 1", "Success", 100, 1, listOf("a"))
        logger.logTask("task 2", "Success", 200, 2, listOf("b"))
        logger.logTask("task 3", "Failed", 300, 3, listOf("c"))
        logger.logTask("task 4", "Cancelled", 400, 4, listOf("d"))

        val analytics = logger.getAnalytics()
        assertEquals(4, analytics["total"])
        assertEquals(2, analytics["success"])
        assertEquals(1, analytics["failed"])
        assertEquals(1, analytics["cancelled"])
        assertEquals(50, analytics["successRate"])  // 2/4 = 50%
    }

    @Test fun `getAnalytics empty returns zeros`() {
        val analytics = logger.getAnalytics()
        assertEquals(0, analytics["total"])
        assertEquals(0, analytics["success"])
        assertEquals(0, analytics["failed"])
        assertEquals(0, analytics["successRate"])
    }

    @Test fun `getAnalytics 100 percent success rate`() {
        logger.logTask("task 1", "Success", 100, 1, listOf("a"))
        logger.logTask("task 2", "Success", 200, 2, listOf("b"))

        val analytics = logger.getAnalytics()
        assertEquals(2, analytics["total"])
        assertEquals(2, analytics["success"])
        assertEquals(100, analytics["successRate"])
    }

    @Test fun `getAnalytics 0 percent success rate`() {
        logger.logTask("task 1", "Failed", 100, 1, listOf("a"))
        logger.logTask("task 2", "Cancelled", 200, 2, listOf("b"))

        val analytics = logger.getAnalytics()
        assertEquals(2, analytics["total"])
        assertEquals(0, analytics["success"])
        assertEquals(0, analytics["successRate"])
    }

    @Test fun `clearHistory removes all records`() {
        logger.logTask("task 1", "Success", 100, 1, listOf("a"))
        logger.logTask("task 2", "Failed", 200, 2, listOf("b"))
        assertEquals(2, logger.readHistory().size)

        logger.clearHistory()
        assertEquals(0, logger.readHistory().size)
    }

    @Test fun `logTask preserves trace order`() {
        val trace = listOf("step 1: open", "step 2: click", "step 3: type", "step 4: done")
        logger.logTask("test task", "Success", 500, 4, trace)

        val history = logger.readHistory()
        assertEquals(4, history[0].trace.size)
        assertEquals("step 1: open", history[0].trace[0])
        assertEquals("step 4: done", history[0].trace[3])
    }

    @Test fun `logTask trims goal whitespace`() {
        logger.logTask("  open whatsapp  ", "Success", 100, 1, listOf("a"))
        val history = logger.readHistory()
        assertEquals("open whatsapp", history[0].goal)
    }

    @Test fun `logTask handles empty trace`() {
        logger.logTask("empty trace task", "Success", 0, 0, emptyList())
        val history = logger.readHistory()
        assertEquals(0, history[0].trace.size)
    }

    @Test fun `TaskRecord data class has all fields`() {
        val record = TaskHistoryLogger.TaskRecord(
            goal = "test",
            status = "Success",
            totalTokens = 100,
            stepsTaken = 5,
            trace = listOf("a", "b"),
            timestamp = 1234567890L
        )
        assertEquals("test", record.goal)
        assertEquals("Success", record.status)
        assertEquals(100, record.totalTokens)
        assertEquals(5, record.stepsTaken)
        assertEquals(2, record.trace.size)
        assertEquals(1234567890L, record.timestamp)
    }
}
