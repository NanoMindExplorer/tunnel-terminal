package com.tunnel.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * SkillMemoryStore — Save and replay learned task sequences.
 *
 * Ported from private-agent's skill_memory_service.dart (120 lines).
 *
 * Uses Jaccard similarity matching on task keywords to find similar tasks.
 * - findSkill: similarity > 0.6 AND skill.isReliable → return for replay
 * - saveSkill: similarity > 0.8 → update existing (increment success, replace if shorter)
 *              otherwise → create new skill
 * - recordFailure: increment failCount, affects isReliable ratio
 *
 * Storage: JSONL file at filesDir/agent_skills.jsonl
 * v9.5.0 Phase 1: Uses context.filesDir (private to app, no permission needed).
 */
class SkillMemoryStore(context: Context) {

    companion object {
        private const val TAG = "SkillMemoryStore"
        private const val FILE_NAME = "agent_skills.jsonl"
        /* Match comments / private-agent: replay only on strong Jaccard. */
        private const val SIMILARITY_THRESHOLD = 0.6
        private const val UPDATE_THRESHOLD = 0.8
        private val STOP_WORDS = setOf(
            "to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by", "from",
            "go", "turn", "open",
            "yang", "di", "ke", "dari", "untuk", "dan", "atau", "ini", "itu",
            "ada", "saya", "kamu", "dong", "lah", "pun", "ya"
        )
    }

    enum class MatchKind { FULL, PREFIX }

    data class SkillMatch(val skill: SavedSkill, val kind: MatchKind)

    private val skillsFile = File(context.filesDir, FILE_NAME)
    private val skills = mutableListOf<SavedSkill>()
    @Volatile
    private var loaded = false

    /** Lazy-load skills from disk. */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!skillsFile.exists()) return
            skillsFile.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val json = org.json.JSONObject(line)
                    SavedSkill.fromJson(json)?.let { skills.add(it) }
                } catch (_: Exception) {}
            }
            Log.i(TAG, "Loaded ${skills.size} skills from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load skills: ${e.message}")
        }
    }

    /**
     * Find a reliable skill matching the task goal.
     * @return SavedSkill if found (Jaccard > 0.6 AND isReliable), null otherwise.
     */
    fun findSkill(taskGoal: String): SavedSkill? =
        findSkillMatch(taskGoal)?.takeIf { it.kind == MatchKind.FULL }?.skill

    /**
     * FULL: goals are about the same size (Jaccard > 0.6, query not much longer)
     *   → safe to replay and mark the task complete.
     * PREFIX: saved keywords are a subset of the new goal → replay as a start,
     *   then continue the AI loop (do not mark complete).
     */
    fun findSkillMatch(taskGoal: String): SkillMatch? {
        ensureLoaded()
        val query = extractKeywords(taskGoal).toSet()
        if (query.isEmpty()) return null

        var bestFull: Pair<SavedSkill, Double>? = null
        var bestPrefix: Pair<SavedSkill, Int>? = null

        for (skill in skills) {
            if (!skill.isReliable) continue
            val saved = skill.taskKeywords.toSet()
            if (saved.isEmpty()) continue
            val inter = query.intersect(saved).size
            val union = query.union(saved).size
            if (union == 0) continue
            val jaccard = inter.toDouble() / union
            val subset = saved.all { it in query }

            if (jaccard > SIMILARITY_THRESHOLD && query.size <= saved.size + 1) {
                if (bestFull == null || jaccard > bestFull.second) {
                    bestFull = skill to jaccard
                }
            } else if (subset && query.size > saved.size) {
                if (bestPrefix == null || saved.size > bestPrefix.second) {
                    bestPrefix = skill to saved.size
                }
            }
        }

        bestFull?.let {
            Log.i(TAG, "Full skill match: '${it.first.task}' (jaccard=${it.second})")
            return SkillMatch(it.first, MatchKind.FULL)
        }
        bestPrefix?.let {
            Log.i(TAG, "Prefix skill match: '${it.first.task}' (saved=${it.second} keywords)")
            return SkillMatch(it.first, MatchKind.PREFIX)
        }
        return null
    }

    /**
     * Save a successfully completed task as a skill.
     * If similar skill exists (Jaccard > 0.8): update success count + steps if shorter.
     * Otherwise: create new skill.
     */
    fun saveSkill(taskGoal: String, steps: List<ActionStep>) {
        ensureLoaded()
        val keywords = extractKeywords(taskGoal)

        // Check if similar skill exists
        var existingSkill: SavedSkill? = null
        var bestScore = 0.0
        for (skill in skills) {
            val score = jaccardSimilarity(keywords, skill.taskKeywords)
            if (score > bestScore) {
                bestScore = score
                existingSkill = skill
            }
        }

        if (bestScore > UPDATE_THRESHOLD && existingSkill != null) {
            // Update existing
            existingSkill.successCount++
            existingSkill.lastUsed = System.currentTimeMillis()
            /* Never shrink a taught sequence — keep the more complete lesson. */
            if (steps.size > existingSkill.steps.size) {
                Log.i(TAG, "Updating skill '${existingSkill.task}' with longer taught sequence (${steps.size} > ${existingSkill.steps.size})")
                val updated = SavedSkill(
                    id = existingSkill.id,
                    task = existingSkill.task,
                    taskKeywords = existingSkill.taskKeywords,
                    successCount = existingSkill.successCount,
                    failCount = existingSkill.failCount,
                    lastUsed = existingSkill.lastUsed,
                    steps = steps
                )
                skills.remove(existingSkill)
                skills.add(updated)
            } else {
                Log.i(TAG, "Updating skill '${existingSkill.task}' success count (${existingSkill.successCount})")
            }
        } else {
            // Create new skill
            val newSkill = SavedSkill(
                id = "skill_${System.currentTimeMillis()}",
                task = taskGoal,
                taskKeywords = keywords,
                successCount = 1,
                failCount = 0,
                lastUsed = System.currentTimeMillis(),
                steps = steps
            )
            skills.add(newSkill)
            Log.i(TAG, "Saved new skill: '$taskGoal' (${steps.size} steps)")
        }

        persist()
    }

    /** Record a failure for a skill (affects isReliable ratio). */
    fun recordFailure(skillId: String) {
        ensureLoaded()
        val skill = skills.find { it.id == skillId }
        if (skill != null) {
            skill.failCount++
            Log.i(TAG, "Recorded failure for skill '${skill.task}' (fails=${skill.failCount})")
            persist()
        }
    }

    /** Get all skills (for UI display). */
    fun getAllSkills(): List<SavedSkill> {
        ensureLoaded()
        return skills.toList()
    }

    /** Clear all skills. */
    fun clearAll() {
        ensureLoaded()
        skills.clear()
        loaded = true
        try { skillsFile.delete() } catch (_: Exception) {}
        Log.i(TAG, "All skills cleared")
    }

    // ─── Private helpers ───

    private fun extractKeywords(text: String): List<String> {
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .split(Regex("\\s+"))
        return words.filter { it.isNotEmpty() && it !in STOP_WORDS }
    }

    private fun jaccardSimilarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        if (union == 0) return 0.0
        /* Pure Jaccard. A containment boost made "open whatsapp" match
         * "open whatsapp and send message" at 1.0, so replay opened the
         * app and marked the whole task complete. */
        return intersection.toDouble() / union
    }

    private fun persist() {
        try {
            val tmp = File(skillsFile.absolutePath + ".tmp")
            tmp.bufferedWriter().use { writer ->
                skills.forEach { skill ->
                    writer.write(skill.toJson().toString())
                    writer.newLine()
                }
            }
            if (!tmp.renameTo(skillsFile)) {
                tmp.copyTo(skillsFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist skills: ${e.message}")
        }
    }
}
