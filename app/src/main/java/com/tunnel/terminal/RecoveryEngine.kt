package com.tunnel.terminal

import android.util.Log

/**
 * RecoveryEngine — Detects stuck patterns and suggests recovery actions.
 *
 * Ported from private-agent's recovery_engine.dart (71 lines).
 * Pure logic — no Android dependencies.
 *
 * Patterns detected:
 * - Loading spinner → wait
 * - Keyboard blocking → press back to dismiss
 * - Click failed + scrollable → scroll down
 * - Click failed + not scrollable → press back
 * - App open failed → press home
 * - Unknown failure → press back (safe default)
 */
class RecoveryEngine {

    companion object {
        private const val TAG = "RecoveryEngine"
    }

    data class RecoveryAction(
        val action: String,
        val params: Map<String, String> = emptyMap(),
        val description: String
    )

    /**
     * Diagnose the failure and suggest a recovery action.
     *
     * @param lastFailedAction The action that just failed (e.g. "click_text")
     * @param screenContent The current screen text dump
     * @return RecoveryAction with suggested next step
     */
    fun diagnose(lastFailedAction: String, screenContent: String): RecoveryAction {
        val lower = screenContent.lowercase()

        // 1. Loading/spinner → Wait
        if (lower.contains("loading") || lower.contains("progress") ||
            lower.contains("spinner") || lower.contains("wait")
        ) {
            Log.i(TAG, "Recovery: app loading → wait")
            return RecoveryAction(
                action = "wait",
                description = "App seems to be loading, waiting..."
            )
        }

        // 2. Keyboard blocking → Press back to dismiss
        if (lower.contains("gboard") || lower.contains("keyboard")) {
            Log.i(TAG, "Recovery: keyboard blocking → press back")
            return RecoveryAction(
                action = "press_back",
                description = "Keyboard might be blocking the screen, dismissing it."
            )
        }

        // 3. Click failed → scroll to find target, or press back if stuck
        if (lastFailedAction == "click_text" || lastFailedAction == "click_at") {
            return if (lower.contains("scrollable")) {
                Log.i(TAG, "Recovery: click failed + scrollable → scroll down")
                RecoveryAction(
                    action = "scroll",
                    params = mapOf("direction" to "down"),
                    description = "Click failed, trying to scroll down to find the target."
                )
            } else {
                Log.i(TAG, "Recovery: click failed + no scroll → press back")
                RecoveryAction(
                    action = "press_back",
                    description = "Click failed and not scrollable, pressing back to retry."
                )
            }
        }

        // 4. open_app failed → go home
        if (lastFailedAction == "open_app") {
            Log.i(TAG, "Recovery: app open failed → press home")
            return RecoveryAction(
                action = "press_home",
                description = "Failed to open app, going home to try a different approach."
            )
        }

        // 5. Generic fallback
        Log.i(TAG, "Recovery: unknown failure → press back")
        return RecoveryAction(
            action = "press_back",
            description = "Unknown failure, pressing back to recover."
        )
    }
}
