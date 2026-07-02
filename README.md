# Tunnel Terminal

**Tunnel Terminal** adalah terminal Android AI-native yang merevolusi cara developer bekerja di perangkat mobile. Menggabungkan mesin C/C++ NDK (Pseudo-Terminal asli) dengan AI Copilot multi-provider, terminal berbasis blok, command palette, tool calling, dan banyak lagi.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Multi%20Provider%20%2B%20Vision%20%2B%20MCP-cyan)
![Version](https://img.shields.io/badge/version-4.5.0--phase26-blue)
![Stability](https://img.shields.io/badge/stability-production-green)

## Mengapa Tunnel Terminal?

Terminal Android konvensional (Termux, dll) hanya menampilkan teks dan mengeksekusi command. Tunnel Terminal membawa pengalaman **Warp + Claude Code + Cursor** ke Android:

- **Block-based UI** — setiap command + output = card diskret (seperti Warp)
- **AI Agent** — AI bisa baca/tulis file, jalankan command, dengan permission prompts
- **Command Palette** — Ctrl+K untuk akses cepat semua fitur (seperti VS Code)
- **Markdown Rendering** — AI responses dirender sebagai rich markdown
- **@context Mentions** — mention file, block, terminal output sebagai AI context
- **MCP Protocol** — connect ke MCP servers untuk tools eksternal
- **SSH Client** — remote ke server langsung dari terminal
- **Syntax Highlighting** — 8 bahasa dengan theme-aware colors
- **Split Pane** — 2 terminal side by side (tmux-style)
- **Voice Input** — speech-to-text untuk AI prompts
- **6 Themes** — Matrix, Dracula, Solarized, Monokai Pro, Nord, Tokyo Night

---

## Fitur Utama

### 1. True Linux Terminal (C++ NDK PTY)
Menggunakan `forkpty()` dari C/C++ untuk membuat sesi terminal asli di kernel Linux Android. Mendukung penuh aplikasi TUI seperti `vim`, `nano`, `htop`, `less`. Term env `xterm-256color` di-set otomatis untuk warna yang benar.

### 2. Block-Based Terminal (Warp-style)
Setiap command + output dirender sebagai **card diskret** dengan:
- Status icon (✓ success, ✗ error, ⏳ running)
- Timestamp
- Collapsible output
- Quick actions: Rerun, 🤖 Explain (ask AI about this block)

Toggle antara **raw mode** (untuk TUI apps) dan **block mode** (untuk review command history) via tombol ⊞.

### 3. AI Auto-Pilot (Agentic Workflow)
AI bisa menjalankan rangkaian perintah secara otomatis. Smart Auto-Pilot:
- Menunggu prompt shell muncul sebelum next command
- Mendeteksi error mid-sequence
- Memberi laporan setiap step

### 4. AI Tool Calling (Function Calling)
AI bisa request tool execution via `<tool_call>` tags:

**Read-only tools (tanpa permission):**
- `read_file(path)` — baca file
- `list_files(dir)` — list direktori
- `search_files(pattern, dir)` — cari file
- `get_terminal_output()` — ambil output terminal

**Destructive tools (dengan permission):**
- `write_file(path, content)` — tulis file (dengan diff preview)
- `delete_file(path)` — hapus file
- `run_command(cmd)` — jalankan command

### 5. Permission Prompts (Claude Code style)
Saat AI request destructive tool, dialog muncul:
- Menampilkan tool call + AI reasoning
- User pilih: Allow once / Always allow / Deny
- Permission state persisted per-tool

### 6. Inline Diff View (Cursor/Warp-style)
AI file edits menampilkan diff sebelum apply:
- Green lines = added, Red lines = removed
- LCS-based diff algorithm
- Apply / Reject buttons

### 7. Command Palette (Ctrl+K)
Modal overlay dengan fuzzy search (seperti VS Code / Warp):
- 5 categories: AI, Navigation, Settings, Commands, Recent
- Keyboard navigation (Up/Down/Enter/Escape)
- Recent commands auto-suggested

### 8. @context Mentions (Warp-style)
Type `@` di AI chat untuk mention context:
- `@file:path` — attach file content
- `@block:N` — attach command block N output
- `@command:"cmd"` — attach command output
- `@terminal` — attach current terminal output
- `@snippet:name` — attach saved snippet

### 9. MCP Protocol (Anthropic standard)
Connect ke MCP servers untuk external tools:
- Add/remove/toggle MCP servers (HTTP/SSE)
- Discover tools via `GET /tools`
- Invoke tools via `POST /tools/{name}/invoke`
- AI can call MCP tools: `<tool_call>{"tool":"mcp.server.tool"}</tool_call>`

### 10. Multi-Provider AI (OpenAI-Compatible)
13 preset providers + Custom:
- **OpenAI** (gpt-4o, gpt-4o-mini)
- **DeepSeek** (deepseek-chat, deepseek-coder)
- **Groq** (Llama 3 8B/70B)
- **OpenRouter** (akses Claude, Gemini, ratusan model)
- **Gemini** (via OpenAI-compat)
- **Anthropic Claude** (via OpenAI-compat)
- **Mistral**, **Together AI**, **Fireworks AI**, **Perplexity**
- **Local Ollama**, **LM Studio**
- **Custom** — bebas masukin provider apapun

Fetch model list dari `/models` endpoint dengan satu klik. Vision capability detection otomatis (👁 icon).

### 11. AI Streaming SSE
Response AI muncul **token-by-token** via Server-Sent Events. Multi-turn conversation memory (max 20 pesan).

### 12. AI Image Vision
Attach gambar ke pesan AI (gallery/camera multi-select). Auto-compress ke 1024px JPEG 85. Support: gpt-4o, gemini-1.5, claude-3, llama-3.2-vision, pixtral, qwen-vl.

### 13. Markdown Rendering
AI responses dirender sebagai rich markdown:
- Headers, bold, italic, inline code
- Code blocks dengan syntax highlighting
- Lists, links, blockquotes
- Theme-aware colors

### 14. SSH Client (JSch)
- Password + private key authentication
- PTY type "xterm-256color" + resize
- Thread-safe (synchronized I/O)
- UTF-8 safe (ByteBuffer + CharsetDecoder)
- SSH tabs coexist with local PTY tabs

### 15. Syntax Highlighting (Editor)
Regex-based highlighting untuk 8 bahasa:
- Kotlin, Python, JavaScript/TypeScript, Shell, JSON, XML, YAML, Markdown
- Theme-aware colors dari ANSI palette
- `open <file>` menampilkan highlighted code

### 16. Split Pane (tmux-style)
2 terminal side by side:
- Left: active terminal (with input)
- Right: second tab (tap to switch)

### 17. Smart Autocomplete
Command suggestions saat typing:
- History matches (most recent first)
- 40+ common commands
- Substring matching

### 18. Voice Input (speech-to-text)
Tap 🎤 di command palette → speak AI prompt:
- Indonesian + English language models
- Spoken text langsung dikirim sebagai AI prompt

### 19. Agent Workflows
Save + replay multi-step AI agent sequences:
- **AI_STEP**: Ask AI a prompt
- **COMMAND_STEP**: Run terminal command
- **DELAY_STEP**: Wait N ms
- **CONDITIONAL_STEP**: If output contains X, run Y

### 20. Theme Picker (6 themes)
- **Matrix** (default) — hijau neon
- **Dracula** — dark purple/pink
- **Solarized Dark** — ergonomis
- **Monokai Pro** — classic Sublime
- **Nord** — Arctic blue
- **Tokyo Night** — modern dark

Theme diterapkan ke terminal cells + UI drawer secara live. Font size persist antar session.

### 21. UX Mobile-First
- **Pinch-to-zoom** — cubit layar untuk atur font size (persist)
- **Volume Up/Down** — navigasi riwayat perintah (per-tab, saat terminal focused)
- **Physical keyboard** — full support: Enter, Backspace, Tab, Arrows, Home/End, PageUp/Down, F1-F4, Ctrl+key, Alt+key
- **Mouse** — click to focus, scroll wheel
- **Back button** — tutup drawer/editor sebelum exit
- **File Explorer** — browse file system tanpa `cd`
- **Workspace Sessions** — save/restore tab sets

### 22. Built-in Commands
| Command | Deskripsi |
|---|---|
| `help` | Tampilkan menu bantuan |
| `clear` | Bersihkan layar (lokal) |
| `setup-storage` | Bridge ke /sdcard via SAF |
| `storage-status` | Status konfigurasi storage |
| `storage-reset` | Reset storage |
| `system-info` | Info sistem (MOTD) |
| `open <file>` | Edit file di Tunnel Editor |

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  Jetpack Compose UI                                    │
│  ├── TabBar (tabs, +, 📁, 💾, 🔌, ⬡, ⊞, ⌘K, AI)    │
│  ├── TerminalScreenView (snapshot rendering)          │
│  ├── BlockTerminalView (Warp-style blocks)            │
│  ├── AIChatPanel (markdown, streaming, tool calls)    │
│  ├── CommandPalette (Ctrl+K fuzzy search)             │
│  └── DiffView (inline diff before apply)              │
├──────────────────────────────────────────────────────┤
│  TerminalSession interface                             │
│  ├── ShellExecutor (local PTY via forkpty)            │
│  └── SshShellExecutor (remote SSH via JSch)           │
├──────────────────────────────────────────────────────┤
│  TerminalEmulator (ANSI parser, screen buffer)        │
│  Thread-safe: synchronized(lock) on all mutations     │
├──────────────────────────────────────────────────────┤
│  AI Layer                                              │
│  ├── AIAgent (SSE streaming, multi-turn, tool calls)  │
│  ├── ToolExecutor + PermissionManager                  │
│  ├── ContextManager (@mentions)                        │
│  ├── McpManager (MCP server connections)               │
│  └── MarkdownText (markdown renderer)                  │
├──────────────────────────────────────────────────────┤
│  TerminalJni (Kotlin ↔ C++ bridge)                    │
│  Thread-safe: writeLock, PID-safe killSession          │
├──────────────────────────────────────────────────────┤
│  native-lib.cpp (C++ NDK)                             │
│  forkpty() → /system/bin/sh                            │
│  SIGTERM → SIGKILL → waitpid (no zombie)              │
│  TERM=xterm-256color, HOME set                         │
│  Partial write loop with EINTR handling                │
└──────────────────────────────────────────────────────┘
```

## Build

Membutuhkan:
- Android Studio Hedgehog+ (2023.1.1)
- Android SDK 34
- NDK 25.1.8937393+
- CMake 3.22.1

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Thread Safety

All terminal state mutations are synchronized:
- `TerminalEmulator`: `synchronized(lock)` on `process()`, `resize()`, `getScreenSnapshot()`, `getCursorState()`, `snapshotRows()`, `snapshotCols()`
- `ShellExecutor`: `outputLock` for outputBuffer, `writeLock` for PTY writes
- `SshShellExecutor`: same pattern (outputLock + writeLock)
- Screen rendering uses **snapshot copies** — Compose never reads array being mutated
- `killSession`: `waitpid(WNOHANG)` before kill to prevent PID recycling

## Phase History

| Phase | Highlight |
|---|---|
| 1-6 | Initial Compose UI, multi-tab, AI Copilot, ANSI parser, foreground service, MOTD |
| 7 | C/C++ NDK PTY engine (forkpty) |
| 8 | True terminal emulator with screen buffer & matrix rendering |
| 9 | AI Auto-Pilot (agentic sequential execution) |
| 10 | Storage Access Framework (SAF + symlink) |
| 11 | Dynamic UI resizing (SIGWINCH), pinch-to-zoom |
| 12 | Built-in Tunnel Editor (touch-friendly) |
| 13 | Modifier key state machine (Ctrl+C, Alt) |
| 14 | Hardware volume key history navigation |
| 15 | Session lifecycle guard (anti-freeze on exit) |
| 16 | Built-in help command & open-source README |
| **17** | **Major Bug Fix** — phantom commands, zombie processes, real MOTD |
| **18** | **AI Streaming SSE + Multi-turn Memory + Theme Picker** |
| **19** | **Free AI Provider + Image Vision + File Explorer + Workspace + Icon Redesign** |
| **19.5** | **Input Reliability + Mouse Support** (fix: cannot type) |
| **20** | **Comprehensive Bug Fix** — cursor double-render, fd double-close, alt screen, ANR |
| **21** | **Thread Safety + SSH Client + Syntax Highlighting + Split Pane** |
| **22** | **AI-Native Revolution** — blocks, palette, tool calls, permission, markdown |
| **23** | **@context + MCP + Diff + Autocomplete + Voice + Agent Workflows** |
| **24-24.5** | **Stability Fix** — thread safety, pinch-zoom persist, block input, voice input |
| **25** | **Input + ANR + Provider Dropdown Fix** |
| **26** | **All 13 remaining bugs fixed** — split/block input, SSH UTF-8, fontSize persist, keyboard nav |

---

## License

Open source. Contributions welcome at https://github.com/NanoMindExplorer/tunnel-terminal
