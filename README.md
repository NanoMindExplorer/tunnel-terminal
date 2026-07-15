# Tunnel Terminal

**Tunnel Terminal** adalah terminal Android AI-native yang merubah cara developer bekerja di perangkat mobile. Menggabungkan mesin C/C++ NDK (Pseudo-Terminal asli) dengan AI Copilot multi-provider, terminal berbasis blok, command palette, tool calling, **lingkungan Linux Ubuntu asli via proot (tanpa root)**, dan banyak lagi.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-Skills%20%2B%20Copilot%20%2B%20Agent%20%2B%20MCP-cyan)
![Linux](https://img.shields.io/badge/Linux-Ubuntu%2024.04%20via%20proot-orange)
![Version](https://img.shields.io/badge/version-8.4.0-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF)
![AGP](https://img.shields.io/badge/AGP-8.5.2-orange)
![Tests](https://img.shields.io/badge/tests-25%20files-green)
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
- **Wave 10-25 UX + AI polish** — bookmarks, safe paste, ExtraKeys, font zoom, AI side panel, storage SAF, Ubuntu download, AI Skills CRUD
- **AI Skills** — custom instruction packs (scope always/chat/agent/local/ubuntu/ssh), inject ke semua jalur AI
- **25 test files** — emulator, tools, IME, layout, selection, Ubuntu paths, skills, storage commands

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
| 36 | **Automated tests** (Phase 51+) | **25 test files (03–25): emulator, tools, IME, selection, Ubuntu, skills** |
| 37 | **Wave 10-25 UX + AI** | **Bookmarks, ExtraKeys, side panel AI, SAF storage, Ubuntu download, Skills** |
| 38 | **Anthropic Native API** (Wave 5) | **Claude via Messages API (`apiStyle: anthropic`)** |
| 39 | **AI Skills** (Wave 25) | **CRUD skill, scope, keyword trigger, inject chat/agent/all sessions** |
| 40 | **AI side panel** (Wave 21) | **Chat/Flow/Skill/Set di kanan — terminal tetap terlihat** |
| 41 | **Device storage** (Wave 19) | **SAF + MediaStore Download + storage-* commands** |
| 42 | **Ubuntu AI paths** (Wave 23) | **write_file → /root guest; Agent cd /root; bind /mnt/workspace** |

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

**UX Wave 15–16:** LazyColumn virtualized scrollback, Unicode, ExtraKeys compact, font zoom pinch fix (v8.0.x).

**UX Wave 17–18:** AI chat UX + terminal display (line metrics, IME wipe guard) (v8.1.x).

**UX Wave 19:** Storage nyata ke Download/Documents (SAF DocumentFile + MediaStore + `storage-*`).

**UX Wave 20–20b:** Terminal polish (dirty trail, HOME/END, volume focus) + **seleksi text akurat** (layoutInfo hit-test) (v8.2.x).

**UX Wave 21:** AI Copilot **panel kanan** (Chat / Flow / Skill / Set) — terminal kiri tetap terlihat (v8.3.0).

**UX Wave 22:** Ubuntu rootfs download di **IO thread**, multi-mirror, resume, extract fallback (v8.3.1).

**UX Wave 23:** AI/Agent/path sinkron dengan Ubuntu (`/root`, bind workspace) (v8.3.2).

**UX Wave 24:** Paste di kolom chat AI + Agent (📋), multi-line, snippet `>_` output terminal (v8.3.3).

**UX Wave 25:** **AI Skills** — tambah/edit/hapus, scope, keyword, inject ke chat & agent (v8.4.0).

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
3. App menyalin binary `proot` dari assets, download Ubuntu Base rootfs (~29MB) dari `cdimage.ubuntu.com` (multi-URL + resume + SHA256), ekstrak ke `filesDir/linux/ubuntu/`, setup DNS
4. Setelah selesai, tab Ubuntu terbuka — `DEBIAN_FRONTEND=noninteractive apt-get update && apt-get install -y git`
5. AI di tab Ubuntu: `write_file` → guest `/root/…`; workspace Android di `/mnt/workspace`
6. Uninstall: Command Palette → "Manage Linux Environment" → Uninstall

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

# Full debug (flavor full + proot)
./gradlew assembleFullDebug
# Output: app/build/outputs/apk/full/debug/*.apk

# Run unit tests
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
| **Wave 17** | **AI chat, Auto-Pilot, Agent UX polish (v8.1.0)** |
| **Wave 18** | **Terminal display: line metrics, no clip, IME wipe guard (v8.1.1)** |
| **Wave 19** | **Device storage SAF + MediaStore Download + storage-* (v8.2.0)** |
| **Wave 20–20b** | **Terminal polish + accurate text selection hit-test (v8.2.x)** |
| **Wave 21** | **AI side panel kanan — Chat/Flow/Skill/Set (v8.3.0)** |
| **Wave 22** | **Ubuntu download IO thread + multi-mirror + resume (v8.3.1)** |
| **Wave 23** | **AI/Agent path integration with Ubuntu proot (v8.3.2)** |
| **Wave 24** | **Paste di chat AI & Agent + terminal snippet (v8.3.3)** |
| **Wave 25** | **AI Skills system — CRUD + inject all AI paths (v8.4.0)** |

**Total: 60+ Kotlin sources + NDK + 25 unit test files · version 8.4.0 (versionCode 61)**

## Release v8.4.0

Release **v8.4.0** menggabungkan Wave 18–25 di atas v8.1.x: storage perangkat, terminal polish, AI side panel, Ubuntu download/AI path, paste chat, dan **AI Skills**.

### Highlights v8.4.0

- **AI Skills** — skill custom + built-in; scope always/chat/agent/local/ubuntu/ssh; keyword trigger; budget inject
- **AI side panel** — Chat | Flow | Skill | Set di kanan; terminal kiri tetap terlihat saat AI bekerja
- **Paste chat** — tombol 📋 + multi-line; `>_` tempel output terminal ke chat
- **Device storage** — `setup-storage`, `storage-ls/put/get/write`, `storage-save-download` (Download publik)
- **Text selection** — long-press/drag hit-test akurat (LazyList layoutInfo)
- **Ubuntu install** — download di IO thread (bukan Main), multi-URL, Range resume, SHA256, extract fallback
- **Ubuntu AI** — `write_file` → `/root/…`; Agent `cd /root`; bind `/mnt/workspace`
- **Terminal polish** — dirty trailing-edge, HOME/END xterm, volume keys tidak curi media di drawer

### AI Skills (cara pakai)

1. Buka panel AI → tab **Skill**
2. **+ Skill** atau edit built-in (Ubuntu Expert, Safety, dll.)
3. Set **scope** (mis. hanya `ubuntu` + `chat`) dan opsional **keywords**
4. Chat / Agent otomatis inject skill yang cocok ke system prompt

### Backlog Status

| ID | Item | Status |
|---|---|---|
| B-1 | Native API tool-calling | ✅ |
| B-4 / B-5 | Checkpoint + Project Context | ✅ |
| C-2 / C-5 | AGP/Kotlin + unit tests | ✅ |
| Wave 19–25 | Storage, selection, side panel, Ubuntu, paste, skills | ✅ |

Lihat [GitHub Releases](https://github.com/NanoMindExplorer/tunnel-terminal/releases) untuk APK terbaru.

## Credits

**Developer:** NanoMind (https://github.com/NanoMindExplorer)

### Support

Jika fitur ini bermanfaat, dukung pengembangan lanjutan:

- 💰 Crypto: lihat di Settings → About
- ⭐ Star repo: https://github.com/NanoMindExplorer/tunnel-terminal
- 🐛 Bug report: buka issue dengan label sesuai phase terkait

## License

Open source. Contributions welcome at https://github.com/NanoMindExplorer/tunnel-terminal
