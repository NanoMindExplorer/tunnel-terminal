package com.tunnel.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Optional start-on-boot. Off by default; enable with `keep-alive boot on`.
 * PTY sessions do not survive reboot — this only re-establishes the FGS
 * so OEM killers are less likely to freeze the app on the next open.
 */
class BootKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val on = context.getSharedPreferences(KeepAlivePolicy.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KeepAlivePolicy.KEY_START_ON_BOOT, false)
        if (!on) return
        Log.i("BootKeepAlive", "BOOT_COMPLETED — starting FGS")
        KeepAliveManager.startForegroundSafely(context)
    }
}
