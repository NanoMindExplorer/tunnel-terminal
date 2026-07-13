package com.tunnel.terminal

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wave-16: Shared terminal font zoom math (pinch + step buttons).
 *
 * Fixes:
 * - Stable 0.5sp snap so size does not thrash mid-gesture
 * - Clamped range suitable for phone screens
 * - Discrete A+/A- steps for ExtraKeys / palette
 */
object TerminalFontZoom {
    const val MIN_SP = 8f
    const val MAX_SP = 28f
    const val DEFAULT_SP = 12f
    /** Minimum change applied during pinch (avoid micro-jitter). */
    const val MIN_DELTA_SP = 0.15f
    const val STEP_SP = 1f

    fun clamp(sp: Float): Float = sp.coerceIn(MIN_SP, MAX_SP)

    /** Snap to nearest 0.5sp for stable UI + PTY col/row math. */
    fun snap(sp: Float): Float {
        val clamped = clamp(sp)
        return (clamped * 2f).roundToInt() / 2f
    }

    /**
     * Apply one pinch frame. [currentSp] should be the live gesture-local size
     * (updated every frame), not a stale composition capture.
     * [zoom] is the per-frame scale factor from detectTransformGestures (~0.95–1.05).
     */
    fun applyPinch(currentSp: Float, zoom: Float): Float {
        if (zoom.isNaN() || zoom <= 0f) return snap(currentSp)
        /* Mild damping so aggressive pinches don't jump 8sp in one frame. */
        val damped = when {
            zoom > 1.08f -> 1f + (zoom - 1f) * 0.65f
            zoom < 0.92f -> 1f - (1f - zoom) * 0.65f
            else -> zoom
        }
        val raw = currentSp * damped
        val next = snap(raw)
        return if (abs(next - currentSp) < MIN_DELTA_SP && next != clamp(currentSp)) {
            /* Allow reaching exact min/max even for tiny last steps. */
            if (raw <= MIN_SP) MIN_SP else if (raw >= MAX_SP) MAX_SP else currentSp
        } else if (abs(next - currentSp) < MIN_DELTA_SP) {
            currentSp
        } else {
            next
        }
    }

    fun step(currentSp: Float, direction: Int): Float {
        val dir = if (direction >= 0) 1 else -1
        return snap(currentSp + dir * STEP_SP)
    }

    fun defaultForDensity(density: Float): Float {
        val d = density.takeIf { it > 0f } ?: 2f
        return snap(
            when {
                d >= 3f -> 13f
                d >= 2.5f -> 12.5f
                else -> DEFAULT_SP
            }
        )
    }

    fun formatLabel(sp: Float): String {
        val s = snap(sp)
        return if (s == s.toInt().toFloat()) "${s.toInt()}sp" else "${s}sp"
    }
}
