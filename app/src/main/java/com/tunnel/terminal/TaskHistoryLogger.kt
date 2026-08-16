package com.tunnel.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * TaskHistoryLogger — Append-only JSONL logger for agent task results.
 *
 * Ported from private-agent's task_history_logger.dart (93 lines).
 *
 * Each task attempt logs: goal, status, totalTokens, steps, trace, timestamp.
 * Stored at filesDir/agent_task_history.jsonl (private to app).
 *
 * v9.5.0 Phase 1: Uses context.filesDir (no permission needed).
 */
class TaskHistoryLogger(context: Context) {

    companion object {
        private const val TAG = "TaskHistoryLogger"
        private const val FILE_NAME = "agent_task_history.jsonl"
    }

    private val historyFile = File(context.filesDir, FILE_NAME)

    data class TaskRecord(
        val goal: String,
        val status: String,  // "Success" | "Failed" | "Cancelled"
        val totalTokens: Int,
        val stepsTaken: Int,
        val trace: List<String>,
        val timestamp: Long
    )

    /** Append a task record to the history file. */
    fun logTask(goal: String, status: String, totalTokens: Int, steps: Int, trace: List<String>) {
        try {
            val json = org.json.JSONObject()
                .put("goal", goal.trim())
                .put("status", status)
                .put("total_tokens", totalTokens)
                .put("steps_taken", steps)
                .put("trace", org.json.JSONArray(trace))
                .put("timestamp", System.currentTimeMillis())

            historyFile.appendText(json.toString() + System.lineSeparator())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log task: ${e.message}")
        }
    }

    /** Read all history records (newest first). */
    fun readHistory(): List<TaskRecord> {
        if (!historyFile.exists()) return emptyList()
        val records = mutableListOf<TaskRecord>()
        try {
            historyFile.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val json = org.json.JSONObject(line)
                    val trace = mutableListOf<String>()
                    val traceArr = json.optJSONArray("trace")
                    if (traceArr != null) {
                        for (i in 0 until traceArr.length()) {
                            trace.add(traceArr.getString(i))
                        }
                    }
                    records.add(TaskRecord(
                        goal = json.optString("goal"),
                        status = json.optString("status"),
                        totalTokens = json.optInt("total_tokens"),
                        stepsTaken = json.optInt("steps_taken"),
                        trace = trace,
                        timestamp = json.optLong("timestamp")
                    ))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read history: ${e.message}")
        }
        return records.reversed() // newest first
    }

    /** Get analytics summary. */
    fun getAnalytics(): Map<String, Int> {
        val history = readHistory()
        val total = history.size
        val success = history.count { it.status == "Success" }
        val failed = history.count { it.status == "Failed" }
        val cancelled = history.count { it.status == "Cancelled" }
        val successRate = if (total > 0) (success * 100 / total) else 0
        return mapOf(
            "total" to total,
            "success" to success,
            "failed" to failed,
            "cancelled" to cancelled,
            "successRate" to successRate
        )
    }

    /** Clear all history. */
    fun clearHistory() {
        try { historyFile.delete() } catch (_: Exception) {}
        Log.i(TAG, "Task history cleared")
    }
}
