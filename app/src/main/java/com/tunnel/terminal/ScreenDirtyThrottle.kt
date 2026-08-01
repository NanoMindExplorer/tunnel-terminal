package com.tunnel.terminal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScreenDirtyThrottle — Shared throttle logic untuk screen update notifications (v8.6.0 fix M1).
 *
 * Sebelumnya: throttle logic (~33 lines) diduplikasi verbatim antara PtySessionBase
 * dan SshShellExecutor. Keduanya punya copy yang sama:
 *   - lastScreenDirtyTime: Long
 *   - pendingTrailingDirty: AtomicBoolean
 *   - dirtyHandler: Handler
 *   - trailingDirtyRunnable: Runnable
 *   - triggerScreenUpdate() dengan 33ms throttle + trailing edge
 *
 * Fix: Extract ke class terpisah. DRY — single implementation, inject ke both.
 *
 * Throttle strategy:
 * - If elapsed >= 33ms since last trigger → fire immediately
 * - If elapsed < 33ms → schedule trailing edge fire at 33ms mark
 * - Guarantees max ~30fps update rate + no missed final update
 */
class ScreenDirtyThrottle(
    private val onDirty: () -> Any,  // v9.0.0 fix: accept Any return (e.g. Int from value++)
    private val throttleMs: Long = 33L
) {
    @Volatile
    private var lastTriggerTime: Long = 0L

    private val pendingTrailing = AtomicBoolean(false)

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val trailingRunnable = Runnable {
        if (pendingTrailing.compareAndSet(true, false)) {
            lastTriggerTime = System.currentTimeMillis()
            onDirty()
        }
    }

    /**
     * Trigger a screen update. If throttle window has passed, fire immediately.
     * Otherwise, schedule a trailing-edge fire to catch the last update.
     */
    fun trigger() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTriggerTime
        if (elapsed >= throttleMs) {
            pendingTrailing.set(false)
            handler.removeCallbacks(trailingRunnable)
            lastTriggerTime = now
            onDirty()
        } else {
            pendingTrailing.set(true)
            handler.removeCallbacks(trailingRunnable)
            handler.postDelayed(trailingRunnable, (throttleMs - elapsed).coerceAtLeast(1))
        }
    }

    /** Cancel any pending trailing updates (call on destroy). */
    fun cancel() {
        pendingTrailing.set(false)
        handler.removeCallbacks(trailingRunnable)
    }
}
