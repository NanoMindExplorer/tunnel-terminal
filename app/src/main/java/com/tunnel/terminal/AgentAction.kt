package com.tunnel.terminal

import org.json.JSONObject

/**
 * AgentAction — Represents a single action from the AI agent.
 *
 * Ported from private-agent's agent_action.dart + task_executor.dart action vocabulary.
 *
 * v9.5.0 Phase 1: Data class for the in-loop action protocol.
 * The LLM returns JSON like:
 * {"action":"click_text","params":{"text":"OK"},"reasoning":"...","is_complete":false}
 *
 * Security: run_adb_command is NOT in the available actions list.
 * Only safe UI actions are supported.
 */
data class AgentAction(
    val action: String,
    val params: Map<String, String>,
    val reasoning: String = "",
    val isComplete: Boolean = false
) {
    companion object {
        /** Parse from JSON string returned by LLM. */
        fun fromJson(jsonStr: String): AgentAction? {
            return try {
                val json = JSONObject(jsonStr)
                AgentAction(
                    action = json.optString("action", ""),
                    params = json.optJSONObject("params")?.let { paramsObj ->
                        val map = mutableMapOf<String, String>()
                        val keys = paramsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            map[key] = paramsObj.optString(key)
                        }
                        map
                    } ?: emptyMap(),
                    reasoning = json.optString("reasoning", ""),
                    isComplete = json.optBoolean("is_complete", false)
                )
            } catch (e: Exception) {
                null
            }
        }

        /** All supported actions in the task loop. */
        val SUPPORTED_ACTIONS = listOf(
            "click_text", "click_at", "type_text", "press_enter",
            "scroll", "swipe", "press_back", "press_home",
            "open_app", "wait", "done"
        )

        /**
         * Extract JSON from LLM response text.
         * Handles markdown code blocks and plain JSON.
         */
        fun extractJson(text: String): String {
            // 1. Try markdown code block ```json ... ```
            val codeBlockRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```")
            val match = codeBlockRegex.find(text)
            if (match != null) return match.groupValues[1]

            // 2. Fallback: first { to last }
            val startIndex = text.indexOf('{')
            val endIndex = text.lastIndexOf('}')
            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                return text.substring(startIndex, endIndex + 1)
            }
            return text.trim()
        }
    }

    /** Check if this action is safe (not in blocked list). */
    val isSafe: Boolean
        get() = action in SUPPORTED_ACTIONS

    /** Human-readable description for UI display. */
    val displayText: String
        get() = when (action) {
            "click_text" -> "Click: ${params["text"] ?: "?"}"
            "click_at" -> "Click at: (${params["x"] ?: "?"}, ${params["y"] ?: "?"})"
            "type_text" -> "Type: ${params["text"] ?: "?"}"
            "press_enter" -> "Press Enter"
            "scroll" -> "Scroll ${params["direction"] ?: "down"}"
            "swipe" -> "Swipe"
            "press_back" -> "Press Back"
            "press_home" -> "Press Home"
            "open_app" -> "Open: ${params["app_name"] ?: "?"}"
            "wait" -> "Wait"
            "done" -> "Done"
            else -> action
        }
}

/**
 * ActionStep — One step in a saved skill (for replay).
 */
data class ActionStep(
    val action: String,
    val params: Map<String, String>
) {
    fun toJson(): JSONObject {
        return JSONObject().put("action", action).put("params", JSONObject(params))
    }

    companion object {
        fun fromJson(json: JSONObject): ActionStep {
            val params = mutableMapOf<String, String>()
            val paramsObj = json.optJSONObject("params")
            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    params[key] = paramsObj.optString(key)
                }
            }
            return ActionStep(json.optString("action"), params)
        }
    }
}

/**
 * SavedSkill — A learned task sequence that can be replayed.
 *
 * Ported from private-agent's saved_skill.dart.
 * Uses Jaccard similarity matching (> 0.6 threshold) for finding similar tasks.
 * isReliable = successCount >= 1 && failRate < 30%.
 */
data class SavedSkill(
    val id: String,
    val task: String,
    val taskKeywords: List<String>,
    var successCount: Int,
    var failCount: Int,
    var lastUsed: Long,
    val steps: List<ActionStep>
) {
    /** Reliable if at least 1 success AND fail-rate < 30%. */
    val isReliable: Boolean
        get() = successCount >= 1 && failCount.toDouble() / (successCount + failCount) < 0.3

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("task", task)
            .put("task_keywords", org.json.JSONArray(taskKeywords))
            .put("success_count", successCount)
            .put("fail_count", failCount)
            .put("last_used", lastUsed)
            .put("steps", org.json.JSONArray(steps.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): SavedSkill? {
            return try {
                val keywords = mutableListOf<String>()
                val kwArr = json.optJSONArray("task_keywords")
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        keywords.add(kwArr.getString(i))
                    }
                }
                val steps = mutableListOf<ActionStep>()
                val stepsArr = json.optJSONArray("steps")
                if (stepsArr != null) {
                    for (i in 0 until stepsArr.length()) {
                        steps.add(ActionStep.fromJson(stepsArr.getJSONObject(i)))
                    }
                }
                SavedSkill(
                    id = json.getString("id"),
                    task = json.getString("task"),
                    taskKeywords = keywords,
                    successCount = json.optInt("success_count", 0),
                    failCount = json.optInt("fail_count", 0),
                    lastUsed = json.optLong("last_used", 0),
                    steps = steps
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
