package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentWorkflow - Multi-step AI agent sequence yang bisa save + replay.
 *
 * Phase 23: Agent Workflows (save/replay multi-step AI agent sequences).
 * User bisa define workflow seperti:
 *   1. Ask AI: "Analisa error di terminal"
 *   2. Auto-run AI suggested commands
 *   3. Ask AI: "Fix masalah berdasarkan output"
 *   4. Auto-apply fix
 *
 * Workflow di-save ke SharedPreferences, bisa di-replay dengan satu klik.
 *
 * Agent workflows — save/replay multi-step AI sequences.
 */
data class AgentStep(
    val type: StepType,
    val prompt: String = "",           // untuk AI_STEP
    val command: String = "",          // untuk COMMAND_STEP
    val waitForOutput: Boolean = true,  // tunggu output sebelum next step
    val timeoutMs: Long = 15000
) {
    enum class StepType { AI_STEP, COMMAND_STEP, DELAY_STEP, CONDITIONAL_STEP }

    val displayText: String get() = when (type) {
        StepType.AI_STEP -> "🤖 AI: $prompt"
        StepType.COMMAND_STEP -> "▶ cmd: $command"
        StepType.DELAY_STEP -> "⏳ delay ${timeoutMs}ms"
        StepType.CONDITIONAL_STEP -> "❓ if output contains \"$prompt\" then run \"$command\""
    }
}

data class AgentWorkflow(
    val id: Long,
    val name: String,
    val description: String = "",
    val steps: List<AgentStep>,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * AgentWorkflowManager - Persist + manage agent workflows.
 */
class AgentWorkflowManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelAgentWorkflows", Context.MODE_PRIVATE)
    private val _workflows = mutableStateListOf<AgentWorkflow>()
    val workflows: List<AgentWorkflow> get() = _workflows.toList()

    init {
        load()
    }

    private fun load() {
        val json = prefs.getString("workflows", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
                val steps = mutableListOf<AgentStep>()
                for (j in 0 until stepsArr.length()) {
                    val s = stepsArr.getJSONObject(j)
                    val typeStr = s.optString("type", "AI_STEP")
                    val type = runCatching { AgentStep.StepType.valueOf(typeStr) }.getOrDefault(AgentStep.StepType.AI_STEP)
                    steps.add(AgentStep(
                        type = type,
                        prompt = s.optString("prompt", ""),
                        command = s.optString("command", ""),
                        waitForOutput = s.optBoolean("waitForOutput", true),
                        timeoutMs = s.optLong("timeoutMs", 15000)
                    ))
                }
                _workflows.add(AgentWorkflow(
                    id = obj.optLong("id", System.currentTimeMillis() + i),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    steps = steps,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                ))
            }
            _workflows.sortByDescending { it.createdAt }
        } catch (_: Exception) {
            _workflows.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        _workflows.forEach { w ->
            val stepsArr = JSONArray()
            w.steps.forEach { s ->
                stepsArr.put(JSONObject()
                    .put("type", s.type.name)
                    .put("prompt", s.prompt)
                    .put("command", s.command)
                    .put("waitForOutput", s.waitForOutput)
                    .put("timeoutMs", s.timeoutMs)
                )
            }
            arr.put(JSONObject()
                .put("id", w.id)
                .put("name", w.name)
                .put("description", w.description)
                .put("steps", stepsArr)
                .put("createdAt", w.createdAt)
            )
        }
        prefs.edit().putString("workflows", arr.toString()).apply()
    }

    fun addWorkflow(name: String, description: String, steps: List<AgentStep>): Boolean {
        if (name.isBlank()) return false
        if (_workflows.size >= 50) return false
        val workflow = AgentWorkflow(
            id = System.currentTimeMillis(),
            name = name.trim(),
            description = description,
            steps = steps
        )
        _workflows.add(workflow)
        _workflows.sortByDescending { it.createdAt }
        save()
        return true
    }

    fun removeWorkflow(id: Long): Boolean {
        val removed = _workflows.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    fun getWorkflow(id: Long): AgentWorkflow? = _workflows.firstOrNull { it.id == id }
}
