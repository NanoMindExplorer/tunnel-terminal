package com.tunnel.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * TerminalForegroundService - Foreground service agar shell tetap hidup
 * saat app di-background (anti-kill oleh OS).
 *
 * Phase 17:
 * - Notification dengan action "Stop" agar user bisa stop service manual
 * - PendingIntent ke MainActivity agar notifikasi bisa dibuka
 * - Type `dataSync` untuk Android < 14, `specialUse` opsional untuk 14+
 *
 * Foreground service keeping shell alive when app is backgrounded.
 */
class TerminalForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "TunnelTerminalChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.tunnel.terminal.STOP_SERVICE"
        const val ACTION_UPDATE_SESSIONS = "com.tunnel.terminal.UPDATE_SESSIONS"
        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_SESSION_LABELS = "session_labels"
    }

    /* v8.6.0 fix (M9): Track session labels untuk per-session notification.
     * Sebelumnya: notification text static "Sesi terminal berjalan di latar belakang".
     * Sekarang: "3 sessions: local, SSH user@host, Ubuntu" — user tahu apa yang aktif. */
    @Volatile
    private var sessionCount: Int = 0
    @Volatile
    private var sessionLabels: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        /* C2 fix: Gunakan startForeground dengan FOREGROUND_SERVICE_TYPE_DATA_SYNC
         * untuk Android 14+ (targetSdk=34). Tanpa type → crash di beberapa OEM. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TerminalForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /* v8.6.0 fix (M9): Per-session notification text.
         * v9.3.0 fix (H-8): Use sessionCount (actual) not sessionLabels.size (capped).
         * Show "+N more" when labels truncated. */
        val contentText = if (sessionCount > 0) {
            val shown = sessionLabels.joinToString(", ")
            if (sessionCount == 1) {
                "1 session: $shown"
            } else if (sessionLabels.size < sessionCount) {
                val extra = sessionCount - sessionLabels.size
                "$sessionCount sessions: $shown, +$extra more"
            } else {
                "$sessionCount sessions: $shown"
            }
        } else {
            "Sesi terminal berjalan di latar belakang"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tunnel Terminal Aktif")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    /** v8.6.0 fix (M9): Update notification dengan session labels terbaru. */
    fun updateSessions(count: Int, labels: List<String>) {
        sessionCount = count
        sessionLabels = labels.take(5)  // cap di 5 supaya notifikasi tidak terlalu panjang
        val notification = buildNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Terminal Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga sesi terminal tetap aktif di background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SESSIONS -> {
                /* v8.6.0 fix (M9): Update notification dengan session info terbaru. */
                val count = intent.getIntExtra(EXTRA_SESSION_COUNT, 0)
                val labels = intent.getStringArrayListExtra(EXTRA_SESSION_LABELS) ?: arrayListOf()
                updateSessions(count, labels)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        /* Saat user swipe app dari recents, jangan auto-destroy service
         * karena shell mungkin masih berjalan.
         * On swipe from recents, don't auto-destroy (shell may be running). */
        super.onTaskRemoved(rootIntent)
    }
}
