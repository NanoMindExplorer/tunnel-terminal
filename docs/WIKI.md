# Tunnel Terminal — Wiki

**Versi:** 9.4.0 (Phase 6 — Performance & UX: MCP parallel, StringBuilder in-place, Ctrl+Tab, pendingBuffer 256, terminalContext takeLast 4000)
**Repo:** https://github.com/NanoMindExplorer/tunnel-terminal
**Release:** [v9.4.0](https://github.com/NanoMindExplorer/tunnel-terminal/releases/tag/v9.4.0)
**Stats:** 68 Kotlin sources · NDK C++ · 34 unit test files · versionCode 74 · 210 commits

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
- **AI Agent / Copilot / Skills** — tool calling, SSE, multi-turn, skill CRUD inject
- **AI side panel** (Wave 21) — Chat | Flow | Skill | Set di kanan, terminal tetap terlihat
- **Ubuntu 24.04 proot** — download robust (Wave 22), path AI → `/root` (Wave 23)
- **Device storage** (Wave 19) — SAF + MediaStore Download + perintah `storage-*`
- **SSH + SFTP**, MCP, block mode, command palette, 6 themes

Codebase: 68 file Kotlin + NDK + 27 test files + assets proot + Gradle flavors (full/playstore).

---

## 2. Arsitektur Sistem

```
┌──────────────────────────────────────────────────────┐
│  Jetpack Compose UI                                    │
│  ├── TabBar (tabs, +, 📁, 💾, 🔌, ⬡, ⊞, ⌘K, AI)    │
│  ├── TerminalScreenView (snapshot rendering, density)  │
│  ├── BlockTerminalView (Warp-style blocks)            │
│  ├── AI side panel (Chat / Flow / Skills / Settings)  │
│  ├── AgentScreen · SkillsPanel · DiffView · Editor    │
│  └── CommandPalette · FileExplorer · Workspace · SSH  │
├──────────────────────────────────────────────────────┤
│  TerminalSession interface                             │
│  ├── ShellExecutor (local) · ProotShellExecutor       │
│  └── SshShellExecutor (JSch + SFTP)                   │
├──────────────────────────────────────────────────────┤
│  TerminalEmulator                                      │
│  ANSI parser, screen buffer, alt screen, SGR,         │
│  DA/DSR response, synchronized(lock)                  │
├──────────────────────────────────────────────────────┤
│  AI Layer                                              │
│  ├── AIAgent + SkillManager (Wave 25 inject skills)   │
│  ├── ToolExecutor + SessionTargetResolver (Ubuntu)    │
│  ├── AgentTaskRunner · MarkerExecutor · TaskPlan      │
│  ├── PermissionManager · ContextManager · McpManager  │
│  └── ProjectContext · CheckpointManager               │
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
│   ├── native-lib.cpp          (370+ lines) — PTY engine (forkpty, createSession, createSessionExec)
│   └── CMakeLists.txt          — CMake config
├── java/com/tunnel/terminal/
│   ├── TunnelApp.kt            (NEW Phase 49) — Application subclass (screen persistence)
│   ├── MainActivity.kt         (2400+ lines) — Entry point, UI wiring, Agent Mode
│   ├── TerminalUI.kt           (1600+ lines) — TabBar, TerminalScreenView, AIChatPanel, dialogs
│   ├── TerminalEmulator.kt     (880+ lines) — ANSI parser, screen buffer, scrollback, atomic snapshot
│   ├── ShellExecutor.kt        (310+ lines) — Local PTY session manager
│   ├── SshShellExecutor.kt     (370+ lines) — Remote SSH session manager + host key dialog
│   ├── ProotShellExecutor.kt   (NEW Phase 38) — Ubuntu via proot session manager
│   ├── ProotBootstrap.kt       (NEW Phase 38) — Download + extract Ubuntu rootfs
│   ├── TerminalSession.kt      (45 lines) — Common interface + environmentDescription
│   ├── TerminalJni.kt          (85 lines) — JNI bridge (createSession + createSessionExec)
│   ├── AIAgent.kt              (380+ lines) — SSE streaming + tool calls + session-aware prompt
│   ├── AiToolCall.kt           (460+ lines) — Tool parser + executor + permission + workspace sandbox
│   ├── AISettings.kt           (53 lines) — AI config + 13 presets
│   ├── MarkerExecutor.kt       (NEW Phase 37) — Marker-based command execution + ExecutionOutcome
│   ├── AgentTaskRunner.kt      (NEW Phase 47) — Autonomous AI task loop
│   ├── AgentScreen.kt          (NEW Phase 47) — Agent Mode UI
│   ├── SecureStorage.kt        (NEW Phase 41) — EncryptedSharedPreferences wrapper
│   ├── ProjectContext.kt       (NEW Phase 50) — Git/manifest/file-tree detection for AI
│   ├── CheckpointManager.kt    (NEW Phase 50) — File snapshot before AI write_file (undo)
│   ├── CommandBlock.kt         (390+ lines) — Block data + manager + incremental parse
│   ├── CommandPalette.kt       (278 lines) — Ctrl+K modal
│   ├── ContextManager.kt       (240+ lines) — @mentions parser + resolver
│   ├── DiffView.kt             (204 lines) — LCS diff + dialog
│   ├── FileExplorer.kt         (419 lines) — File browser + workspace dialog
│   ├── MarkdownText.kt         (333 lines) — Markdown renderer
│   ├── McpManager.kt           (270+ lines) — MCP server connections + management
│   ├── ModelFetcher.kt         (183 lines) — /models endpoint fetcher
│   ├── SmartAutocomplete.kt    (157 lines) — Command suggestions + voice input
│   ├── SnippetManager.kt       (90 lines) — Workflow snippet persistence
│   ├── StorageManager.kt       — SAF CRUD + MediaStore Downloads (Wave 19)
│   ├── SkillManager.kt         — AI Skills persist + inject (Wave 25)
│   ├── SkillsPanel.kt          — Skills CRUD UI (Wave 25)
│   ├── SessionTargetResolver.kt — Local/Ubuntu/SSH path map (Wave 23)
│   ├── TerminalLayoutMetrics.kt · TerminalSelectionHitTest.kt · TerminalFontZoom.kt
│   ├── PasteUtils.kt · ScrollbackSearch.kt · UrlOpenUtils.kt · UrlValidator.kt
│   ├── TaskPlanManager.kt · PtySessionBase.kt · TerminalSize.kt
│   ├── SshConnectDialog.kt · SyntaxHighlighter · ThemeManager · SystemInfo
│   ├── TerminalForegroundService · TunnelEditor · ImageHelper
│   ├── AgentWorkflow · WorkspaceManager · BookmarkStore · ChatExporter
│   └── SecureStorage · CommandHistoryStore · TranscriptExporter
├── assets/proot/
│   ├── proot                   — Binary proot (arm64, dari Termux)
│   ├── lib/                    — Shared libs (libtalloc.so.2, libandroid-shmem.so)
│   ├── VERSION                 — Version info
│   └── README.md               — Cara obtain binary proot
├── res/
│   ├── drawable/               — Launcher icons (vector)
│   ├── mipmap-anydpi-v26/      — Adaptive icon XML
│   └── values/                 — Theme, colors
└── AndroidManifest.xml         — Permissions, service, activity, TunnelApp
```

### Test Files (Phase 51 + Waves)
```
app/src/test/java/com/tunnel/terminal/
├── 03–07  TerminalEmulator, AiToolCall, Permission, Wave utils
├── 08–16  History/URL, export, bookmarks, IME, polish, select, find, Unicode, zoom
├── 18–25  Layout/IME, Wave20, selection hit-test, Ubuntu URL, Ubuntu paths, skills
```

### CI/CD (Phase 42)
```
.github/workflows/
├── build-apk.yml               — Basic APK build
├── build-debug.yml             — Debug APK build + artifact upload
└── build-release.yml           — Release APK build + sign + GitHub Releases
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
- Phase 41: Blocking dialog saat host key berubah (CRIT-02 fix) — `hostKeyChangeCallback`
- Phase 46: `environmentDescription` untuk AI context awareness

---

## 7.5. Linux Environment (Ubuntu via proot) — Phase 38-39

### Arsitektur

```
TunnelApp
  ├── Tab "Local"     → ShellExecutor → /system/bin/sh (Android shell)
  ├── Tab "SSH"       → SshShellExecutor → remote shell via JSch
  └── Tab "Ubuntu" 🐧 → ProotShellExecutor → proot + Ubuntu rootfs
```

PTY layer yang sama (`native-lib.cpp` → `forkpty()`) dipakai untuk semua tipe sesi. Sesi Ubuntu memanggil `createSessionExec()` (JNI baru) dengan argv proot, alih-alih `createSession()` yang hardcode `/system/bin/sh`.

### ProotBootstrap.kt

Mengelola instalasi Ubuntu rootfs (diperkuat **Wave 22**):
- Salin binary `proot` + shared libs dari assets ke `filesDir/linux/`
- Download rootfs Ubuntu Base 24.04 (arm64) di **`Dispatchers.IO`** (bukan Main — hindari NetworkOnMainThread)
- Multi-URL / multi-mirror + **HTTP Range resume** + **SHA256** verify
- Ekstrak via `/system/bin/tar` (toybox) dengan fallback extract path
- Setup DNS (`resolv.conf`), non-interactive apt (`DEBIAN_FRONTEND=noninteractive`)
- Validate `proot --version` sebelum tulis marker `.installed`
- Cek free storage sebelum mulai; progress dialog di UI thread

### SessionTargetResolver + Ubuntu AI (Wave 23)

- Tab Ubuntu: path guest default **`/root`** (bukan `/data/data/...` Android)
- Workspace app di-bind ke **`/mnt/workspace`** di dalam proot
- `write_file` / Agent `cd` / tool path memakai resolver agar AI tidak menulis ke path host yang salah di proot

### ProotShellExecutor.kt

Implements `TerminalSession` — hampir identik dengan `ShellExecutor`, bedanya:
- Panggil `createSessionExec()` dengan argv: `proot --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root /usr/bin/env -i ... /bin/bash --login`
- `LD_LIBRARY_PATH` set ke `baseDir/lib` supaya proot temukan `libtalloc.so.2`
- SECCOMP auto-retry: kalau sesi mati <2s, retry dengan `PROOT_NO_SECCOMP=1`
- Phase 46: `environmentDescription` = "Ubuntu 24.04 LTS via proot — apt-get & dpkg tersedia"
- Phase 46: `CountDownLatch` untuk readiness signal (tunggu byte pertama dari proot)

### Known Limitations (proot)

- `systemctl`/`service` tidak jalan (tidak ada systemd) — Phase 43: intercept + tampilkan workaround in-app
- Performa kompilasi C++ berat lambat (overhead ptrace)
- Beberapa device OEM crash di startup (SECCOMP) — auto-retry dengan `PROOT_NO_SECCOMP=1`

---

## 7.6. MarkerExecutor — Phase 37, 46, 48

### Cara Kerja

Setiap command AI dibungkus dengan marker unik:
```bash
{ cmd ; } ; ec=$?; echo "__TT_DONE_<counter>_<hex4>_<exitcode>__"
```

Marker di-parse dari output terminal → exit code tercapture → hasil dikirim balik ke AI.

### ExecutionOutcome (Phase 46)

Dual-layer timeout untuk bedakan "completed" vs "possibly waiting for input" vs "timed out":
- `Completed(result)` — marker ditemukan, exit code tercapture
- `PossiblyWaitingForInput(partialOutput, elapsedMs)` — idle >15s, kemungkinan nunggu input interaktif (mis. apt dialog)
- `TimedOut(partialOutput)` — max timeout (5min untuk apt) tercapai

### Phase 48 fix (A-5)

Marker ID sekarang counter + `SecureRandom` 4-byte hex — tidak predictable, praktis mustahil collide dengan output command.

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
| **32-34** | **Input fix + SSH TOFU** — input dobel, soft keyboard, text selection, SSH real fingerprint | +500/-200 |
| **35-36** | **Credit + Marker Foundation** — text selection, credit, marker-based execution foundation | +400/-100 |
| **37** | **Marker-based AI tool calling** — run_command dibungkus marker, exit code di-capture, @command: real | +600/-200 |
| **38-39** | **Linux Environment (Ubuntu via proot)** — createSessionExec JNI, ProotBootstrap, ProotShellExecutor, install dialog | +1500/-50 |
| **40** | **Audit V3 fixes** — 42 bugs (A1-A4, H1-H10, M1-M13) — input dobel, Ubuntu URL, text selection, MOTD | +600/-100 |
| **41** | **Security & Privacy** — EncryptedSharedPreferences (CRIT-01), SSH dialog (CRIT-02), .gitignore (CRIT-03), product flavors (CRIT-04) | +600/-60 |
| **42** | **Documentation & Distribution** — WIKI/USER_GUIDE update, GitHub Releases workflow, repo topics | +300/-50 |
| **43** | **Robustness** — Permission per-session (HIGH-05), libtalloc detection (MED-07), SECCOMP UI (LOW-03), systemctl intercept (LOW-04) | +200/-40 |
| **44** | **UX Quality** — Theme recolor (MED-01), pinch-zoom Block Mode (MED-02), PTY initial size (MED-04) | +200/-30 |
| **45** | **Realtime fixes** — Shell cwd fix (Bug #1), pseudo-command stick (Bug #2), proot readiness (Bug #3) | +120/-10 |
| **46** | **AI↔Ubuntu Integration** — MarkerExecutor fix + ExecutionOutcome, environmentDescription, non-interactive apt, AgentWorkflow unify | +440/-120 |
| **47** | **Storage sandbox + Agent Mode** — ToolExecutor.resolvePath() workspace sandbox, AgentTaskRunner autonomous loop, AgentScreen UI | +700/-30 |
| **48** | **Rendering fixes** — F-1 atomic snapshot, F-2 alt-screen resize, F-5 throttle 30fps, A-5 random marker, C-1 .gradle cleanup, D-1/D-2 WIKI | +150/-40 |
| **49** | **Scrollback + Persistence + MCP UI** — E-1 scrollback buffer 2000 lines, F-3 TunnelApp Application scope, D-4 MCP server management dialog | +220/-6 |
| **50** | **Project Context + Checkpointing** — B-5 ProjectContext (git/manifest/file tree), B-4 CheckpointManager (undo AI file edits) | +350/-10 |
| **51** | **Automated tests + BlockMode fix** — C-5 39 unit tests (TerminalEmulator, AiToolCall, PermissionManager), F-4 incremental parse | +320/-10 |
| **52** | **Agent Mode audit fixes** — Bug #1 approval dialog (risk-tagged commands), Bug #2 success detection (regex strengthen), Bug #3 Stop button cancel (job.cancel + active flag) | +180/-30 |
| **53-57** | **Text selection refinement + terminal resize + verification audit + edit_file tool + SessionTargetResolver** | +1030/-260 |
| **58** | **TaskPlanManager (plan/act/observe/verify) + SFTP for SSH file I/O** — Plan loop immune 20-msg limit, ChannelSftp untuk remote file ops | +1200/-80 |
| **59** | **Native API tool-calling (B-1) + AGP/Kotlin upgrade (C-2)** — `tools`+`tool_choice` API parameter, AGP 8.5.2 + Kotlin 2.0.21 + Compose Compiler Plugin + Gradle 8.9 | +450/-50 |
| **60** | **Audit fixes: HTTPS enforcement, MCP schema validation, Ubuntu download reliability, reflection removal** — Bug #2 (HTTPS), Bug #4 (MCP), C-1 to C-5 (Ubuntu download) | +600/-100 |
| **Wave 1-2** | **Critical stability + security** — scrollback, agent, sandbox, HOME path, SSH TOFU before auth, rootfs SHA256 verify, secure storage fail-closed | +2000/-200 |
| **Wave 3-4** | **UX + agent tools** — IME input, block live updates, palette recents, diff apply, reaper, autocomplete, grep_content, safe read/delete, output buffer | +1500/-300 |
| **Wave 5-6** | **PtySessionBase + Anthropic + UX** — shared PTY core, Anthropic Messages API, wide-char, AiMetrics, explorer cd, editor confirm, SFTP limits, agent clarify, MCP ping | +1800/-400 |
| **Wave 7-9** | **Release polish + history + export** (v7.3.0-v7.5.0) — scroll, permissions, tests, persistent history, export transcript, URL validate, chat export, snippet type-in, SSH host keys | +2200/-500 |
| **Wave 10-11** | **Tabs + bookmarks + IME fix** (v7.6.0-v7.6.1) — tab rename, bookmarks, copy-output, keep-screen-on, IME fix typed chars vanish | +1500/-300 |
| **Wave 12-13** | **Terminal max polish + scrollback select** (v7.7.0-v7.8.0) — safe paste, DECCKM, ExtraKeys, scroll, render, scrollback select/copy, key-repeat, split activate, PTY size | +2500/-800 |
| **Wave 14** | **Find + mouse + reconnect** (v7.9.0) — find scrollback, open-url, mouse/wheel mode 1000/1006, reconnect keep history, F1-F4 + ^A/^E | +1200/-300 |
| **Wave 15-16** | **LazyColumn + Unicode + font zoom** (v8.0.0-v8.0.1) — LazyColumn virtualized scrollback, Unicode code-points, compact ExtraKeys, font zoom pinch fix | +1800/-600 |
| **Wave 17** | **AI chat UX polish** (v8.1.0) — Stop stream, bubble + Copy/Retry, empty chips, Auto-Pilot progress, Agent scroll/pause, API key mask, max tokens, FAB AI | +1500/-400 |
| **Wave 18** | **Terminal display clip + IME** (v8.1.1) — line metrics, no clip on zoom, IME wipe guard | +400/-100 |
| **Wave 19** | **Device storage** (v8.2.0) — SAF DocumentFile + MediaStore Downloads + `setup-storage` / `storage-*` | +800/-50 |
| **Wave 20–20b** | **Terminal polish + selection** (v8.2.x) — dirty trail, HOME/END, volume focus; LazyList layoutInfo hit-test select/copy | +900/-200 |
| **Wave 21** | **AI side panel** (v8.3.0) — Row layout Chat/Flow/Skill/Set di kanan; terminal kiri tetap terlihat | +600/-300 |
| **Wave 22** | **Ubuntu download** (v8.3.1) — download di Dispatchers.IO, multi-mirror, Range resume, SHA256, extract fallback | +500/-150 |
| **Wave 23** | **Ubuntu AI paths** (v8.3.2) — SessionTargetResolver `/root` guest, Agent cd `/root`, bind `/mnt/workspace` | +400/-100 |
| **Wave 24** | **AI chat paste** (v8.3.3) — paste clipboard di chat/agent, multi-line, snippet `>_` terminal | +200/-30 |
| **Wave 25** | **AI Skills** (v8.5.0) — SkillManager CRUD, scope always/chat/agent/local/ubuntu/ssh, keyword trigger, inject AIAgent + AgentTaskRunner | +700/-50 |

**Total: 68 Kotlin sources + NDK C++ + 34 unit test files · version 9.4.0 (versionCode 74) · 210 commits**

---

## 20. Known Limitations

1. **No scrollback buffer** — ✅ FIXED (Phase 49 + Wave 15) — ring buffer 2000 baris, LazyColumn virtualized, Unicode code-points
2. **PTY initial 80×24** — ✅ FIXED (Phase 44 + Wave 13) — TerminalSize.fromDisplay() hitung dari display metrics
3. **@command: real implementation** (Phase 37+) — command dieksekusi via MarkerExecutor, output + exit code di-capture real-time
4. **SSH host key dialog** — ✅ FIXED (Phase 41 + Wave 2) — TOFU before auth, dialog blocking dengan fingerprint lama vs baru
5. **Agent Workflows UI builder** — workflows hanya bisa dibuat via code. Agent Mode (Phase 47) adalah alternatif yang lebih fleksibel
6. **MCP server management UI** — ✅ FIXED (Phase 49) — McpServerManagementDialog dengan form add/remove server
7. **EncryptedSharedPreferences** — ✅ FIXED (Phase 41 + Wave 2) — API key + SSH creds + MCP keys AES256-GCM encrypted, fail-closed (no plaintext fallback)
8. **Tool-calling text/regex** — ✅ FIXED (Phase 59, B-1) — native API `tools`+`tool_choice` parameter untuk provider yang support. Text-tag `<tool_call>` parsing tetap sebagai bridge/fallback untuk provider non-native.
9. **AGP + Kotlin version** — ✅ FIXED (Phase 59, C-2) — AGP 8.5.2 + Kotlin 2.0.21 + Gradle 8.9 + Compose Compiler Plugin
10. **Rendering atomicity** — ✅ FIXED (Phase 48) — getRenderState() atomic snapshot
11. **Alt-screen resize sync** — ✅ FIXED (Phase 48) — resize() resize KETIGA buffer (screen + altScreen + mainScreen)
12. **Screen buffer persistence** — ✅ FIXED (Phase 49) — TunnelApp Application scope, survive Activity recreate
13. **Block Mode parse divergen** — ✅ FIXED (Phase 51) — incremental parse
14. **Output throttle** — ✅ FIXED (Phase 48) — screenDirty di-throttle ke ~30fps (33ms)
15. **Automated tests** — ✅ FIXED (Phase 51 + Waves 6–25 + v9.0-v9.2) — **27 test files** (03–32): emulator, tools, permission, IME, selection, Ubuntu URL/paths, AI Skills, storage, NetworkPolicy, AiMetrics, MarkerExecutor, SyntaxHighlighter, ContextManager
16. **Agent Mode approval/success/Stop bugs** — ✅ FIXED (Phase 52) — risk-tagged command approval dialog, success detection regex strengthen, Stop button via job.cancel()
17. **SFTP for SSH file I/O** — ✅ FIXED (Phase 58) — ChannelSftp untuk read/write/list file di server SSH, mkdir recursive (Wave 6)
18. **TaskPlanManager context limit** — ✅ FIXED (Phase 58) — plan/act/observe/verify loop, plan disimpan terpisah dari conversation history
19. **Ubuntu download reliability** — ✅ FIXED (Phase 60 + **Wave 22**) — IO thread (bukan Main), multi-URL, Range resume, SHA256, NonCancellable + extract fallback
20. **HTTPS enforcement** — ✅ FIXED (Phase 60, Bug #2) — reject HTTP untuk provider eksternal (kecuali localhost Ollama/LM Studio)
21. **MCP schema validation** — ✅ FIXED (Phase 60, Bug #4) — try-catch + basic schema check (ensure type + properties)
22. **Anthropic Native API** — ✅ FIXED (Wave 5) — apiStyle: anthropic, Claude via Messages API
23. **Wide char / Unicode** — ✅ FIXED (Wave 15) — code-points + combining marks, CJK width 2, emoji width 2
24. **Font zoom** — ✅ FIXED (Wave 16) — pinch-zoom gesture-local, 0.5sp snap, range 8-28sp
25. **Device storage ke Download** — ✅ FIXED (Wave 19) — SAF + MediaStore + `storage-*` (bukan hanya app-private)
26. **Text selection offset** — ✅ FIXED (Wave 20b) — hit-test via LazyList layoutInfo
27. **AI menutupi terminal** — ✅ FIXED (Wave 21) — side panel kanan, terminal tetap visible
28. **AI path di Ubuntu proot** — ✅ FIXED (Wave 23) — guest `/root`, workspace bind `/mnt/workspace`
29. **Paste di chat AI** — ✅ FIXED (Wave 24) — clipboard paste + multi-line + terminal snippet
30. **AI Skills system** — ✅ FIXED (Wave 25) — CRUD + scope + inject chat/agent/all sessions

### Masih berlaku (bukan bug)

| Limitasi | Catatan |
|---|---|
| `systemctl` di proot | Tidak ada systemd — jalankan daemon manual |
| Compile C++ berat di proot | Overhead ptrace — cocok tool/dev, kurang untuk build besar |
| Play Store + proot | Flavor `full` untuk GitHub; `playstore` tanpa proot |
| Agent workflow UI builder | Masih code-only; pakai Agent Mode + Skills sebagai alternatif |

---

## Catatan Penutup

Tunnel Terminal **v9.4.0 (Phase 6)** melanjutkan Phase 1–60 + Wave 1–25 + v8.5.0–v9.4.0: terminal AI-native dengan **AI Skills**, **side panel Copilot**, storage perangkat (SAF/Download), Ubuntu proot yang andal, path AI sinkron dengan guest Linux, paste chat, seleksi text akurat, **34 unit test files**, NetworkPolicy centralization, SSH CompletableDeferred, MCP parallel discovery, Ctrl+Tab/Ctrl+N keyboard shortcuts, dan **49 fixes total** across 6 improvement phases (versionCode **73**).

Codebase telah melalui multiple comprehensive audit plus 25 waves polish (stabilitas, security, UX terminal, AI, Ubuntu, storage).

**Status backlog:** B-1 / B-4 / B-5 / C-2 / C-5 ✅ · Wave 19–25 (storage, selection, side panel, Ubuntu, paste, skills) ✅.

Untuk panduan cara penggunaan, lihat [USER_GUIDE.md](USER_GUIDE.md).  
Untuk release notes + APK, lihat [GitHub Release v9.4.0](https://github.com/NanoMindExplorer/tunnel-terminal/releases/tag/v9.4.0).
