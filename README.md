# Tunnel Terminal

**Tunnel Terminal** adalah aplikasi terminal Android tingkat lanjut yang menggabungkan kekuatan mesin C/C++ NDK (Pseudo-Terminal) dengan AI Copilot Multi-Provider untuk pengembang modern.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Multi%20Provider%20%2B%20Vision-cyan)
![Version](https://img.shields.io/badge/version-4.0.0--phase21-blue)

## Fitur Utama

### 1. True Linux Terminal (C++ NDK PTY)
Tidak menggunakan trik Java `Runtime.exec()`. Tunnel Terminal menggunakan `forkpty()` dari C/C++ untuk membuat sesi terminal asli di kernel Linux Android. Mendukung penuh aplikasi TUI seperti `vim`, `nano`, dan `htop`.

### 2. AI Auto-Pilot (Agentic Workflow) with Streaming
Alih-alih sekadar memberi saran, AI bisa menjalankan rangkaian perintah secara otomatis. Cukup minta *"Setup server Node.js Express dan jalankan"*, AI akan menginstal, membuat file, dan menjalankannya secara berurutan. **Phase 17:** Auto-Pilot cerdas — menunggu prompt shell muncul sebelum lanjut ke perintah berikutnya, mendeteksi error mid-sequence, dan memberi laporan setiap step. **Phase 18:** Response AI di-stream token-by-token via SSE (Server-Sent Events) sehingga terasa instan. AI juga ingat seluruh percakapan (multi-turn memory, max 20 pesan) untuk follow-up question.

### 3. Multi-Provider AI (OpenAI-Compatible)
Pengguna bebas memilih provider AI favorit mereka. Semua provider menggunakan OpenAI-compatible `/chat/completions` endpoint:
- **OpenAI** (gpt-4o-mini, gpt-4o, dll.)
- **DeepSeek** (deepseek-chat, deepseek-coder)
- **Groq** (Llama 3 8B/70B, Mixtral)
- **OpenRouter** (akses Claude, Gemini, dan ratusan model lain)
- **Gemini** (via Google's OpenAI-compatible endpoint)
- **Anthropic Claude** (via OpenAI-compatible endpoint)
- **Local Ollama** (llama3, mistral, dll.)
- **Local LM Studio** (model lokal apapun)

### 4. UX Mobile-First yang Cerdas
- **Built-in Code Editor:** Ketik `open <file>` untuk membuka UI Editor ramah sentuhan dengan line numbers, async loading, dan error handling.
- **Volume Key History:** Gunakan tombol Volume Up/Down untuk navigasi riwayat perintah (per-tab).
- **Pinch to Zoom:** Cubit layar untuk mengatur ukuran font.
- **Smart Modifier Keys:** Tombol CTRL dan ALT yang berfungsi penuh (Ctrl+C untuk kill process).
- **Session Lifecycle Guard:** Terminal tidak akan membeku saat diketik `exit`.
- **Extra Keys Bar:** ESC, TAB, CTRL, ALT, arrows, HOME, END, PGUP, PGDN, BKSP, DEL + 25 simbol umum.
- **Cursor Rendering:** Cursor block ditampilkan di posisi aktual (vim/htop friendly).
- **Back Button:** Tutup drawer/editor dulu sebelum exit app.

### 5. Storage Access Framework (Phase 17 - Real Implementation)
Ketik `setup-storage` untuk membuka SAF picker dan membuat jembatan ke `/sdcard` atau folder pilihan Anda. URI persisten disimpan dengan `takePersistableUriPermission` sehingga tetap valid setelah reboot. Anda bisa mengerjakan proyek yang bisa diakses langsung oleh aplikasi File Manager HP atau dibagikan ke PC.

Perintah terkait:
- `setup-storage` — Buka picker folder
- `storage-status` — Cek status konfigurasi storage
- `storage-reset` — Reset konfigurasi storage

### 6. System Info MOTD (Phase 17)
Saat sesi shell baru dibuat, MOTD dinamis menampilkan:
- Android version & API level
- Device manufacturer & model
- CPU architecture & core count
- Memory usage (used/total)
- Storage free/total
- Network IP address
- System uptime

Ketik `system-info` kapan saja untuk menampilkan ulang.

### 7. Built-in Commands (Phase 17 - All Functional)
| Command | Deskripsi |
|---|---|
| `help` | Tampilkan menu bantuan lengkap |
| `clear` | Bersihkan layar terminal (lokal, tidak kirim ke shell) |
| `setup-storage` | Bridge ke /sdcard via Storage Access Framework |
| `storage-status` | Cek status konfigurasi storage |
| `storage-reset` | Reset konfigurasi storage |
| `system-info` | Tampilkan info sistem (MOTD) |
| `open <file>` | Edit file di Tunnel Editor UI |

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Jetpack Compose UI (MainActivity / TerminalUI) │
├─────────────────────────────────────────────────┤
│  ShellExecutor (Kotlin)                         │
│   - Per-tab command history                     │
│   - ANSI strip for AI context                   │
│   - PTY lifecycle management                    │
├─────────────────────────────────────────────────┤
│  TerminalJni (Kotlin ↔ C++ bridge)              │
│   - createSession / write / resize / close      │
│   - killSession / isAlive (Phase 17)            │
├─────────────────────────────────────────────────┤
│  native-lib.cpp (C++ NDK)                       │
│   - forkpty() → /system/bin/sh                  │
│   - SIGTERM → SIGKILL → waitpid (no zombie)     │
│   - ioctl(TIOCSWINSZ) for SIGWINCH              │
└─────────────────────────────────────────────────┘
```

## Build

Membutuhkan:
- Android Studio Hedgehog (2023.1.1) atau newer
- Android SDK 34
- NDK 25.1.8937393 (atau newer)
- CMake 3.22.1

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Phase History

| Phase | Highlight |
|---|---|
| 1-6 | Initial Compose UI, multi-tab, AI Copilot, ANSI parser, foreground service, MOTD |
| 7 | Major architecture shift: C/C++ NDK PTY engine (forkpty) |
| 8 | True terminal emulator with screen buffer & matrix rendering |
| 9 | AI Auto-Pilot (agentic sequential execution) |
| 10 | Storage Access Framework (advertised; **real implementation in Phase 17**) |
| 11 | Dynamic UI resizing (SIGWINCH), pinch-to-zoom |
| 12 | Built-in Tunnel Editor |
| 13 | Modifier key state machine (Ctrl+C, Alt) |
| 14 | Hardware volume key history navigation |
| 15 | Session lifecycle guard (anti-freeze on exit) |
| 16 | Built-in help command & open-source README |
| **17** | **Major Bug Fix Release** — see below |
| **18** | **AI Streaming SSE + Multi-turn Memory + Theme Picker** — see below |
| **19** | **Free AI Provider + Image Vision + File Explorer + Workspace Sessions + Icon Redesign** — see below |
| **19.5** | **Input Reliability + Mouse Support** (fix: cannot type in terminal) |
| **20** | **Comprehensive Bug Fix + Compose BOM Upgrade** — see below |
| **21** | **Thread Safety + SSH Client + Syntax Highlighting + Split Pane** — see below |

## Phase 21 — Production Quality + SSH + Syntax Highlighting + Split Pane

This release closes all 4 deferred thread safety bugs from Phase 20 + adds 3 major features previously deferred from Phase 19.

### Thread Safety (4 fixes — production quality)

1. **TerminalEmulator thread safety**: `process()` + `resize()` + `getScreen()` now use `synchronized(lock)`. `getScreen()` returns a **snapshot copy** of the screen array — Compose never reads array being mutated. Added `getCursorState()` for thread-safe cursor reads.

2. **ShellExecutor thread safety**: `outputBuffer` protected by `outputLock` (readLoop writes, main reads via `getCleanOutput()`). `writeRaw()` protected by `writeLock` — no more concurrent JNI writes from main + Auto-Pilot threads.

3. **PID recycling fix** (C++ `killSession`): Before sending kill signal, `waitpid(pid, &status, WNOHANG)` checks if pid is still our child. If `waitpid` returns -1 (ECHILD), process already reaped or PID recycled → skip kill (avoid killing wrong process).

4. **Dead code removal**: `AIAgent.askAI()` (non-streaming) removed — never called, only `askAIStreaming()` used. `TerminalJni.isAlive()` (JNI) removed — dead code, used `kill(pid, 0)` which has PID recycling risk.

### SSH Client (JSch)

5. **SshShellExecutor**: Full SSH terminal session using JSch library. Implements `TerminalSession` interface — works interchangeably with local PTY in TabBar. Features:
   - Password authentication
   - Private key authentication (with passphrase)
   - PTY type "xterm-256color" + resize
   - Read loop (same pattern as ShellExecutor)
   - Thread-safe (outputLock + writeLock)
   - Graceful disconnect

6. **SshConnectDialog**: Connection form with host/port/username/password (or private key path + passphrase). Validation + error messages.

7. **TabBar integration**: 🔌 button opens SSH dialog. New SSH tab appears in TabBar alongside local PTY tabs. Tap tab to switch between local/SSH sessions.

8. **TerminalSession interface**: Common interface for `ShellExecutor` (local PTY) and `SshShellExecutor` (remote SSH). Both can coexist in the same tab list.

### Syntax Highlighting (regex-based, NOT tree-sitter)

9. **SyntaxHighlighter**: Regex-based highlighting for 8 languages:
   - **Kotlin** (.kt, .kts, .java) — keywords, strings, comments, numbers, annotations, functions, types
   - **Python** (.py) — keywords, strings (f-strings, triple), comments, decorators, functions
   - **JavaScript/TypeScript** (.js, .ts, .mjs) — keywords, template literals, comments, functions
   - **Shell/Bash** (.sh, .bash) — keywords, variables ($VAR), strings, comments
   - **JSON** (.json) — keys, strings, numbers, booleans
   - **XML/HTML** (.xml, .html, .svg) — tags, attributes, strings, comments
   - **YAML** (.yml, .yaml) — keys, strings, comments, booleans
   - **Markdown** (.md) — headers, code blocks, links, bold

10. **Theme-aware colors**: Syntax colors derived from terminal ANSI palette — each theme produces different syntax colors.

11. **VisualTransformation**: `SyntaxHighlightTransformation` class applies highlighting to `BasicTextField` without changing underlying text. Editor input stays plain, display is highlighted.

12. **TunnelEditor integration**: `open <file>` now shows syntax-highlighted code. Language auto-detected by file extension.

**Why not tree-sitter?** Tree-sitter requires NDK grammar builds (+2-5MB per language). Regex-based is ~400 lines, 0 bytes APK overhead, sufficient for mobile editing.

### Split Pane (tmux-style)

13. **Split pane mode**: ⬡ button in TabBar toggles split mode. Two terminals side by side:
    - Left pane: active terminal (with input)
    - Right pane: second tab terminal (view-only, tap to switch)
    - Divider between panes

14. **Tab switching**: Tap right pane → switches active tab to that pane. Type in left pane → input goes to active terminal.

### Additional Changes

15. **Compose BOM**: Already upgraded in Phase 20 (2024.02.00)
16. **JSch dependency**: `com.github.mwiede:jsch:0.2.17` (maintained fork)
17. **Material icons**: Already added in Phase 19

### Files Changed (Phase 21)
- **NEW**: `TerminalSession.kt` — common interface
- **NEW**: `SshShellExecutor.kt` — SSH terminal session (JSch)
- **NEW**: `SshConnectDialog.kt` — SSH connection form UI
- **NEW**: `SyntaxHighlighter.kt` — regex-based syntax highlighting (8 languages)
- **MODIFIED**: `TerminalEmulator.kt` — synchronized lock + snapshot getScreen + getCursorState
- **MODIFIED**: `ShellExecutor.kt` — outputLock + writeLock + implements TerminalSession
- **MODIFIED**: `TerminalJni.kt` — removed isAlive (dead code)
- **MODIFIED**: `native-lib.cpp` — killSession PID recycling fix + removed isAlive
- **MODIFIED**: `AIAgent.kt` — removed askAI dead code
- **MODIFIED**: `TunnelEditor.kt` — syntax highlighting via VisualTransformation
- **MODIFIED**: `TerminalUI.kt` — TabBar SSH + Split buttons
- **MODIFIED**: `MainActivity.kt` — TerminalSession list + SSH dialog + split pane
- **MODIFIED**: `build.gradle.kts` — JSch dependency + version bump

### Version
- `versionCode`: 7 → 8
- `versionName`: `3.3.0-phase20-comprehensive-fix` → `4.0.0-phase21-ssh-syntax-split`

## Phase 20 — Comprehensive Bug Fix Release

Deep audit of all 17 source files (6100+ lines) found 22 bugs. This release fixes 12 critical/high bugs + upgrades Compose BOM.

### Critical Fixes

1. **Cursor double-render** (TerminalUI.kt): Cursor position showed DOUBLE character ("ll" instead of "l" with cursor). Root cause: char appended with normal style THEN appended again with cursor style. Fix: use if/else — cursor style for cursor position, normal style otherwise. Also use theme colors instead of hardcoded white/black.

2. **fd double-close** (ShellExecutor.kt): `ParcelFileDescriptor.adoptFd(masterFd)` takes fd ownership, but `destroy()` called BOTH `TerminalJni.close(masterFd)` AND `pfd?.close()` — double-close on same fd. Fix: only close `pfd` (which owns the fd).

3. **Alt screen restore loses main screen** (TerminalEmulator.kt): Entering alt screen overwrote `screen` reference without saving. Exiting created BLANK screen — vim/less exit showed empty terminal. Fix: save `mainScreen` before entering alt, restore on exit.

4. **destroy() blocks main thread** (MainActivity.kt): `onDestroy()` called `destroy()` on main thread — each tab blocks ~400ms (Thread.sleep + join). 5 tabs = 2s ANR. Fix: run destroy on background thread.

### High Priority Fixes

5. **C++ write() partial write** (native-lib.cpp): `write(fd, bytes, len)` may write less than `len` bytes (especially for large writes or slow PTY). Fix: loop until all bytes written, handle EINTR.

6. **TERM env not set** (native-lib.cpp): `execl("/system/bin/sh")` without setting `TERM` env. TUI apps (vim, htop, less) couldn't detect terminal type → no colors. Fix: `setenv("TERM", "xterm-256color", 1)` + `TERM_PROGRAM` + `HOME` before execl.

7. **Volume keys intercept always** (MainActivity.kt): Volume keys intercepted globally even when AI drawer / editor / file explorer was open. User couldn't adjust media volume. Fix: only intercept when terminal is focused (no overlay open).

8. **runAutoPilot outputBefore index invalid** (MainActivity.kt): `outputBefore` was length-based. If outputBuffer was trimmed (capped at 4000 chars), `substring(outputBefore)` threw `StringIndexOutOfBoundsException`. Fix: capture snapshot String, use `startsWith` + `substring` safely.

9. **auto-error-detection race** (MainActivity.kt): LaunchedEffect could add error-detection message during AI streaming, shifting `streamingIdx`. Fix: guard with `isProcessingAI` + check existing error notification (avoid duplicate).

### Compose BOM Upgrade

10. **compose-bom: 2023.08.00 → 2024.02.00** (build.gradle.kts):
    - material3 1.1.1 → 1.2.0+ (HorizontalDivider available, better stability)
    - Security patches
    - Performance improvements

### destroy() Reliability

11. **destroy() order fix** (ShellExecutor.kt): Old order: interrupt thread → kill child → close fd. Problem: `Thread.interrupt()` doesn't unblock `FileInputStream.read()` on all platforms. Fix: close pfd FIRST (unblocks read() → readLoop exits naturally) → then interrupt + join (300ms) → then kill child.

12. **Reduced destroy() blocking time**: Thread.sleep(100) → 50ms, join(500) → 300ms. Combined with pfd-close-first, destroy is now ~350ms per tab (was ~600ms).

### Files Changed (Phase 20)
- **MODIFIED**: `native-lib.cpp` — TERM env + write() partial write loop
- **MODIFIED**: `TerminalEmulator.kt` — alt screen save/restore (mainScreen field)
- **MODIFIED**: `ShellExecutor.kt` — destroy() order fix + no double-close
- **MODIFIED**: `MainActivity.kt` — volume keys focus guard + onDestroy async + runAutoPilot snapshot + auto-error guard
- **MODIFIED**: `TerminalUI.kt` — cursor if/else (no double-render) + theme cursor colors
- **MODIFIED**: `build.gradle.kts` — compose-bom upgrade + version bump
- **MODIFIED**: `README.md` — Phase 20 section

### Version
- `versionCode`: 6 → 7
- `versionName`: `3.2.0-phase19-providers-vision-explorer` → `3.3.0-phase20-comprehensive-fix`

### Bugs Found But Deferred
- Thread safety: `emulator.screen` + `outputBuffer` accessed from readLoop + main without synchronization. Fix requires copy-on-read or lock — complex, deferred to Phase 21.
- `writeRaw`/`clearScreen` not synchronized (concurrent JNI writes from main + background).
- `AIAgent.askAI` (non-streaming) is dead code — still exists but not called.
- `isAlive` uses `kill(pid, 0)` which has PID recycling risk.

---

## Phase 19.5 — Input Reliability + Mouse Support

User reported: cannot type anything in terminal (since pre-Phase 17). Deep audit found 21 bugs in input handling. This release fixes the critical input bugs + adds mouse support.

Key fixes:
- BasicTextField delta tracking (IME confused by value reset)
- FocusRequester auto-focus on tab switch
- handleKeyEvent() handles ALL special keys at KeyDown (prevent double-fire)
- Physical keyboard: Enter, Backspace, Tab, Arrows, Home/End, PageUp/Down, Delete, F1-F4, Ctrl+key combos, Alt+key
- Mouse: tap-to-focus, scroll wheel
- Per-tab input buffer (currentCommandBuffer moved to ShellExecutor)
- TerminalEmulator partial ANSI sequence handling (pendingBuffer)
- ShellExecutor: readThread tracking + interrupt, close FileInputStream, flush emulator

---

## Phase 19 — Free AI Provider + Image Vision + File Explorer + Workspace Sessions

This release focuses on **provider freedom**, **multimodal AI**, **file management UX**, dan **launcher icon redesign**.

### Launcher Icon Redesign
Icon baru dengan desain yang merepresentasikan "tunnel + terminal + AI":
- Background: dark gradient (deep purple to black) dengan tunnel ring effect
- Foreground: terminal window frame dengan title bar (3 traffic-light dots)
- Inside window: neon green `>` prompt + white cursor `_` block
- AI accent: 3 connected cyan nodes (bottom-right, Auto-Pilot agentic workflow)
- Monochrome variant untuk Android 13+ themed icons
- Added `ic_launcher_round.xml` untuk round icon

### Free AI Provider + All Models
User sekarang bisa:
- **Custom provider**: tambah provider apapun dengan baseUrl + apiKey + model bebas (tidak terikat preset)
- **Fetch model list**: tombol "🔄 Fetch Models" panggil endpoint `/models` untuk ambil daftar model tersedia
- **Searchable dropdown**: list model dengan vision indicator (👁) dan owner
- **13 preset providers** (naik dari 6): OpenAI, DeepSeek, Groq, OpenRouter, Gemini, Anthropic, **Mistral**, **Together AI**, **Fireworks AI**, **Perplexity**, Ollama, LM Studio, Custom
- **Vision capability detection**: heuristic by model name (gpt-4o, gemini-1.5, claude-3, llama-3.2-vision, pixtral, qwen-vl, phi-3-vision, generic "vision"/"vl")
- **Popularity sorting**: model populer (gpt-4o-mini, claude-3-5-sonnet, gemini-1.5-flash) muncul di atas

### AI Image Vision (Phase 19)
- **Image picker**: tombol 📎 di input bar chat AI, support multi-select
- **Gallery/camera**: pakai `ActivityResultContracts.GetMultipleContents()` dengan mime `image/*`
- **Auto-compress**: scale to max 1024px + JPEG quality 85 + base64 encode (~33% overhead)
- **Pending images preview**: thumbnail chips dengan remove button
- **Vision capability warning**: kalau model tidak support vision, tampilkan warning + saran pilih model vision
- **Multi-modal message format**: content sebagai array of parts (text + image_url) untuk OpenAI-compatible vision API
- **Auto-fallback prompt**: kalau user kirim image tanpa text, otomatis isi "Tolong analisa gambar ini."
- **Models tested**: gpt-4o, gpt-4o-mini, gemini-1.5-flash/pro, claude-3-5-sonnet, claude-3-haiku, llama-3.2-11b-vision, pixtral, qwen-vl

### File Explorer Drawer (Phase 19)
- **Sidebar browser**: tombol 📁 di tab bar buka file explorer dialog
- **Navigate folders**: tap folder untuk masuk, tombol ↑ untuk parent, 🏠 untuk home
- **File type icons**: Folder (blue), Code files (green), Image files, Generic files
- **File size display**: B/KB/MB/GB
- **Tap file**: buka di TunnelEditor (file) atau cd ke dir (folder navigate juga kirim `cd`)
- **Refresh button**: reload current directory
- **Permission-aware**: error message kalau permission denied
- **Initial dir**: `~/home` (app sandbox home), bisa navigate ke /sdcard jika setup-storage sudah

### Workspace Sessions (Phase 19)
- **Save tab state**: tombol 💾 di tab bar buka workspace dialog
- **Named sessions**: simpan dengan nama bebas (max 20 sessions)
- **Tab count + working dirs**: di-snapshot saat save (working dir di-parse dari prompt)
- **Restore**: tutup semua tab, buat ulang sesuai session, kirim `cd <dir>` ke setiap tab
- **Delete sessions**: tombol hapus per session
- **Timestamp display**: kapan session di-save
- **Limitations**: tidak save shell env vars, aliases, atau command history (per-tab history milik ShellExecutor, tidak dipersist)

### Additional Improvements
- **AISettings.supportsVision**: flag baru untuk track vision capability
- **13 preset providers** (up from 6) + Custom
- **material-icons-extended** dependency untuk FileExplorer icons
- **ImageHelper.kt**: utilitas compress + base64 encode
- **ModelFetcher.kt**: fetch + parse /models endpoint
- **WorkspaceManager.kt**: persist sessions ke SharedPreferences
- **FileExplorer.kt**: composable + WorkspaceSessionDialog

### Files Changed (Phase 19)
- **NEW**: `ModelFetcher.kt` — fetch model list dari /models endpoint
- **NEW**: `ImageHelper.kt` — image compress + base64 encode
- **NEW**: `WorkspaceManager.kt` — workspace session persistence
- **NEW**: `FileExplorer.kt` — FileExplorerPanel + WorkspaceSessionDialog composable
- **NEW**: `ic_launcher_monochrome.xml` — Android 13+ themed icon
- **NEW**: `ic_launcher_round.xml` — round icon variant
- **REWRITTEN**: `ic_launcher_background.xml` — tunnel ring + gradient
- **REWRITTEN**: `ic_launcher_foreground.xml` — terminal window + AI nodes
- **MODIFIED**: `AISettings.kt` — supportsVision flag + 13 presets + Custom
- **MODIFIED**: `AIAgent.kt` — ChatMessage.images field + multi-modal message format
- **MODIFIED**: `MainActivity.kt` — wire image picker, model fetcher, file explorer, workspace
- **MODIFIED**: `TerminalUI.kt` — AIChatPanel signature + image preview + model picker UI + theme support
- **MODIFIED**: `AndroidManifest.xml` — ic_launcher_round + READ_MEDIA_IMAGES
- **MODIFIED**: `build.gradle.kts` — material-icons-extended + version bump

### Version
- `versionCode`: 5 → 6
- `versionName`: `3.1.0-phase18-streaming-themes` → `3.2.0-phase19-providers-vision-explorer`

### Deferred to Phase 20+
Fitur yang diminta tapi di-defer karena kompleksitas (butuh native lib / UI restructure major):
- **SSH Client** (JSch/libssh2) — feasible but ~1000+ lines + dedicated UI
- **Syntax Highlighting Editor** (Tree-sitter) — butuh NDK grammar builds, very heavy
- **Split Pane** (tmux-style) — UI restructure major

---

## Phase 18 — AI Streaming + Multi-turn Memory + Theme Picker

This release focuses on AI UX polish and personalization. Three complementary features bundled together.

### AI Streaming via SSE (Server-Sent Events)
- **Token-by-token response**: AI responses appear progressively as the model generates them, no more waiting for full response
- **SSE parsing**: `AIAgent.askAIStreaming()` returns `Flow<String>` that emits content deltas
- **Streaming cursor**: Blinking `▋` cursor indicator while streaming
- **Auto-scroll**: Chat view auto-scrolls to bottom during streaming
- **Stream cancellation**: If user navigates away or AI errors mid-stream, partial content is preserved
- **Provider compatibility**: Works with all OpenAI-compatible providers that support `stream: true` (OpenAI, DeepSeek, Groq, OpenRouter, Gemini OpenAI-compat, Anthropic OpenAI-compat, Ollama)
- **Header `Accept: text/event-stream`** set on streaming requests
- **Read timeout 0** (unlimited) for streaming, normal timeout for non-streaming

### Multi-turn Conversation Memory
- AI now remembers the full conversation history (up to 20 messages) within a session
- Ask follow-up questions like "what about for Python?" and AI understands the context
- `ChatMessage` gained `conversationRole` field (system/user/assistant) for proper OpenAI API format
- Messages filtered before sending: error messages, streaming-in-progress, and pure-command messages excluded from history (avoid confusing the AI)
- **Clear chat button** (🗑) to reset conversation memory when starting a new task

### Theme Picker (6 Themes)
Six complete themes with full ANSI 16-color palette + UI color scheme:
- **Matrix** (default) — signature green-on-black
- **Dracula** — popular dark purple/pink
- **Solarized Dark** — ergonomic dark blue
- **Monokai Pro** — classic Sublime Text theme
- **Nord** — Arctic north-bluish
- **Tokyo Night** — modern dark blue inspired by Tokyo city lights

Each theme defines: terminal background/foreground/cursor, 16-color ANSI palette (8 normal + 8 bright), and UI colors (drawer bg, surface, accent, text, muted). Themes are applied live to:
- New terminal cells (existing cells keep their assigned colors — type `clear` for full refresh)
- 256-color palette (0-15 use theme palette, 16-255 stay standard)
- AI drawer UI (backgrounds, buttons, text colors, accents)
- Theme picker cards with color swatches preview

### Additional Improvements
- **Settings sub-tabs**: AI / Theme / About (was single long scroll)
- **Temperature setting** now exposed in UI (0.0-2.0)
- **Send button disabled** during streaming (no double-send)
- **Input field disabled** during streaming
- **"AI sedang merespons..." placeholder** during streaming
- **Status indicator** in drawer header: "● Streaming..." / "N pesan"
- **About sub-tab** with version + feature list + repo link
- **Single-command Run button** now uses `commands[0]` instead of `msg.content` (more accurate when AI includes explanation text)
- **Welcome message** in empty chat with example prompts

### Files Changed (Phase 18)
- **NEW**: `ThemeManager.kt` — theme data class + 6 presets + persistence
- **NEW**: `ThemeHolder` class in `TerminalEmulator.kt` — shared theme reference
- **MODIFIED**: `TerminalEmulator.kt` — accept ThemeHolder, use theme palette for SGR 30-37/90-97/40-47/100-107 and color256(0-15)
- **MODIFIED**: `AIAgent.kt` — added `askAIStreaming()` Flow-based SSE parser; `askAI()` now takes conversation list for multi-turn
- **MODIFIED**: `ChatMessage` — added `isStreaming` and `conversationRole` fields
- **MODIFIED**: `ShellExecutor.kt` — accept ThemeHolder, pass to TerminalEmulator
- **MODIFIED**: `MainActivity.kt` — wire theme holder, streaming handler, multi-turn conversation, clear chat, theme change
- **MODIFIED**: `TerminalUI.kt` — full AIChatPanel rewrite: theme-aware colors, streaming rendering with cursor, auto-scroll, clear chat button, settings sub-tabs (AI/Theme/About), theme picker with color swatches
- **MODIFIED**: `build.gradle.kts` — added `kotlinx-coroutines-core` for Flow, bumped version

### Version
- `versionCode`: 4 → 5
- `versionName`: `3.0.0-phase17-major-fix` → `3.1.0-phase18-streaming-themes`

---

## Phase 17 — Major Bug Fix Release

This release closes the gap between advertised features and actual implementation, and fixes 60+ bugs identified during deep audit.

### Critical Fixes
- **Real `setup-storage`**: Now actually launches SAF picker, persists URI permission, creates `~/storage/shared` symlink/marker. Previously the command was forwarded to `/system/bin/sh` which didn't recognize it.
- **Real `clear` command**: Now clears the local screen buffer via `ShellExecutor.clearScreen()`. Previously sent to Android shell which has no `clear` builtin.
- **No more zombie processes**: `native-lib.cpp` now returns child pid; `ShellExecutor.destroy()` sends SIGTERM → wait → SIGKILL → waitpid to fully reap child. Previously only closed master fd, leaving orphan shell processes.
- **MOTD with system info**: Dynamic banner showing Android version, device, CPU, memory, disk, network IP, uptime. Previously only a static "Tunnel Terminal v3.2" string.

### Terminal Emulator Upgrades
- **Alternate screen buffer** (`?1049h/l`) — vim/nano/less now restore screen on exit
- **Cursor visibility** (`?25h/l`) — vim/htop can hide cursor
- **256-color** (`38;5;n`) and **TrueColor** (`38;2;r;g;b`) support
- **SGR attributes**: bold (1), italic (3), underline (4), reverse (7)
- **Erase variants**: `J 0/1/2/3`, `K 0/1/2`
- **Scrolling region** (`CSI r`)
- **Save/restore cursor** (`CSI s/u`, `ESC 7/8`)
- **OSC sequences** properly ignored (no more garbage from title-set)
- **IND/RI/NEL** (`ESC D/M/E`) for proper linefeed handling
- **Cursor block rendering** at actual position

### AI Improvements
- **Smart Auto-Pilot**: Waits for shell prompt between commands (15s timeout), detects errors mid-sequence, logs each step, stops on error
- **ANSI-stripped context**: Terminal output sent to AI no longer contains escape codes
- **Configurable timeout** per provider (5s–120s)
- **Better error messages**: Specific messages for 401/403/404/429/5xx, timeout, DNS, SSL
- **No API key check for local providers** (Ollama, LM Studio)

### UX Improvements
- **Per-tab command history** (was shared across all tabs — confusing)
- **Consecutive dedup** in command history
- **History capped** at 500 entries per tab
- **Back button** closes drawer/editor before exiting app
- **Contextual FAB**: Detects actual errors in terminal, sends appropriate prompt to AI
- **Extra keys**: Added HOME, END, PGUP, PGDN; expanded symbol bar to 25 chars
- **Auto-scroll** to bottom on new output
- **Debounced settings save** (800ms) — no more disk write on every keystroke
- **"Saved" indicator** in Settings tab
- **POST_NOTIFICATIONS permission** requested on Android 13+

### Editor Upgrades
- **Async file loading** (no UI freeze on large files)
- **Error handling** for binary files, permission denied, OOM
- **Line numbers** in left gutter
- **"Modified" indicator** for unsaved changes
- **Save without close** option
- **Status toast** for save success/error

### Code Quality
- **Removed dead code**: `AnsiParser.kt` (functionality was duplicated in `TerminalEmulator`)
- **`AISettings` immutability**: `var` → `val` properties
- **Snippet ID-based ops**: `remove(id: Long)` instead of `remove(index: Int)` — no more wrong-delete on reorder
- **Snippet cap**: Max 100 snippets (anti-bloat)
- **Snippet update method**: `update(id, title, command)` added
- **Debounced resize** during pinch-zoom (100ms) — no more JNI ioctl spam
- **Comprehensive logging** in C++ via `__android_log_print`
- **fd validation** in all JNI write/close/resize calls

### Permissions
- Added `FOREGROUND_SERVICE_DATA_SYNC` for Android 14+
- Added `INTERNET` and `ACCESS_NETWORK_STATE` (were implicit, now explicit)
- Scoped `READ_EXTERNAL_STORAGE` to `maxSdkVersion=32`
- Scoped `WRITE_EXTERNAL_STORAGE` to `maxSdkVersion=29`

### Version
- `versionCode`: 3 → 4
- `versionName`: `2.0-phase7-pty` → `3.0.0-phase17-major-fix`

---

## License

Open source. Contributions welcome at https://github.com/NanoMindExplorer/tunnel-terminal
