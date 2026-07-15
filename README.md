# Tunnel Terminal

**Tunnel Terminal** adalah terminal Android AI-native yang merubah cara developer bekerja di perangkat mobile. Menggabungkan mesin C/C++ NDK (Pseudo-Terminal asli) dengan AI Copilot multi-provider, terminal berbasis blok, command palette, tool calling, **lingkungan Linux Ubuntu asli via proot (tanpa root)**, dan banyak lagi.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Multi%20Provider%20%2B%20Vision%20%2B%20MCP%20%2B%20Tools-cyan)
![Linux](https://img.shields.io/badge/Linux-Ubuntu%2024.04%20via%20proot-orange)
![Version](https://img.shields.io/badge/version-8.1.1-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF)
![AGP](https://img.shields.io/badge/AGP-8.5.2-orange)
![Tests](https://img.shields.io/badge/tests-14%20files-green)
![Stability](https://img.shields.io/badge/stability-production-green)

## Quick Links

- 📖 [Wiki Lengkap](docs/WIKI.md) — arsitektur, thread safety, phase history
- 📚 [Panduan Penggunaan](docs/USER_GUIDE.md) — cara pakai semua fitur step-by-step
- 🐧 [Cara Obtain Binary proot](app/src/main/assets/proot/README.md) — wajib dibaca sebelum build fitur Ubuntu

## Mengapa Tunnel Terminal?

Terminal Android konvensional hanya menampilkan teks dan mengeksekusi command. Tunnel Terminal membawa pengalaman **Warp + Claude Code + Cursor + Termux proot-distro** ke Android dalam satu app:

- **Block-based UI** — setiap command + output = card diskret (seperti Warp)
- **AI Agent** — AI bisa baca/tulis file, jalankan command, dengan permission prompts + risk-approval dialog
- **Native API Tool-Calling** (Phase 59) — `tools`/`tool_choice` parameter dikirim ke API provider (OpenAI, DeepSeek, Groq, OpenRouter, Gemini, Anthropic Native, Mistral, Together, Fireworks). MCP tools juga di-inject dinamis.
- **TaskPlanManager** (Phase 58) — plan/act/observe/verify loop, immune terhadap 20-message context limit
- **SFTP for SSH** (Phase 58) — remote file I/O via `ChannelSftp` — read/write/list file di server SSH tanpa `scp` manual
- **Anthropic Native API** (Wave 5) — support Claude via Messages API (`apiStyle: anthropic`), bukan hanya OpenAI-compat
- **Linux Environment (Ubuntu)** — jalankan `apt`, `git`, `python`, `nodejs` di Ubuntu 24.04 asli via proot, **tanpa root** — lengkap dengan marker-based exit code capture supaya AI bisa autonously install package
- **Command Palette** — Ctrl+K untuk akses cepat semua fitur (seperti VS Code)
- **Markdown Rendering** — AI responses dirender sebagai rich markdown
- **@context Mentions** — mention file, block, terminal output sebagai AI context
- **MCP Protocol** — connect ke MCP servers untuk tools eksternal (HTTPS enforced, API keys encrypted)
- **SSH Client** — remote ke server langsung dari terminal, dengan SFTP untuk file ops
- **Syntax Highlighting** — 8 bahasa dengan theme-aware colors
- **Split Pane** — 2 terminal side by side (tmux-style)
- **Voice Input** — speech-to-text untuk AI prompts
- **6 Themes** — Matrix, Dracula, Solarized, Monokai Pro, Nord, Tokyo Night
- **Checkpointing/Undo** (Phase 50) — AI edit file disimpan snapshot-nya; satu tap undo
- **Project Context** (Phase 50) — git branch + manifests + file tree auto-injected ke AI prompt
- **EncryptedSharedPreferences** (Phase 41) — API key AES256-GCM encrypted at rest
- **Wave 10-17 UX polish** — bookmarks, safe paste, DECCKM, ExtraKeys, scrollback search, font zoom, AI chat UX
- **14 test files** — TerminalEmulator, AiToolCall, Permission, Wave utils, history/url, chat export, bookmarks, IME, terminal polish, scrollback select, find/url/mouse, Unicode, font zoom

## Fitur Utama (Ringkas)

| # | Fitur | Detail |
|---|-------|--------|
| 1 | True Linux Terminal | C++ NDK PTY via `forkpty()`, mendukung vim/htop/less |
| 2 | Block-Based Terminal | Warp-style command+output cards, collapsible, rerun, AI explain |
| 3 | AI Auto-Pilot | Multi-step command execution, error detection, marker-based exit code |
| 4 | AI Tool Calling | 11 tools: read_file, write_file, edit_file, delete_file, list_files, search_files, grep_content, run_command, get_terminal_output, plan_task, update_task_status |
| 5 | **Native API Tool-Calling** | **Phase 59: `tools`+`tool_choice` parameter dikirim ke API. MCP tools di-inject dinamis.** |
| 6 | Permission Prompts | Claude Code-style: Allow once / Always allow / Deny (kecuali run_command/delete_file). Per-session scope. |
| 7 | Inline Diff View | LCS-based diff sebelum apply AI file edits |
| 8 | Command Palette | Ctrl+K fuzzy search, 5 categories, keyboard nav |
| 9 | @context Mentions | @file, @block, @command, @terminal, @snippet |
| 10 | MCP Protocol | Connect ke MCP servers, discover+invoke tools, HTTPS enforced |
| 11 | Multi-Provider AI | 13 presets + Custom, fetch /models, vision detection, native tool_calling flag, Anthropic Native API |
| 12 | AI Streaming SSE | Token-by-token response via Server-Sent Events + native tool_calls delta accumulation |
| 13 | AI Image Vision | Gallery/camera multi-select, auto-compress, vision models |
| 14 | Markdown Rendering | Headers, bold, italic, code blocks, lists, links, blockquotes |
| 15 | SSH Client | JSch, password+key auth, UTF-8 safe, TOFU host key |
| 16 | **SFTP for SSH** | **Phase 58: read/write/list file di server SSH via ChannelSftp — mkdir recursive** |
| 17 | Syntax Highlighting | Kotlin, Python, JS/TS, Shell, JSON, XML, YAML, Markdown |
| 18 | Split Pane | 2 terminals side by side, tap to switch |
| 19 | Smart Autocomplete | History + common commands, substring matching (persist across restart) |
| 20 | Voice Input | Speech-to-text (Bahasa Indonesia) |
| 21 | Agent Workflows | Save/replay multi-step AI sequences, conditional steps |
| 22 | Theme Picker | 6 themes, live apply, fontSize persist |
| 23 | File Explorer | Browse filesystem tanpa `cd` |
| 24 | Workspace Sessions | Save/restore tab sets |
| 25 | Physical Keyboard | Full support: Enter, Backspace, Tab, Arrows, F1-F12, Ctrl+key, Alt+key |
| 26 | Mouse Support | Click to focus, scroll wheel, mode 1000/1006 (SGR) |
| 27 | Pinch-to-Zoom | Font size 8-28sp, 0.5sp snap, persist across sessions |
| 28 | **Linux Environment** | **Ubuntu 24.04 via proot — `apt`, `git`, `python`, `nodejs` tanpa root** |
| 29 | **Marker-Based Execution** | **AI run_command dibungkus unique marker → capture exit code → kirim balik ke AI sebagai context** |
| 30 | **Agent Mode** (Phase 47) | **Autonomous task runner: AI pilih tool sendiri, risk-approval dialog, Stop/Pause/Resume** |
| 31 | **TaskPlanManager** (Phase 58) | **Plan/Act/Observe/Verify loop, immune terhadap 20-message context limit** |
| 32 | **Checkpointing/Undo** (Phase 50) | **Snapshot file sebelum AI edit, satu tap undo (max 50 checkpoints)** |
| 33 | **Project Context** (Phase 50) | **git branch + 10 manifest types + file tree auto-injected ke AI prompt** |
| 34 | **Scrollback buffer** (Phase 49) | **2000-line ring buffer, LazyColumn virtualized (Wave 15), Unicode code-points** |
| 35 | **EncryptedSharedPreferences** (Phase 41) | **API key + SSH creds + MCP keys AES256-GCM encrypted at rest** |
| 36 | **Automated tests** (Phase 51+) | **14 test files (03-16): TerminalEmulator, AiToolCall, Permission, Wave utils, dll** |
| 37 | **Wave 10-17 UX polish** | **Bookmarks, safe paste, DECCKM, ExtraKeys, scrollback search/find, font zoom, AI chat UX** |
| 38 | **Anthropic Native API** (Wave 5) | **Claude via Messages API (`apiStyle: anthropic`), bukan hanya OpenAI-compat** |
| 39 | **HTTPS enforcement** (Phase 60) | **Reject HTTP untuk provider eksternal (kecuali localhost Ollama/LM Studio)** |
| 40 | **Wide char support** (Wave 15) | **Unicode code-points + combining marks, CJK width 2, emoji width 2** |

## Built-in Commands

| Command | Deskripsi |
|---|---|
| `help` | Tampilkan menu bantuan |
| `clear` | Bersihkan layar (lokal) |
| `history` / `history-clear` | Riwayat perintah (persist) |
| `export-output` / `export-chat` | Export transcript / chat AI |
| `copy-output` | Salin output terminal ke clipboard |
| `bookmark list\|add\|go\|remove` | Bookmark direktori + quick `cd` |
| `setup-storage` | Pilih folder perangkat (SAF; disarankan Download) |
| `storage-ls` / `put` / `get` / `write` | List/salin/tulis di folder SAF |
| `storage-save-download` | Simpan file ke Download publik (MediaStore) |
| `storage-grant-all` | Opsional: akses semua file untuk path shell |
| `storage-status` / `storage-reset` | Status / cabut grant |
| `system-info` | Info sistem (MOTD) |
| `open <file>` | Edit file di Tunnel Editor |
| `ssh-list-hostkeys` / `ssh-reset-hostkeys` | TOFU host keys |

**UX Wave 10:** long-press tab untuk rename label; keep-screen-on (default on); command palette items untuk bookmark/copy/rename.

**UX Wave 12 (terminal polish):** safe paste (bracketed / flatten newlines), DECCKM arrows for vim/less, ExtraKeys `^C ^D ^Z ^L ^U ^W` + F5–F12, jump-to-bottom, faster row render, IME sync after history/paste.

**UX Wave 13:** select/copy dari scrollback, ExtraKeys key-repeat (panah/BKSP), split pane tap-to-activate, ukuran PTY awal dari display (local/Ubuntu/SSH).

**UX Wave 14/20:** `tt-find <query>` di scrollback (shell `find` tidak di-intercept), `open-url` / Open URL chip, mouse wheel + mode 1000/1006, restart session **tanpa hapus history**, ExtraKeys F1–F4 + `^A`/`^E`.

**UX Wave 15 (v8.0):** LazyColumn virtualized scrollback (hingga 2000 baris), Unicode code-point + combining marks, ExtraKeys compact (toggle ▴/▾).

**UX Wave 16:** Fix pinch-zoom font (gesture-local size, snap 0.5sp, range 8–28sp), ExtraKeys `A+`/`A−`, palette zoom, debounce persist.

**UX Wave 17:** AI chat nyaman — Stop stream, bubble + Copy/Retry, empty chips, Auto-Pilot progress, Agent scroll/pause, API key mask, max tokens, FAB AI.

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

Membutuhkan: Android Studio Koala+ (AGP 8.5.2), Android SDK 34, NDK 25.1.8937393+, CMake 3.22.1, Kotlin 2.0.21, Gradle 8.9, JDK 17

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release build (signed, ProGuard/R8 enabled)
./gradlew assembleFullRelease
# Output: app/build/outputs/apk/full/release/app-full-release.apk

# Run unit tests (14 test files)
./gradlew testFullDebugUnitTest
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
| 37 | Marker-based AI tool calling — `run_command` dibungkus marker, exit code di-capture |
| 38-39 | Linux Environment (Ubuntu via proot) — `createSessionExec()` JNI, `ProotBootstrap`, `ProotShellExecutor` |
| 40 | Audit V3 fixes — 42 bugs (A1-A4, H1-H10, M1-M13) |
| 41 | Security & Privacy — EncryptedSharedPreferences (AES256-GCM), SSH dialog, .gitignore, product flavors |
| 42-44 | Documentation, robustness, UX quality (theme recolor, pinch-zoom Block Mode, PTY initial size) |
| 45-46 | Realtime fixes + AI↔Ubuntu Integration (MarkerExecutor, non-interactive apt, AgentWorkflow unify) |
| 47 | Storage sandbox + Agent Mode — workspace sandbox, AgentTaskRunner autonomous loop, AgentScreen UI |
| 48 | Rendering fixes — atomic snapshot, alt-screen resize, 30fps throttle, random marker |
| 49 | Scrollback + Persistence + MCP UI — 2000-line ring buffer, TunnelApp Application scope |
| 50 | Project Context + Checkpointing — ProjectContext (git/manifests/file tree), CheckpointManager (undo) |
| 51 | Automated tests + BlockMode fix — 39 unit tests, incremental parse |
| 52 | Agent Mode audit fixes — approval dialog, success detection, Stop cancel |
| 53-57 | Text selection, terminal resize, edit_file tool, SessionTargetResolver |
| 58 | **TaskPlanManager (plan/act/observe/verify) + SFTP for SSH file I/O** |
| 59 | **Native API tool-calling (B-1) + AGP 8.5.2 + Kotlin 2.0.21 + Compose Compiler Plugin (C-2)** |
| 60 | **Audit fixes: HTTPS enforcement, MCP schema validation, Ubuntu download reliability, reflection removal** |
| **Wave 1-2** | **Critical stability, scrollback, agent, sandbox, HOME path, SSH TOFU before auth, rootfs SHA256, secure storage** |
| **Wave 3-4** | **IME input, block live updates, palette recents, diff apply, reaper, autocomplete, grep_content, safe read/delete** |
| **Wave 5-6** | **PtySessionBase, Anthropic Messages API, wide-char, metrics, explorer cd, editor confirm, SFTP limits, agent clarify** |
| **Wave 7-9** | **Release polish, persistent history, export transcript, URL validate, chat export, snippet type-in, SSH host keys (v7.3.0-v7.5.0)** |
| **Wave 10-11** | **Tab rename, bookmarks, copy-output, keep-screen-on, IME fix typed chars vanish (v7.6.0-v7.6.1)** |
| **Wave 12-13** | **Terminal max polish (paste, DECCKM, ExtraKeys, scroll, render), scrollback select/copy, key-repeat, split activate, PTY size (v7.7.0-v7.8.0)** |
| **Wave 14** | **Find scrollback, open-url, mouse/wheel, reconnect keep history (v7.9.0)** |
| **Wave 15-16** | **LazyColumn virtualized scrollback, Unicode code-points, compact ExtraKeys, font zoom pinch fix (v8.0.0-v8.0.1)** |
| **Wave 17** | **AI chat, Auto-Pilot, and Agent UX polish (v8.1.0)** |

**Total: 161 commits, ~18,800 baris code, 55 Kotlin files + 1 C++ file + 14 test files**

## Release v8.1.0

Release v8.1.0 adalah **major release** yang menggabungkan 17 waves development (Wave 1-17) di atas Phase 60. Total **161 commits, ~18,800 baris code, 55 Kotlin files + 1 C++ file + 14 test files**.

### Highlights v8.1.0

- **Anthropic Native API** (Wave 5) — Claude via Messages API, bukan hanya OpenAI-compat
- **PtySessionBase** (Wave 5) — shared PTY session core untuk local + proot
- **Persistent command history** (Wave 8) — survive app restart, shared for autocomplete
- **Chat + transcript export** (Wave 8-9) — export AI chat ke txt, terminal output ke txt
- **URL validator** (Wave 8) — auto-prepend https://, reject HTTP untuk non-localhost
- **Bookmarks** (Wave 10) — `bookmark add/list/go/remove`, quick cd
- **Tab rename** (Wave 10) — long-press tab untuk rename label
- **Safe paste** (Wave 12) — bracketed paste mode, flatten newlines, 64KB cap
- **DECCKM** (Wave 12) — application cursor keys untuk vim/less
- **ExtraKeys** (Wave 12-14) — `^C ^D ^Z ^L ^U ^W` + F1-F12 + `^A`/`^E` + `A+`/`A−`
- **Scrollback select/copy** (Wave 13) — select dari scrollback, copy ke clipboard
- **Find scrollback** (Wave 14/20) — `tt-find <query>` search di scrollback (shell find bebas)
- **Open URL** (Wave 14) — detect http(s) URL di output, open external browser
- **Mouse wheel** (Wave 14) — mode 1000/1006 (SGR), scroll terminal
- **Reconnect keep history** (Wave 14) — restart session tanpa hapus scrollback
- **LazyColumn virtualized** (Wave 15) — render 2000 scrollback rows tanpa lag
- **Unicode code-points** (Wave 15) — combining marks, CJK width 2, emoji width 2
- **Font zoom fix** (Wave 16) — pinch-zoom gesture-local, 0.5sp snap, range 8-28sp
- **AI chat UX** (Wave 17) — Stop stream, bubble + Copy/Retry, empty chips, Auto-Pilot progress, Agent scroll/pause, API key mask, max tokens, FAB AI
- **14 test files** — TerminalEmulator, AiToolCall, Permission, Wave utils (06-16)

### Backlog Status

| ID | Item | Status | Phase |
|---|---|---|---|
| B-1 | Native API tool-calling | ✅ DONE | 59 |
| B-4 | Checkpointing/Undo | ✅ DONE | 50 |
| B-5 | Project Context Awareness | ✅ DONE | 50 |
| C-2 | AGP + Kotlin upgrade | ✅ DONE | 59 |
| C-5 | Automated tests | ✅ DONE | 51+ (14 files) |

Lihat [GitHub Release v8.1.0](https://github.com/NanoMindExplorer/tunnel-terminal/releases/tag/v8.1.0) untuk changelog lengkap.

## Credits

**Developer:** NanoMind (https://github.com/NanoMindExplorer)

### Support

Jika fitur ini bermanfaat, dukung pengembangan lanjutan:

- 💰 Crypto: lihat di Settings → About
- ⭐ Star repo: https://github.com/NanoMindExplorer/tunnel-terminal
- 🐛 Bug report: buka issue dengan label sesuai phase terkait

## License

Open source. Contributions welcome at https://github.com/NanoMindExplorer/tunnel-terminal
