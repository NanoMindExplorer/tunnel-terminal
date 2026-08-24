package com.tunnel.terminal

/**
 * v9.5.8: Pure keep-alive / permission policy (unit-testable).
 *
 * Android 12–15 kill background terminals unless:
 *  - a typed foreground service is running with a visible notification
 *  - the app is exempt from battery optimization
 *  - OEM autostart / "app lock" is allowed (Xiaomi, Oppo, Vivo, Huawei, Samsung)
 *
 * dataSync FGS on Android 15 is capped at ~6 hours; specialUse is the correct
 * long-lived type for a terminal/SSH/proot session.
 */
object KeepAlivePolicy {

    const val PREFS = "TunnelKeepAlive"
    const val KEY_BATTERY_ASKED = "battery_exemption_asked"
    const val KEY_OEM_ASKED = "oem_autostart_asked"
    const val KEY_START_ON_BOOT = "start_on_boot"

    fun isKeepAliveCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return c == "keep-alive" || c.startsWith("keep-alive ") ||
            c == "izin-status" || c == "permission-status"
    }

    data class OemAutostartTarget(
        val vendor: String,
        val packageName: String,
        val activity: String
    )

    /** Known OEM screens that actually control "start in background". */
    val OEM_AUTOSTART_TARGETS: List<OemAutostartTarget> = listOf(
        OemAutostartTarget(
            "Xiaomi / HyperOS / MIUI",
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        OemAutostartTarget(
            "Xiaomi (Power)",
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        ),
        OemAutostartTarget(
            "Oppo / ColorOS",
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        OemAutostartTarget(
            "Oppo (oplus)",
            "com.oplus.safecenter",
            "com.oplus.safecenter.startupapp.StartupAppListActivity"
        ),
        OemAutostartTarget(
            "Vivo / Funtouch",
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        OemAutostartTarget(
            "Huawei / Harmony",
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        OemAutostartTarget(
            "Honor",
            "com.hihonor.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        OemAutostartTarget(
            "Samsung",
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        OemAutostartTarget(
            "OnePlus",
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        )
    )

    fun commandAction(cmd: String): Action {
        val c = cmd.trim()
        val rest = when {
            c == "keep-alive" || c == "izin-status" || c == "permission-status" -> "status"
            c.startsWith("keep-alive ") -> c.removePrefix("keep-alive ").trim()
            else -> "status"
        }
        return when (rest) {
            "battery", "baterai" -> Action.BATTERY
            "autostart", "oem" -> Action.OEM_AUTOSTART
            "notif", "notification", "notifikasi" -> Action.NOTIFICATIONS
            "boot", "boot on", "boot-on" -> Action.BOOT_ON
            "boot off", "boot-off" -> Action.BOOT_OFF
            else -> Action.STATUS
        }
    }

    enum class Action { STATUS, BATTERY, OEM_AUTOSTART, NOTIFICATIONS, BOOT_ON, BOOT_OFF }
}
