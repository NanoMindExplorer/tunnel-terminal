package com.tunnel.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service so PTY / SSH / Ubuntu stay alive in the background.
 *
 * v9.5.8:
 *  - WAKE_LOCK (partial) so the CPU is not frozen under Doze
 *  - FGS type dataSync|specialUse — Android 15 caps dataSync at 6h
 *  - stopWithTask=false + restart after swipe-from-recents
 *  - START_STICKY
 */
class TerminalForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "TunnelTerminalChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.tunnel.terminal.STOP_SERVICE"
        const val ACTION_UPDATE_SESSIONS = "com.tunnel.terminal.UPDATE_SESSIONS"
        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_SESSION_LABELS = "session_labels"
        private const val TAG = "TerminalFgs"
        private const val WAKELOCK_TAG = "TunnelTerminal:Fgs"
    }

    @Volatile
    private var sessionCount: Int = 0
    @Volatile
    private var sessionLabels: List<String> = emptyList()

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        val notification = buildNotification()
        val types = foregroundTypes()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, types
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground typed failed, fallback: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground failed: ${e2.message}")
            }
        }
    }

    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return types
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "PARTIAL_WAKE_LOCK acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WAKE_LOCK gagal: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    fun updateSessions(count: Int, labels: List<String>) {
        sessionCount = count
        sessionLabels = labels.take(5)
        val notification = buildNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Terminal Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Menjaga sesi terminal tetap aktif di background"
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SESSIONS -> {
                val count = intent.getIntExtra(EXTRA_SESSION_COUNT, 0)
                val labels = intent.getStringArrayListExtra(EXTRA_SESSION_LABELS) ?: arrayListOf()
                updateSessions(count, labels)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        /* Swipe from recents must not kill the FGS — restart ourselves. */
        try {
            KeepAliveManager.startForegroundSafely(applicationContext)
            Log.i(TAG, "onTaskRemoved — FGS re-asserted")
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved restart: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
