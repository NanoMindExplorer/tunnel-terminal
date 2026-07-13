# Tunnel Terminal

**Tunnel Terminal** adalah terminal Android AI-native yang merubah cara developer bekerja di perangkat mobile. Menggabungkan mesin C/C++ NDK (Pseudo-Terminal asli) dengan AI Copilot multi-provider, terminal berbasis blok, command palette, tool calling, **lingkungan Linux Ubuntu asli via proot (tanpa root)**, dan banyak lagi.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Multi%20Provider%20%2B%20Vision%20%2B%20MCP-cyan)
![Linux](https://img.shields.io/badge/Linux-Ubuntu%2024.04%20via%20proot-orange)
![Version](https://img.shields.io/badge/version-7.8.0-blue)
![Stability](https://img.shields.io/badge/stability-production-green)

## Quick Links

- 📖 [Wiki Lengkap](docs/WIKI.md) — arsitektur, thread safety, phase history
- 📚 [Panduan Penggunaan](docs/USER_GUIDE.md) — cara pakai semua fitur step-by-step
- 🐧 [Cara Obtain Binary proot](app/src/main/assets/proot/README.md) — wajib dibaca sebelum build fitur Ubuntu

## Mengapa Tunnel Terminal?

Terminal Android konvensional hanya menampilkan teks dan mengeksekusi command. Tunnel Terminal membawa pengalaman **Warp + Claude Code + Cursor + Termux proot-distro** ke Android dalam satu app:

- **Block-based UI** — setiap command + output = card diskret (seperti Warp)
- **AI Agent** — AI bisa baca/tulis file, jalankan command, dengan permission prompts
- **Linux Environment (Ubuntu)** — jalankan `apt`, `git`, `python`, `nodejs` di Ubuntu 24.04 asli via proot, **tanpa root** — lengkap dengan marker-based exit code capture supaya AI bisa autonously install package
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
| 3 | AI Auto-Pilot | Multi-step command execution, error detection, marker-based exit code |
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
| 26 | **Linux Environment** | **Ubuntu 24.04 via proot — `apt`, `git`, `python`, `nodejs` tanpa root** |
| 27 | **Marker-Based Execution** | **AI run_command dibungkus unique marker → capture exit code → kirim balik ke AI sebagai context** |

## Built-in Commands

| Command | Deskripsi |
|---|---|
| `help` | Tampilkan menu bantuan |
| `clear` | Bersihkan layar (lokal) |
| `history` / `history-clear` | Riwayat perintah (persist) |
| `export-output` / `export-chat` | Export transcript / chat AI |
| `copy-output` | Salin output terminal ke clipboard |
| `bookmark list\|add\|go\|remove` | Bookmark direktori + quick `cd` |
| `setup-storage` | Bridge ke /sdcard via SAF |
| `storage-status` | Status konfigurasi storage |
| `storage-reset` | Reset storage |
| `system-info` | Info sistem (MOTD) |
| `open <file>` | Edit file di Tunnel Editor |
| `ssh-list-hostkeys` / `ssh-reset-hostkeys` | TOFU host keys |

**UX Wave 10:** long-press tab untuk rename label; keep-screen-on (default on); command palette items untuk bookmark/copy/rename.

**UX Wave 12 (terminal polish):** safe paste (bracketed / flatten newlines), DECCKM arrows for vim/less, ExtraKeys `^C ^D ^Z ^L ^U ^W` + F5–F12, jump-to-bottom, faster row render, IME sync after history/paste.

**UX Wave 13:** select/copy dari scrollback, ExtraKeys key-repeat (panah/BKSP), split pane tap-to-activate, ukuran PTY awal dari display (local/Ubuntu/SSH).

## 🐧 Linux Environment (Ubuntu via proot)

Fitur unggulan Phase 36–39: jalankan **Ubuntu 24.04 asli** di dalam Tunnel Terminal tanpa root. Berbeda dari shell Android biasa yang terbatas pada `toybox`, di sini kamu dapat `apt`, `dpkg`, `systemd-userspace` tools, dan ribuan package Ubuntu asli.

### Cara kerja

```
Tunnel Terminal
  ├── Tab "Local"     → /system/bin/sh (shell Android bawaan)
  ├── Tab "SSH"       → remote shell via JSch
  └── Tab "Ubuntu" 🐧 → proot + Ubuntu 24.04 rootfs
                         (apt, git, python, nodejs, build-essential, ...)
```

PTY layer yang sudah teruji (`native-lib.cpp` → `forkpty()`) **tidak diubah**. Fitur ini hanya menambah satu jalur baru: alih-alih `execve("/system/bin/sh")`, sesi Ubuntu memanggil `execve("<proot>", ["--link2symlink", "-0", "-r", "<rootfs>", ...])`. Mekanisme fork/pty/read-loop-nya sama persis.

### Cara pakai

1. Tap tombol 🐧 di TabBar, atau buka Command Palette (Ctrl+K) → "Ubuntu (Linux Environment)"
2. Pertama kali: dialog instalasi muncul → tap **Install**
3. App menyalin binary `proot` dari assets, download Ubuntu Base rootfs (~30–60MB) dari `cdimage.ubuntu.com`, ekstrak ke `filesDir/linux/ubuntu/`, setup DNS
4. Setelah selesai, tab Ubuntu terbuka — langsung bisa `apt update && apt install git`
5. Untuk uninstall (bebaskan storage): Command Palette → "Manage Linux Environment" → Uninstall

### Skenario penggunaan

- Install tool dev di Android: `apt install git python3 nodejs npm build-essential`
- Jalankan script Python/Node di Ubuntu environment yang konsisten dengan server
- Test build config sebelum push ke CI (replikasi environment)
- Pakai package yang tidak ada di Termux repository

### Known limitations

| Limitasi | Penyebab | Workaround |
|---|---|---|
| `systemctl`/`service` tidak jalan | Android tidak punya systemd/cgroup v2 | Jalankan servis manual: `nginx -g "daemon off;" &` |
| Performa kompilasi C++ berat lambat | Overhead ptrace proot | Cocok untuk pakai tool, kurang cocok untuk compile project besar dari nol |
| Beberapa device OEM crash di startup | SECCOMP filter tidak kompatibel dengan proot | App auto-retry dengan `PROOT_NO_SECCOMP=1` (tersimpan per-device) |
| Rootfs makan 300–500MB (basic) atau 1–2GB (dengan dev tools) | Ukuran asli Ubuntu | Cek free storage sebelum install; uninstall kapan saja |

### Catatan distribusi

⚠️ **Fitur ini tidak kompatibel dengan Google Play Store.** Kebijakan Play melarang app mendownload+mengeksekusi binary native saat runtime (alasan Termux sendiri tidak ada di Play Store). Distribusikan APK lewat **GitHub Releases** atau **F-Droid** saja.

## Build

Membutuhkan: Android Studio Hedgehog+, Android SDK 34, NDK 25.1.8937393+, CMake 3.22.1

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### ⚠️ Wajib sebelum build: obtain binary `proot`

Fitur Ubuntu Linux Environment butuh binary `proot` di `app/src/main/assets/proot/proot`. File ini **tidak di-bundle di repo** (ukuran + licensing). Cara mendapatkannya: ekstrak dari package Termux `.deb` — lihat instruksi lengkap di [`app/src/main/assets/proot/README.md`](app/src/main/assets/proot/README.md).

Tanpa binary ini, app tetap jalan normal untuk semua fitur lain (local shell, SSH, AI, dll), tapi tombol 🐧 → Install akan gagal dengan error "Binary proot tidak ditemukan di assets APK".

## Security

- `allowBackup=false` — kredensial tidak ikut backup
- `run_command`/`delete_file` selalu butuh permission (no "Always Allow")
- SSH `StrictHostKeyChecking=ask` (TOFU pattern)
- MCP server `http://` ditolak kecuali localhost
- AI tool calls di code blocks tidak dieksekusi (anti prompt injection)
- `isMinifyEnabled=true` untuk release (ProGuard + R8)
- proot dijalankan dengan `-0` (fake root) — terbatas di rootfs app, tidak mengakses system Android
- Rootfs Ubuntu disimpan di `filesDir/linux/` (private ke app, tidak butuh storage permission)

## Thread Safety

- `TerminalEmulator`: `synchronized(lock)` on all mutations + snapshot rendering
- `ShellExecutor` / `SshShellExecutor` / `ProotShellExecutor`: same pattern (`outputLock` + `writeLock` + `AtomicBoolean` fd guard)
- `killSession`: `waitpid(WNOHANG)` before kill (PID recycling safe)
- ID generator: `AtomicInteger`/`AtomicLong` (no timestamp collision)
- `MarkerExecutor`: AtomicLong counter untuk unique marker ID per-command

## Architecture (Ringkas)

```
┌──────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI                     │
│  TabBar │ TerminalScreenView │ AIChatPanel │ DiffView    │
└───────────────────────┬──────────────────────────────────┘
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
   ┌────────────┐ ┌────────────┐ ┌──────────────────┐
   │ ShellExec  │ │ SshShell   │ │ ProotShellExec   │
   │ (local PTY)│ │ (JSch)     │ │ (proot+Ubuntu)   │
   └─────┬──────┘ └─────┬──────┘ └────────┬─────────┘
         │              │                 │
         ▼              ▼                 ▼
   ┌──────────────────────────────────────────────────┐
   │              TerminalSession interface           │
   │   start / writeRaw / resize / destroy / ...      │
   └──────────────────────┬───────────────────────────┘
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
       ┌─────────────┐         ┌──────────────┐
       │ TerminalJni │         │  AIAgent     │
       │ (Kotlin)    │         │  (SSE Flow)  │
       └──────┬──────┘         └──────┬───────┘
              │                       │
              ▼                       ▼
       ┌─────────────┐         ┌──────────────────┐
       │ native-lib  │         │ MarkerExecutor   │
       │ .cpp (NDK)  │         │ (cmd + exit code)│
       │ forkpty()   │         └──────────────────┘
       │ execve()    │
       └─────────────┘
```

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
| 32-34 | Input double-fire fix, soft keyboard Enter, text selection, paste, SSH TOFU real fingerprint check |
| 35-36 | Credit + crypto address, marker-based command execution foundation |
| 37 | Marker-based AI tool calling — `run_command` dibungkus marker, exit code di-capture, hasil dikirim balik ke AI sebagai context; @command: real implementation |
| **38-39** | **Linux Environment (Ubuntu via proot) — `createSessionExec()` JNI, `ProotBootstrap` download+extract rootfs, `ProotShellExecutor`, install dialog, SECCOMP auto-retry, uninstall** |

## Credits

**Developer:** NanoMind (https://github.com/NanoMindExplorer)

### Support

Jika fitur ini bermanfaat, dukung pengembangan lanjutan:

- 💰 Crypto: lihat di Settings → About
- ⭐ Star repo: https://github.com/NanoMindExplorer/tunnel-terminal
- 🐛 Bug report: buka issue dengan label sesuai phase terkait

## License

Open source. Contributions welcome at https://github.com/NanoMindExplorer/tunnel-terminal
