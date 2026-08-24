package com.tunnel.terminal

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * v9.5.8: Runtime keep-alive — FGS, battery exemption, OEM autostart, status.
 */
object KeepAliveManager {
    private const val TAG = "KeepAlive"

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAccessibilityEnabled(context: Context): Boolean =
        AgentAccessibilityService.isRunning()

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    fun startForegroundSafely(context: Context) {
        try {
            val intent = Intent(context, TerminalForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService gagal: ${e.message}")
        }
    }

    fun batteryExemptionIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun notificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun resolvedOemAutostartIntent(context: Context): Intent? {
        val pm = context.packageManager
        for (target in KeepAlivePolicy.OEM_AUTOSTART_TARGETS) {
            val intent = Intent().setComponent(
                ComponentName(target.packageName, target.activity)
            )
            if (intent.resolveActivity(pm) != null) return intent
        }
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun requestBatteryExemption(activity: Activity) {
        if (isIgnoringBatteryOptimizations(activity)) return
        try {
            activity.startActivity(batteryExemptionIntent(activity))
            activity.getSharedPreferences(KeepAlivePolicy.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KeepAlivePolicy.KEY_BATTERY_ASKED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "battery exemption intent gagal: ${e.message}")
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    fun requestOemAutostart(activity: Activity) {
        val intent = resolvedOemAutostartIntent(activity) ?: return
        try {
            activity.startActivity(intent)
            activity.getSharedPreferences(KeepAlivePolicy.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KeepAlivePolicy.KEY_OEM_ASKED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "OEM autostart intent gagal: ${e.message}")
        }
    }

    fun maybePromptBatteryOnce(activity: Activity) {
        if (isIgnoringBatteryOptimizations(activity)) return
        val prefs = activity.getSharedPreferences(KeepAlivePolicy.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KeepAlivePolicy.KEY_BATTERY_ASKED, false)) return
        requestBatteryExemption(activity)
    }

    fun handleCommand(
        cmd: String,
        activity: Activity,
        print: (String) -> Unit,
        requestNotifications: () -> Unit
    ): Boolean {
        if (!KeepAlivePolicy.isKeepAliveCommand(cmd)) return false
        val prefs = activity.getSharedPreferences(KeepAlivePolicy.PREFS, Context.MODE_PRIVATE)
        when (KeepAlivePolicy.commandAction(cmd)) {
            KeepAlivePolicy.Action.BATTERY -> {
                print("\n\u001B[36m[Keep-alive] Membuka pengecualian optimasi baterai...\u001B[0m\n")
                requestBatteryExemption(activity)
            }
            KeepAlivePolicy.Action.OEM_AUTOSTART -> {
                print(
                    "\n\u001B[36m[Keep-alive] Membuka pengaturan autostart OEM...\u001B[0m\n" +
                        "\u001B[33mIzinkan Tunnel Terminal di daftar autostart / background.\u001B[0m\n"
                )
                requestOemAutostart(activity)
            }
            KeepAlivePolicy.Action.NOTIFICATIONS -> {
                print("\n\u001B[36m[Keep-alive] Meminta izin notifikasi (wajib untuk FGS)...\u001B[0m\n")
                requestNotifications()
            }
            KeepAlivePolicy.Action.BOOT_ON -> {
                prefs.edit().putBoolean(KeepAlivePolicy.KEY_START_ON_BOOT, true).apply()
                print("\n\u001B[32m[Keep-alive] Start-on-boot AKTIF. Setelah reboot, FGS akan dihidupkan ulang.\u001B[0m\n")
            }
            KeepAlivePolicy.Action.BOOT_OFF -> {
                prefs.edit().putBoolean(KeepAlivePolicy.KEY_START_ON_BOOT, false).apply()
                print("\n\u001B[33m[Keep-alive] Start-on-boot nonaktif.\u001B[0m\n")
            }
            KeepAlivePolicy.Action.STATUS -> {
                val boot = prefs.getBoolean(KeepAlivePolicy.KEY_START_ON_BOOT, false)
                print(
                    "\n\u001B[36m${statusReport(activity)}\n" +
                        "  Start setelah reboot           : ${if (boot) "OK" else "BELUM"} (keep-alive boot on)\u001B[0m\n"
                )
            }
        }
        return true
    }

    fun statusReport(context: Context): String {
        val notif = hasNotificationPermission(context)
        val battery = isIgnoringBatteryOptimizations(context)
        val a11y = isAccessibilityEnabled(context)
        val allFiles = hasAllFilesAccess()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notifEnabled = if (Build.VERSION.SDK_INT >= 24) {
            nm?.areNotificationsEnabled() != false
        } else true
        fun mark(ok: Boolean) = if (ok) "OK" else "BELUM"

        return buildString {
            appendLine("[Keep-alive / izin sistem]")
            appendLine("  Notifikasi POST_NOTIFICATIONS : ${mark(notif)}")
            appendLine("  Channel notifikasi aktif      : ${mark(notifEnabled)}")
            appendLine("  Abaikan optimasi baterai      : ${mark(battery)}")
            appendLine("  Accessibility (Phone Agent)   : ${mark(a11y)}")
            appendLine("  Akses semua file (opsional)   : ${mark(allFiles)}")
            appendLine()
            appendLine("Tanpa notifikasi + FGS, Android 12+ mematikan PTY/SSH/Ubuntu")
            appendLine("saat app di belakang. Tanpa pengecualian baterai, OEM (Xiaomi,")
            appendLine("Oppo, Vivo, Samsung) membekukan proses meski FGS berjalan.")
            appendLine()
            appendLine("Perintah: keep-alive | keep-alive battery | keep-alive autostart")
            appendLine("          keep-alive notif | izin-status")
        }
    }
}
