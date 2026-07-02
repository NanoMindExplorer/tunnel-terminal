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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tunnel Terminal Aktif")
            .setContentText("Sesi terminal berjalan di latar belakang")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
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
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
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
