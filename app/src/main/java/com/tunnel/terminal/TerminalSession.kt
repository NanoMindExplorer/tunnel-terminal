package com.tunnel.terminal

import androidx.compose.runtime.MutableState
import kotlinx.coroutines.flow.StateFlow

/**
 * TerminalSession - Common interface untuk local PTY (ShellExecutor) dan
 * remote SSH (SshShellExecutor). Memungkinkan TabBar menampung kedua tipe.
 *
 * Phase 21: Interface untuk support SSH sessions alongside local PTY.
 */
interface TerminalSession {
    val id: Int
    val emulator: TerminalEmulator
    val isAlive: Boolean
    val screenDirty: StateFlow<Int>
    val lastCommandOutput: StateFlow<String>
    val commandHistory: MutableList<String>
    var currentCommandBuffer: String
    var historyIndex: Int
    var currentPrompt: String

    /** "local" untuk ShellExecutor, "ssh" untuk SshShellExecutor. */
    val sessionType: String

    fun triggerScreenUpdate()
    suspend fun start()
    suspend fun restart()
    fun resizeTerminal(newRows: Int, newCols: Int, fontSize: Float)
    fun executeCommand(command: String)
    fun writeRaw(data: String)
    fun clearScreen()
    fun getCleanOutput(): String
    fun destroy()
}
