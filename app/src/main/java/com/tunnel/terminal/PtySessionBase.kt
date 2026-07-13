package com.tunnel.terminal

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wave-5: Shared PTY session core for local shell and proot/Ubuntu.
 *
 * ShellExecutor and ProotShellExecutor only differ in how they *spawn* the child
 * (createSession vs createSessionExec). Read loop, write, resize, destroy, clean
 * output, and screen-dirty throttling live here so fixes apply once.
 */
abstract class PtySessionBase(
    protected val themeHolder: ThemeHolder,
    private val logTag: String
) : TerminalSession {

    protected var masterFd: Int = -1
    protected var childPid: Int = -1
    protected var pfd: ParcelFileDescriptor? = null

    @Volatile
    protected var readThread: Thread? = null

    protected val fdClosed = AtomicBoolean(false)

    override val id: Int = globalIdCounter.incrementAndGet()

    override var emulator = TerminalEmulator(themeHolder).also {
        it.writeCallback = { data -> writeRaw(data) }
    }

    override var isAlive by mutableStateOf(true)
        protected set

    private val _screenDirty = MutableStateFlow(0)
    override val screenDirty: StateFlow<Int> = _screenDirty.asStateFlow()

    @Volatile
    private var lastScreenDirtyTime: Long = 0

    override fun triggerScreenUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastScreenDirtyTime >= 33) {
            lastScreenDirtyTime = now
            _screenDirty.value++
        }
    }

    private val _lastCommandOutput = MutableStateFlow("")
    override val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()

    protected val outputLock = Any()
    protected var outputBuffer = StringBuilder()
    private val writeLock = Any()

    override val commandHistory = mutableListOf<String>()
    override var currentCommandBuffer by mutableStateOf("")

    @Volatile
    override var historyIndex: Int = -1

    @Volatile
    override var currentPrompt: String = "$ "

    /** Optional hook when first PTY byte arrives (proot readiness). */
    protected open fun onFirstOutputByte() {}

    /** Message shown when the process exits naturally. */
    protected open fun processExitMessage(): String =
        "\n\u001B[33m[Process Exited. Tap screen to restart session.]\u001B[0m\n"

    protected fun resetSessionBuffers() {
        synchronized(outputLock) { outputBuffer.setLength(0) }
        _lastCommandOutput.value = ""
        fdClosed.set(false)
    }

    protected fun adoptMasterAndStartReader(pid: Int, fd: Int, threadName: String) {
        childPid = pid
        masterFd = fd
        pfd = ParcelFileDescriptor.adoptFd(masterFd)
        Log.i(logTag, "PTY session: pid=$childPid, fd=$masterFd, id=$id")
        readThread = Thread({ readLoop() }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    protected fun failStart(message: String) {
        isAlive = false
        emulator.process("\u001B[31m[ERROR] $message\u001B[0m\n")
        triggerScreenUpdate()
    }

    private fun readLoop() {
        val pfdRef = pfd
        if (pfdRef == null) {
            Log.e(logTag, "pfd null di readLoop, abort")
            return
        }
        val inputStream = FileInputStream(pfdRef.fileDescriptor)
        val buffer = ByteArray(4096)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val byteBuffer = ByteBuffer.wrap(buffer)
        val charBuffer = CharBuffer.allocate(8192)
        var firstByte = true

        var bytesRead: Int = 0
        try {
            while (isAlive && inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isAlive) break
                if (bytesRead <= 0) continue

                if (firstByte) {
                    firstByte = false
                    onFirstOutputByte()
                }

                byteBuffer.position(0)
                byteBuffer.limit(bytesRead)
                decoder.decode(byteBuffer, charBuffer, false)
                charBuffer.flip()
                val text = charBuffer.toString()
                charBuffer.clear()

                emulator.process(text)
                val outputStr = synchronized(outputLock) {
                    outputBuffer.append(text)
                    if (outputBuffer.length > OUTPUT_RING_CHARS) {
                        outputBuffer = StringBuilder(
                            outputBuffer.substring(outputBuffer.length - OUTPUT_RING_CHARS)
                        )
                    }
                    outputBuffer.toString()
                }
                _lastCommandOutput.value = outputStr
                triggerScreenUpdate()
            }
        } catch (e: InterruptedException) {
            Log.i(logTag, "readLoop interrupted (destroy)")
        } catch (e: Exception) {
            Log.w(logTag, "readLoop berakhir: ${e.message}")
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            try { emulator.flush() } catch (_: Exception) {}
            try {
                if (childPid > 1) {
                    TerminalJni.killSession(childPid, 0)
                    childPid = -1
                }
            } catch (e: Exception) {
                Log.w(logTag, "reap on exit: ${e.message}")
            }
            isAlive = false
            emulator.process(processExitMessage())
            triggerScreenUpdate()
        }
    }

    override fun resizeTerminal(newRows: Int, newCols: Int, fontSize: Float) {
        if (masterFd < 0) return
        TerminalJni.resize(masterFd, newRows, newCols)
        emulator.resize(newRows, newCols, fontSize.sp)
        triggerScreenUpdate()
    }

    override fun executeCommand(command: String) {
        if (isAlive) writeRaw(command + "\n")
    }

    override fun writeRaw(data: String) {
        if (masterFd < 0 || !isAlive) return
        synchronized(writeLock) {
            if (masterFd >= 0 && isAlive) {
                TerminalJni.write(masterFd, data.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    override fun clearScreen() {
        emulator.process("\u001B[2J\u001B[H")
        emulator.clearScrollback()
        synchronized(outputLock) { outputBuffer.setLength(0) }
        _lastCommandOutput.value = ""
        triggerScreenUpdate()
    }

    override fun getCleanOutput(): String {
        val raw = synchronized(outputLock) { outputBuffer.toString() }
        val sb = StringBuilder(raw.length)
        val regex = Regex("\u001B\\[[;?\\d]*[A-Za-z]|\u001B\\][^\\u0007]*\\u0007|\u001B\\[[0-9;]*[A-Za-z]")
        var lastEnd = 0
        regex.findAll(raw).forEach { m ->
            sb.append(raw, lastEnd, m.range.first)
            lastEnd = m.range.last + 1
        }
        sb.append(raw, lastEnd, raw.length)
        return sb.toString().trim().take(CLEAN_OUTPUT_CHARS)
    }

    override fun destroy() {
        if (!isAlive && masterFd < 0 && childPid < 0 && readThread == null) return
        isAlive = false

        try {
            if (fdClosed.compareAndSet(false, true)) {
                pfd?.close()
            }
        } catch (_: Exception) {}
        pfd = null

        try {
            readThread?.interrupt()
            readThread?.join(300)
        } catch (_: Exception) {}
        readThread = null

        try {
            if (childPid > 1) {
                TerminalJni.killSession(childPid, 15)
                Thread.sleep(50)
                TerminalJni.killSession(childPid, 9)
                childPid = -1
            }
        } catch (e: Exception) {
            Log.w(logTag, "killSession error: ${e.message}")
        }
        masterFd = -1
    }

    protected fun rewireEmulator() {
        emulator = TerminalEmulator(themeHolder).also {
            it.writeCallback = { data -> writeRaw(data) }
        }
    }

    companion object {
        private val globalIdCounter = AtomicInteger(0)
        private const val OUTPUT_RING_CHARS = 16000
        private const val CLEAN_OUTPUT_CHARS = 8000
    }
}
