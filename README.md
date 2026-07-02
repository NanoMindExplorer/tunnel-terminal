# Tunnel Terminal

**Tunnel Terminal** adalah aplikasi terminal Android tingkat lanjut yang menggabungkan kekuatan mesin C/C++ NDK (Pseudo-Terminal) dengan AI Copilot Multi-Provider untuk pengembang modern.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Streaming%20SSE-cyan)
![Version](https://img.shields.io/badge/version-3.1.0--phase18-blue)

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
