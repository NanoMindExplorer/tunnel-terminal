package com.tunnel.terminal

import android.app.Application
import androidx.compose.runtime.mutableStateListOf

/**
 * TunnelApp — Application subclass untuk hold state yang harus survive Activity recreate.
 *
 * Phase 49 fix (F-3): Screen buffer hilang saat Activity di-recreate (rotasi, low-memory kill).
 *
 * OLD BUG: shellExecutors di-hold di MainActivity. Saat Activity di-recreate, instance
 * baru dibuat → TerminalEmulator kosong → layar "menghilang" walau proses shell masih hidup
 * di TerminalForegroundService.
 *
 * FIX: Pindah shellExecutors ke Application scope. Application hidup sepanjang process
 * lifetime — Activity recreate tidak menghancurkan instance. Saat Activity baru attach,
 * dia akses shellExecutors dari TunnelApp → dapat instance lama dengan screen buffer utuh.
 *
 * Cara pakai di Activity:
 *   val app = application as TunnelApp
 *   shellExecutors = app.shellExecutors
 */
class TunnelApp : Application() {

    /** Phase 49 (F-3): Shell executors di-hold di Application scope.
     *  Survive Activity recreate — screen buffer tidak hilang. */
    val shellExecutors = mutableStateListOf<TerminalSession>()

    /** Phase 49 (F-3): Active executor ID juga di-hold di Application. */
    var activeExecutorId: Int = 0

    companion object {
        private const val TAG = "TunnelApp"
    }
}
