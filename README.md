# Tunnel Terminal

**Tunnel Terminal** adalah terminal Android AI-native yang merevolusi cara developer bekerja di perangkat mobile. Menggabungkan mesin C/C++ NDK (Pseudo-Terminal asli) dengan AI Copilot multi-provider, terminal berbasis blok, command palette, tool calling, dan banyak lagi.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Multi%20Provider%20%2B%20Vision%20%2B%20MCP-cyan)
![Version](https://img.shields.io/badge/version-5.1.0-blue)
![Stability](https://img.shields.io/badge/stability-production-green)

## Quick Links

- 📖 [Wiki Lengkap](docs/WIKI.md) — arsitektur, thread safety, phase history
- 📚 [Panduan Penggunaan](docs/USER_GUIDE.md) — cara pakai semua fitur step-by-step

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

## Fitur Utama (Ringkas)

| # | Fitur | Detail |
|---|-------|--------|
| 1 | True Linux Terminal | C++ NDK PTY via `forkpty()`, mendukung vim/htop/less |
| 2 | Block-Based Terminal | Warp-style command+output cards, collapsible, rerun, AI explain |
| 3 | AI Auto-Pilot | Multi-step command execution, error detection, wait-for-prompt |
| 4 | AI Tool Calling | read_file, write_file, delete_file, run_command, list_files, search_files |
| 5 | Permission Prompts | Claude Code-style: Allow once / Always allow / Deny (kecuali run_command/delete_file) |
| 6 | Inline Diff View | LCS-based diff sebelum apply AI file edits |
| 7 | Command Palette | Ctrl+K fuzzy search, 5 categories, keyboard nav |
| 8 | @context Mentions | @file, @block, @command, @terminal, @snippet |
| 9 | MCP Protocol | Connect ke MCP servers, discover+invoke tools |
| 10 | Multi-Provider AI | 13 presets + Custom, fetch /models, vision detection |
| 11 | AI Streaming SSE | Token-by-token response via Server-Sent Events |
| 12 | AI Image Vision | Gallery/camera multi-select, auto-compress, vision models |
| 13 | Markdown Rendering | Headers, bold, italic, code blocks, lists, links, blockquotes |
| 14 | SSH Client | JSch, password+key auth, UTF-8 safe, TOFU host key |
| 15 | Syntax Highlighting | Kotlin, Python, JS/TS, Shell, JSON, XML, YAML, Markdown |
| 16 | Split Pane | 2 terminals side by side, tap to switch |
| 17 | Smart Autocomplete | History + common commands, substring matching |
| 18 | Voice Input | Speech-to-text (Bahasa Indonesia) |
| 19 | Agent Workflows | Save/replay multi-step AI sequences, conditional steps |
| 20 | Theme Picker | 6 themes, live apply, fontSize persist |
| 21 | File Explorer | Browse filesystem tanpa `cd` |
| 22 | Workspace Sessions | Save/restore tab sets |
| 23 | Physical Keyboard | Full support: Enter, Backspace, Tab, Arrows, F1-F4, Ctrl+key, Alt+key |
| 24 | Mouse Support | Click to focus, scroll wheel |
| 25 | Pinch-to-Zoom | Font size persist across sessions |

## Built-in Commands

| Command | Deskripsi |
|---|---|
| `help` | Tampilkan menu bantuan |
| `clear` | Bersihkan layar (lokal) |
| `setup-storage` | Bridge ke /sdcard via SAF |
| `storage-status` | Status konfigurasi storage |
| `storage-reset` | Reset storage |
| `system-info` | Info sistem (MOTD) |
| `open <file>` | Edit file di Tunnel Editor |

## Build

Membutuhkan: Android Studio Hedgehog+, Android SDK 34, NDK 25.1.8937393+, CMake 3.22.1

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Security

- `allowBackup=false` — kredensial tidak ikut backup
- `run_command`/`delete_file` selalu butuh permission (no "Always Allow")
- SSH `StrictHostKeyChecking=ask` (TOFU pattern)
- MCP server `http://` ditolak kecuali localhost
- AI tool calls di code blocks tidak dieksekusi (anti prompt injection)
- `isMinifyEnabled=true` untuk release (ProGuard + R8)

## Thread Safety

- `TerminalEmulator`: `synchronized(lock)` on all mutations + snapshot rendering
- `ShellExecutor`: `outputLock` + `writeLock` + `AtomicBoolean` fd guard
- `SshShellExecutor`: same pattern
- `killSession`: `waitpid(WNOHANG)` before kill (PID recycling safe)
- ID generator: `AtomicInteger`/`AtomicLong` (no timestamp collision)

## Phase History

| Phase | Highlight |
|---|---|
| 1-16 | Initial development: Compose UI, NDK PTY, AI Copilot, multi-tab, themes |
| 17 | Major Bug Fix — phantom commands, zombie processes, real MOTD |
| 18 | AI Streaming SSE + Multi-turn Memory + Theme Picker |
| 19 | Free AI Provider + Image Vision + File Explorer + Workspace + Icon |
| 19.5 | Input Reliability + Mouse Support |
| 20 | Comprehensive Bug Fix — cursor, fd, alt screen, ANR |
| 21 | Thread Safety + SSH Client + Syntax Highlighting + Split Pane |
| 22 | AI-Native Revolution — blocks, palette, tool calls, permission, markdown |
| 23 | @context + MCP + Diff + Autocomplete + Voice + Agent Workflows |
| 24-26 | Stability fixes — pinch-zoom, block input, fontSize persist |
| 27-30 | Comprehensive audit — 38 bugs fixed (security, functional, medium, low) |
| 31 | Crash-on-launch fix — 7 root causes (SharedPreferences, foreground service, JNI) |

## License

Open source. Contributions welcome at https://github.com/NanoMindExplorer/tunnel-terminal
