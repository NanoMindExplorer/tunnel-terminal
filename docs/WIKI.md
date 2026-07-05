# Tunnel Terminal — Wiki

**Versi:** 6.6.0 (Phase 47 — Agent Mode + Storage Sandbox)
**Repo:** https://github.com/NanoMindExplorer/tunnel-terminal

---

## Daftar Isi

1. [Overview](#1-overview)
2. [Arsitektur Sistem](#2-arsitektur-sistem)
3. [File Structure](#3-file-structure)
4. [Native Layer (C++ NDK)](#4-native-layer-c-ndk)
5. [Terminal Emulator](#5-terminal-emulator)
6. [Shell Executor & Session Management](#6-shell-executor--session-management)
7. [SSH Client](#7-ssh-client)
8. [AI Layer](#8-ai-layer)
9. [Tool Calling & Permission System](#9-tool-calling--permission-system)
10. [MCP Protocol Integration](#10-mcp-protocol-integration)
11. [UI/UX Layer (Jetpack Compose)](#11-uiux-layer-jetpack-compose)
12. [Block-Based Terminal](#12-block-based-terminal)
13. [Command Palette](#13-command-palette)
14. [Context Mentions (@mentions)](#14-context-mentions-mentions)
15. [Theme System](#15-theme-system)
16. [Thread Safety Model](#16-thread-safety-model)
17. [Security Model](#17-security-model)
18. [Build Configuration](#18-build-configuration)
19. [Phase History](#19-phase-history)
20. [Known Limitations](#20-known-limitations)

---

## 1. Overview

Tunnel Terminal adalah terminal Android AI-native yang menggabungkan:
- **True PTY** via C++ NDK `forkpty()` (bukan `Runtime.exec()`)
- **AI Agent** dengan tool calling, streaming SSE, multi-turn memory
- **Warp-style UI** — block-based terminal, command palette, markdown rendering
- **SSH Client** via JSch dengan TOFU host key verification
- **MCP Protocol** untuk external tool interoperability
- **6 themes** dengan theme-aware syntax highlighting

Total codebase: ~9.500 baris (30 file Kotlin + 1 file C++ + resource XML + build config).

---

## 2. Arsitektur Sistem

```
┌──────────────────────────────────────────────────────┐
│  Jetpack Compose UI                                    │
│  ├── TabBar (tabs, +, 📁, 💾, 🔌, ⬡, ⊞, ⌘K, AI)    │
│  ├── TerminalScreenView (snapshot rendering, density)  │
│  ├── BlockTerminalView (Warp-style blocks)            │
│  ├── AIChatPanel (markdown, streaming, tool calls)    │
│  ├── CommandPalette (Ctrl+K fuzzy search)             │
│  ├── DiffViewDialog (inline diff before apply)        │
│  ├── TunnelEditorDialog (syntax highlighting)         │
│  └── SshConnectDialog / FileExplorer / Workspace      │
├──────────────────────────────────────────────────────┤
│  TerminalSession interface                             │
│  ├── ShellExecutor (local PTY via forkpty)            │
│  └── SshShellExecutor (remote SSH via JSch)           │
├──────────────────────────────────────────────────────┤
│  TerminalEmulator                                      │
│  ANSI parser, screen buffer, alt screen, SGR,         │
│  DA/DSR response, synchronized(lock)                  │
├──────────────────────────────────────────────────────┤
│  AI Layer                                              │
│  ├── AIAgent (SSE streaming, multi-turn, tool calls)  │
│  ├── ToolExecutor + PermissionManager                  │
│  ├── ContextManager (@mentions resolve)               │
│  ├── McpManager (MCP server connections)              │
│  ├── MarkdownText (markdown renderer)                  │
│  └── SyntaxHighlighter (8 languages, regex-based)     │
├──────────────────────────────────────────────────────┤
│  TerminalJni (Kotlin ↔ C++ bridge)                    │
│  isLoaded flag, createSession, write, resize, close,  │
│  killSession (PID-safe via waitpid)                   │
├──────────────────────────────────────────────────────┤
│  native-lib.cpp (C++ NDK)                             │
│  forkpty() → /system/bin/sh (via execve)              │
│  SIGTERM → SIGKILL → waitpid (no zombie)              │
│  TERM=xterm-256color, HOME set via envp               │
│  Partial write loop with EINTR handling                │
└──────────────────────────────────────────────────────┘
```

---

## 3. File Structure

```
app/src/main/
├── cpp/
│   ├── native-lib.cpp          (222 lines) — PTY engine
│   └── CMakeLists.txt          — CMake config
├── java/com/tunnel/terminal/
│   ├── MainActivity.kt         (1800+ lines) — Entry point, UI wiring
│   ├── TerminalUI.kt           (1000+ lines) — TabBar, TerminalScreenView, AIChatPanel
│   ├── TerminalEmulator.kt     (760+ lines) — ANSI parser, screen buffer
│   ├── ShellExecutor.kt        (297 lines) — Local PTY session manager
│   ├── SshShellExecutor.kt     (323 lines) — Remote SSH session manager
│   ├── TerminalSession.kt      (35 lines) — Common interface
│   ├── TerminalJni.kt          (57 lines) — JNI bridge
│   ├── AIAgent.kt              (310 lines) — SSE streaming + tool calls
│   ├── AiToolCall.kt           (388 lines) — Tool parser + executor + permission
│   ├── AISettings.kt           (53 lines) — AI config + 13 presets
│   ├── CommandBlock.kt         (338 lines) — Block data + manager + view
│   ├── CommandPalette.kt       (278 lines) — Ctrl+K modal
│   ├── ContextManager.kt       (214 lines) — @mentions parser + resolver
│   ├── DiffView.kt             (204 lines) — LCS diff + dialog
│   ├── FileExplorer.kt         (419 lines) — File browser + workspace dialog
│   ├── MarkdownText.kt         (333 lines) — Markdown renderer
│   ├── McpManager.kt           (264 lines) — MCP server connections
│   ├── ModelFetcher.kt         (183 lines) — /models endpoint fetcher
│   ├── SmartAutocomplete.kt    (157 lines) — Command suggestions + voice input
│   ├── SnippetManager.kt       (90 lines) — Workflow snippet persistence
│   ├── StorageManager.kt       (212 lines) — SAF bridge
│   ├── SshConnectDialog.kt     (200 lines) — SSH connection form
│   ├── SyntaxHighlighter.kt    (314 lines) — 8-language highlighter
│   ├── SystemInfo.kt           (186 lines) — MOTD system info
│   ├── ThemeManager.kt         (197 lines) — 6 theme presets
│   ├── TerminalForegroundService.kt (98 lines) — Keep-alive service
│   ├── TunnelEditor.kt         (284 lines) — Code editor with highlighting
│   ├── ImageHelper.kt          (91 lines) — Image compress + base64
│   ├── AgentWorkflow.kt        (139 lines) — Multi-step workflow data
│   └── WorkspaceManager.kt     (137 lines) — Tab session persistence
├── res/
│   ├── drawable/               — Launcher icons (vector)
│   ├── mipmap-anydpi-v26/      — Adaptive icon XML
│   └── values/                 — Theme, colors
└── AndroidManifest.xml         — Permissions, service, activity
```

---

## 4. Native Layer (C++ NDK)

### forkpty() Engine

```cpp
pid_t pid = forkpty(&masterFd, NULL, NULL, NULL);
if (pid == 0) {
    // Child: exec /system/bin/sh via execve (async-signal-safe)
    static char term_env[] = "TERM=xterm-256color";
    // ...
    execve("/system/bin/sh", argv, envp);
    _exit(1);  // async-signal-safe
}
// Parent: return masterFd + pid to Java
```

### Key Design Decisions

- **`execve()` bukan `execl()`** — environment diteruskan via parameter (async-signal-safe), bukan `setenv()` (tidak safe antara fork-exec)
- **`_exit()` bukan `exit()`** — `_exit` adalah async-signal-safe, `exit()` memanggil atexit handlers
- **`static char` arrays** — string literals adalah `const char*` di C++; cast ke `char*` adalah UB
- **Partial write loop** — `write()` bisa write kurang dari requested; loop dengan EINTR handling
- **PID-safe killSession** — `waitpid(WNOHANG)` sebelum `kill()` untuk mencegah PID recycling

---

## 5. Terminal Emulator

### Screen Buffer

```kotlin
class TerminalEmulator(private val themeHolder: ThemeHolder) {
    private var screen = Array(rows) { Array(cols) { TerminalCell() } }
    private var altScreen: Array<Array<TerminalCell>>? = null
    private var mainScreen: Array<Array<TerminalCell>>? = null  // saved saat alt screen
}
```

### ANSI Support

| Feature | Implementation |
|---------|---------------|
| SGR (colors) | 30-37, 90-97, 40-47, 100-107, 38;5;n (256-color), 38;2;r;g;b (TrueColor) |
| SGR (attributes) | bold (1), italic (3), underline (4), reverse (7) |
| Cursor | H/f (position), A/B/C/D (move), G/d (col/row), s/u (save/restore) |
| Erase | J 0/1/2/3 (screen), K 0/1/2 (line) |
| Scroll | CSI r (scroll region), L/M (insert/delete lines) |
| Alt screen | CSI ?1049h/l (save main screen, switch) |
| Cursor visibility | CSI ?25h/l |
| DA/DSR | CSI c (device attrs), CSI 6n (cursor pos report) — response via writeCallback |
| Partial ANSI | pendingBuffer — buffer ESC sequences yang ter-split antar chunk |

### Thread Safety

```kotlin
private val lock = Any()

fun process(data: String) = synchronized(lock) { processInternal(data) }
fun getScreenSnapshot(): Array<Array<TerminalCell>> = synchronized(lock) { /* copy */ }
fun getCursorState(): CursorState = synchronized(lock) { /* copy */ }
fun snapshotRows(): Int = synchronized(lock) { rows }
fun snapshotCols(): Int = synchronized(lock) { cols }
```

Compose membaca **snapshot copy** — tidak pernah baca array yang sedang di-mutate.

### blankCell() Helper

```kotlin
private fun blankCell() = TerminalCell(fgColor = defaultFg, bgColor = defaultBg)
```

Semua 23+ lokasi yang membuat cell kosong pakai `blankCell()` — warna dari tema aktif, bukan hardcode green/black.

---

## 6. Shell Executor & Session Management

### Lifecycle

```
start() → createSession(24, 80, outFd) → adoptFd → readThread.start()
                                                      ↓
                                              readLoop() → emulator.process(text)
                                                        → outputBuffer.append(text)
                                                        → triggerScreenUpdate()

destroy() → pfd.close() (unblock readLoop)
          → readThread.interrupt() + join(300ms)
          → killSession(SIGTERM → SIGKILL → waitpid)
          → masterFd = -1
```

### Thread Safety

- `outputLock` — synchronize outputBuffer access (readLoop writes, main reads)
- `writeLock` — serialize concurrent JNI writes
- `fdClosed` (AtomicBoolean) — prevent double-close fd antara readLoop exit vs destroy()
- `readThread` (volatile) — track + interrupt saat destroy

### ID Generation

```kotlin
companion object {
    private val globalIdCounter = AtomicInteger(0)
}
override val id: Int = globalIdCounter.incrementAndGet()
```

AtomicInteger — no timestamp collision.

---

## 7. SSH Client

### JSch Integration

```kotlin
class SshShellExecutor(
    private val themeHolder: ThemeHolder,
    private val config: SshConnectionConfig,
    private val context: Context? = null
) : TerminalSession
```

### Features

- Password + private key authentication
- PTY type `xterm-256color` + resize via `setPtySize`
- UTF-8 safe: `ByteBuffer` + `CharsetDecoder` (sama seperti ShellExecutor)
- TOFU host key: `StrictHostKeyChecking=ask` + log warning
- Thread-safe: `outputLock` + `writeLock`
- Graceful disconnect: channel disconnect di readLoop `finally`

---

## 8. AI Layer

### Streaming SSE

```kotlin
fun askAIStreaming(settings, conversation, terminalContext): Flow<String> = callbackFlow {
    connection = openConnection(settings, streaming = true)
    // readTimeout = 120000 (BUG-35 fix: not 0/unlimited)
    val reader = BufferedReader(InputStreamReader(inputStream))
    while (reader.readLine().also { line = it } != null) {
        if (!isActive) break
        if (line.startsWith("data:")) {
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val json = JSONObject(data)
            val delta = json.getJSONArray("choices").getJSONObject(0)
                .optJSONObject("delta")?.optString("content") ?: ""
            if (delta.isNotEmpty()) trySend(delta)
        }
    }
}
```

### Multi-turn Memory

- Conversation history (max 20 messages) dikirim ke AI
- Error/streaming/command messages di-filter dari history
- Image attachments sebagai multi-modal content (JSONArray of text + image_url parts)

### System Prompt

System prompt includes:
- Shell capabilities (`/system/bin/sh`, available commands)
- Tool documentation (read_file, write_file, delete_file, run_command, etc.)
- Markdown formatting instructions
- MCP tools section (jika ada MCP servers connected)

---

## 9. Tool Calling & Permission System

### Tool Call Format

AI response berisi `<tool_call>{"tool":"read_file","args":{"path":"/foo"}}</tool_call>`

### Parser (BUG-38 fix)

```kotlin
// Strip markdown code blocks dulu (cegah kutipan AI dieksekusi)
val responseWithoutCodeBlocks = Regex("```[\\s\\S]*?```").replace(response, "")
// Parse <tool_call> tags dari response yang sudah di-strip
val toolCallRegex = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
```

### Permission Model (BUG-01 fix)

| Tool | Always Allow? | Permission |
|------|--------------|------------|
| read_file, list_files, search_files, get_terminal_output | N/A (read-only) | Auto-approved |
| write_file | Yes | Diff preview → Apply/Reject |
| delete_file | **No** (BUG-01) | Always prompt |
| run_command | **No** (BUG-01) | Always prompt |
| mcp.* | Yes | Per MCP server |

### Permission Dialog (BUG-04 fix)

- Full args display (`displayTextFull`) dengan scroll — tidak dipotong 50 char
- "Always allow" disembunyikan untuk run_command/delete_file

---

## 10. MCP Protocol Integration

### McpManager

```kotlin
class McpManager(context: Context) {
    val servers: List<McpServerConfig>
    val discoveredTools: Map<String, List<McpTool>>

    suspend fun discoverAllTools(): List<McpTool>  // GET /tools dari semua servers
    suspend fun invokeTool(serverName, toolName, args): String  // POST /tools/{name}/invoke
    fun generateSystemPromptSection(): String  // List MCP tools untuk AI system prompt
}
```

### Security (BUG-20 fix)

- `http://` ditolak kecuali `localhost`/`127.0.0.1` (API key bocor tanpa HTTPS)
- API key via `Authorization: Bearer` header

---

## 11. UI/UX Layer (Jetpack Compose)

### TerminalScreenView

- **Density-aware grid** (BUG-05 fix): `LocalDensity` + `sp.toPx()` untuk konversi yang benar
- **Pinch-to-zoom** (BUG-06 fix): `lastSize` disimpan, `onResize` dipanggil langsung di `LaunchedEffect(fontSize)`
- **Snapshot rendering**: `remember(screenDirty) { emulator.getScreenSnapshot() }`
- **No word wrap**: `softWrap = false, maxLines = 1, overflow = Clip`
- **External fontSize**: `fontSizeState` + `onFontSizeChange` dari parent (persist)

### AIChatPanel

- **Markdown rendering** untuk AI responses (bukan plain text)
- **Streaming cursor** (▋ blink)
- **Auto-scroll** ke bawah saat streaming
- **Image preview chips** untuk pending attachments
- **Settings sub-tabs**: AI / Theme / About

---

## 12. Block-Based Terminal

### CommandBlock

```kotlin
data class CommandBlock(
    val id: Long,          // AtomicLong (BUG-25 fix)
    val command: String,
    val output: String,
    val timestamp: Long,
    val status: BlockStatus,  // RUNNING, SUCCESS, ERROR, CANCELLED
    val isCollapsed: Boolean
)
```

### BlockManager

- `parseFromOutput(rawOutput)` — parse terminal history ke blocks
- `addBlock(command)` — add live block saat command dijalankan
- `toggleCollapse(id)` — collapse/expand block
- BUG-19 fix: histori parse default SUCCESS (bukan RUNNING)

### BlockTerminalView

- Card dengan status icon, timestamp, collapsible output
- Actions: Rerun, 🤖 Explain (kirim ke AI)

---

## 13. Command Palette

### Features

- Ctrl+K / ⌘K button di TabBar
- Fuzzy search across 5 categories: AI, Navigation, Settings, Commands, Recent
- Keyboard navigation: Up/Down/Enter/Escape (BUG-11 fix di Phase 28 — `rememberCoroutineScope`)
- Recent commands auto-suggested

---

## 14. Context Mentions (@mentions)

### Supported Mentions

| Mention | Resolves To |
|---------|-------------|
| `@file:/path/to/file` | File content (max 5000 chars) |
| `@block:N` | Command block N output |
| `@command:"cmd"` | Execute command via MarkerExecutor + attach output (with exit code) as context (Phase 37+) |
| `@terminal` | Current terminal output (max 3000 chars) |
| `@snippet:name` | Saved snippet command |

### Parser (BUG-13 fix)

Regex support quoted values `"..."` dan paths dengan spasi:
```
@(file|block|command|terminal|snippet)(?::(?:"([^"]+)"|(\S+)))?|@terminal
```

---

## 15. Theme System

### 6 Themes

| Theme | Background | Foreground | Accent |
|-------|-----------|-----------|--------|
| Matrix | #000000 | #00FF00 | #6200EE |
| Dracula | #282A36 | #F8F8F2 | #BD93F9 |
| Solarized Dark | #002B36 | #839496 | #268BD2 |
| Monokai Pro | #2D2A2E | #FCFCFA | #FF6188 |
| Nord | #2E3440 | #D8DEE9 | #88C0D0 |
| Tokyo Night | #1A1B26 | #A9B1D6 | #7AA2F7 |

### Theme Application

- `ThemeHolder` — shared reference across all ShellExecutor instances
- `TerminalEmulator.blankCell()` — pakai `defaultFg`/`defaultBg` dari theme
- SGR 30-37/90-97/40-47/100-107 — pakai ANSI palette dari theme
- 256-color 0-15 — pakai ANSI palette dari theme
- UI (drawer, buttons, text) — pakai `uiBg`/`uiSurface`/`uiAccent`/`uiText`/`uiTextMuted`
- Syntax highlighting — pakai `SyntaxHighlighter.colorsFromTheme(theme)`
- Font size persist di SharedPreferences

---

## 16. Thread Safety Model

### Locks

| Component | Lock | Protects |
|-----------|------|----------|
| TerminalEmulator | `lock` (Any) | screen array, cursor, style, scroll region |
| ShellExecutor | `outputLock` | outputBuffer (StringBuilder) |
| ShellExecutor | `writeLock` | PTY write (serialize concurrent JNI calls) |
| ShellExecutor | `fdClosed` (AtomicBoolean) | prevent double-close fd |
| SshShellExecutor | `outputLock` + `writeLock` | same pattern |

### Snapshot Pattern

Compose **tidak pernah** membaca array yang sedang di-mutate:

```kotlin
// Compose reads:
val screenSnapshot = remember(screenDirty) { emulator.getScreenSnapshot() }  // copy
val cursorState = remember(screenDirty) { emulator.getCursorState() }        // data class copy
val renderRows = emulator.snapshotRows()  // synchronized Int
val renderCols = emulator.snapshotCols()  // synchronized Int
```

### readLoop Thread

- Set sebagai daemon (`isDaemon = true`)
- `isAlive` volatile — checked di while loop condition
- `InterruptedException` catch — graceful exit saat destroy()
- `finally` block — close inputStream, flush emulator, set isAlive=false

---

## 17. Security Model

### AI Tool Permissions (BUG-01 fix)

- `run_command` dan `delete_file` **selalu** butuh permission prompt
- "Always Allow" disembunyikan dari dialog untuk tools tersebut
- `setPermission()` menolak `ALWAYS_ALLOW` untuk run_command/delete_file
- Mencegah indirect prompt injection → eksekusi command arbitrer

### SSH Host Key (BUG-02 fix)

- `StrictHostKeyChecking=ask` (bukan `no`)
- TOFU pattern: accept on first connect, verify on subsequent
- Security warning logged

### Credential Storage (BUG-03 fix)

- `android:allowBackup=false` — kredensial tidak ikut backup
- `requestLegacyExternalStorage` dihapus (flag mati untuk targetSdk=34)

### MCP Server (BUG-20 fix)

- `http://` ditolak kecuali `localhost`/`127.0.0.1`
- API key via HTTPS only

### AI Tool Call Injection (BUG-38 fix)

- Markdown code blocks (` ```...``` `) di-strip sebelum parse tool calls
- AI yang mengutip sintaks `<tool_call>` di code block tidak dieksekusi

---

## 18. Build Configuration

### build.gradle.kts

```kotlin
android {
    compileSdk = 34
    minSdk = 24
    targetSdk = 34
    versionCode = 19
    versionName = "5.1.0-phase31-crash-on-launch-fix"

    // NDK & CMake
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8/ProGuard
            isShrinkResources = true
            proguardFiles(...)
        }
    }
}

dependencies {
    // Compose BOM 2024.02.00
    // kotlinx-coroutines (android + core)
    // JSch 0.2.21 (mwiede fork)
    // androidx.documentfile (SAF)
    // material-icons-extended
}
```

### ProGuard Rules

Keep rules untuk:
- JSch (`com.jcraft.jsch.**`)
- Data classes (AISettings, ChatMessage, McpServerConfig, dll.)
- JNI methods (`native <methods>`)
- TerminalJni

---

## 19. Phase History

| Phase | Highlight | Lines Changed |
|---|---|---|
| 1-6 | Initial Compose UI, multi-tab, AI Copilot, ANSI parser, foreground service | ~2000 |
| 7 | C/C++ NDK PTY engine (forkpty) | ~200 |
| 8-9 | Terminal emulator + AI Auto-Pilot | ~800 |
| 10-12 | SAF, pinch-zoom, Tunnel Editor | ~600 |
| 13-16 | Modifier keys, volume nav, lifecycle guard, help | ~400 |
| **17** | **Major Bug Fix** — 60+ bugs (phantom commands, zombie, MOTD) | +2606/-1056 |
| **18** | **AI Streaming SSE + Multi-turn + Theme Picker** | +1090/-283 |
| **19** | **Free Provider + Image Vision + File Explorer + Workspace + Icon** | +1578/-54 |
| **19.5** | **Input Reliability + Mouse** | +332/-55 |
| **20** | **Comprehensive Bug Fix** — cursor, fd, alt screen, ANR | +228/-62 |
| **21** | **Thread Safety + SSH + Syntax + Split Pane** | +1240/-142 |
| **22** | **AI-Native Revolution** — blocks, palette, tool calls, markdown | +1842/-18 |
| **23** | **@context + MCP + Diff + Voice + Workflows** | +1125/-6 |
| **24-26** | **Stability** — pinch-zoom persist, block input, fontSize, all 13 bugs | +200/-70 |
| **27** | **Security CRITICAL** — 5 bugs (permission, SSH, backup, voice) | +80/-31 |
| **28** | **Functional HIGH** — 9 bugs (density, pinch, scroll, colors, DA/DSR) | +139/-54 |
| **29** | **MEDIUM** — 12 bugs (SSH cleanup, workspace, MCP, editor, ID, fd) | +79/-20 |
| **30** | **LOW** — 10 bugs (version, ProGuard, fork-exec, vision, timeout) | +101/-56 |
| **31** | **Crash-on-launch fix** — 7 bugs (prefs, foreground, JNI, native) | +83/-30 |

**Total: 56 commits, ~9.500 lines code, 30 Kotlin files + 1 C++ file**

---

## 20. Known Limitations

1. **No scrollback buffer** — baris yang keluar dari layar hilang permanen (raw mode)
2. **PTY initial 80×24** — hardcoded, onSizeChanged fixes immediately after layout
3. **@command: real implementation** (Phase 37+) — command dieksekusi via MarkerExecutor, output + exit code di-capture real-time
4. **SSH host key dialog** — ✅ FIXED (Phase 41) — dialog blocking dengan fingerprint lama vs baru sekarang ada
5. **Agent Workflows UI builder** — workflows hanya bisa dibuat via code, tidak ada UI builder. Tapi Agent Mode (Phase 47) adalah alternatif yang lebih fleksibel
6. **MCP server management UI** — add/remove servers via code only, tidak ada dialog UI
7. **EncryptedSharedPreferences** — ✅ FIXED (Phase 41) — API key sekarang dienkripsi via EncryptedSharedPreferences (AES256-GCM)

---

## Catatan Penutup

Tunnel Terminal v5.1.0 adalah hasil 31 phase development dengan 56 commit, mulai dari terminal sederhana hingga AI-native terminal dengan 25+ fitur. Codebase telah melalui comprehensive audit (38 bug ditemukan dan di-fixed) plus crash-on-launch audit (7 bug di-fixed).

Untuk panduan cara penggunaan, lihat [USER_GUIDE.md](USER_GUIDE.md).
