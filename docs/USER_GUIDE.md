# Tunnel Terminal — Panduan Penggunaan Lengkap

**Versi:** 8.1.0 (Wave 17) | **Platform:** Android 7.0+ (API 24+) | **ABI:** arm64-v8a | **Kotlin:** 2.0.21 | **AGP:** 8.5.2

---

## Daftar Isi

1. [Instalasi](#1-instalasi)
2. [Mulai Cepat](#2-mulai-cepat)
3. [Terminal Dasar](#3-terminal-dasar)
4. [Input & Keyboard](#4-input--keyboard)
5. [Multi-Tab & Split Pane](#5-multi-tab--split-pane)
6. [Block Mode](#6-block-mode)
7. [AI Copilot](#7-ai-copilot)
8. [AI Tool Calling](#8-ai-tool-calling)
9. [Permission System](#9-permission-system)
10. [Command Palette (Ctrl+K)](#10-command-palette-ctrlk)
11. [@context Mentions](#11-context-mentions)
12. [SSH Client](#12-ssh-client)
13. [Linux Environment (Ubuntu via proot)](#13-linux-environment-ubuntu-via-proot)
14. [Code Editor](#14-code-editor)
15. [File Explorer](#15-file-explorer)
16. [Themes](#16-themes)
17. [Workspace Sessions](#17-workspace-sessions)
18. [Voice Input](#18-voice-input)
19. [Agent Workflows](#19-agent-workflows)
20. [Agent Mode](#20-agent-mode)
21. [MCP Protocol](#21-mcp-protocol)
22. [Storage Access (SAF)](#22-storage-access-saf)
23. [Pinch-to-Zoom & Font Size](#23-pinch-to-zoom--font-size)
24. [Bookmarks](#24-bookmarks)
25. [Scrollback Search & URL Open](#25-scrollback-search--url-open)
26. [Mouse & ExtraKeys](#26-mouse--extrakeys)
27. [Safe Paste & DECCKM](#27-safe-paste--decckm)
28. [Chat & Transcript Export](#28-chat--transcript-export)
29. [Settings AI Provider](#29-settings-ai-provider)
30. [Troubleshooting](#30-troubleshooting)

---

## 1. Instalasi

### Build dari Source

```bash
git clone https://github.com/NanoMindExplorer/tunnel-terminal.git
cd tunnel-terminal
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Install ke Perangkat

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Atau transfer APK ke HP dan install manually (enable "Install from unknown sources").

### Requirements

- Android 7.0 (API 24) atau newer
- ~20MB storage
- Internet connection untuk AI features

---

## 2. Mulai Cepat

Saat app pertama dibuka, Anda akan melihat:

```
╔══════════════════════════════════════════════════════════════╗
║       TUNNEL TERMINAL v5.1.0 - AI Native Dev Environment       ║
║          NDK PTY + Multi-Provider AI Copilot                  ║
╚══════════════════════════════════════════════════════════════╝
─ System Info ────────────────────────────────────────────────
  OS        : Android 14 (API 34)
  Device    : Samsung Galaxy S24
  CPU Arch  : arm64-v8a
  ...
─ Quick Help ─────────────────────────────────────────────────
  help              Tampilkan menu bantuan lengkap
  setup-storage     Bridge ke /sdcard via SAF
  ...
```

### Coba Ketik

1. Tap layar terminal → keyboard muncul
2. Ketik `ls` → tekan Enter
3. Ketik `pwd` → tekan Enter
4. Ketik `help` → tekan Enter untuk lihat semua command

---

## 3. Terminal Dasar

Tunnel Terminal menggunakan `/system/bin/sh` Android sebagai shell. Command yang tersedia:

| Command | Fungsi |
|---------|--------|
| `ls`, `ls -la` | List file/direktori |
| `cd <dir>` | Pindah direktori |
| `pwd` | Cetak direktori aktif |
| `cat <file>` | Baca file |
| `echo <text>` | Cetak text |
| `mkdir <dir>` | Buat direktori |
| `rm <file>` | Hapus file |
| `cp`, `mv` | Copy/move |
| `grep <pattern> <file>` | Cari text |
| `ps` | List proses |
| `kill <pid>` | Hentikan proses |
| `df -h` | Disk usage |
| `head`, `tail` | Baca awal/akhir file |
| `sed`, `awk` | Text processing |

### Built-in Commands (tidangani lokal, bukan shell)

| Command | Fungsi |
|---------|--------|
| `help` | Menu bantuan |
| `clear` | Bersihkan layar |
| `setup-storage` | Bridge ke /sdcard |
| `storage-status` | Status storage |
| `storage-reset` | Reset storage |
| `system-info` | Info sistem (MOTD) |
| `open <file>` | Buka file di editor |

---

## 4. Input & Keyboard

### Soft Keyboard (HP)

1. **Tap layar terminal** → keyboard muncul
2. Ketik karakter → muncul di terminal
3. **Enter** → eksekusi command
4. **Backspace** → hapus karakter

### Physical Keyboard (Bluetooth/USB)

| Key | Action |
|-----|--------|
| Enter | Eksekusi command |
| Backspace | Hapus karakter |
| Tab | Autocomplete |
| ↑ ↓ ← → | Navigasi (vim, less, history) |
| Home / End | Awal/akhir baris |
| PageUp / PageDown | Scroll |
| Delete | Hapus karakter di kanan cursor |
| F1-F4 | Function keys |
| Ctrl+C | SIGINT (kill proses) |
| Ctrl+D | EOF (exit shell) |
| Ctrl+L | Clear screen |
| Ctrl+A / Ctrl+E | Awal/akhir baris (readline) |
| Ctrl+K / Ctrl+U | Hapus ke akhir baris / seluruh baris |
| Ctrl+W | Hapus kata sebelumnya |
| Ctrl+R | Reverse search history |
| Alt+key | ESC prefix (vim meta keys) |

### Extra Keys Bar

Bar di bawah terminal berisi tombol quick access:
- **Row 1 (symbols):** ~ * $ " ' ; & | - / ( ) < > = { } [ ] # ! ? \ @ `
- **Row 2 (control):** ESC TAB CTRL ALT ↑ ↓ ← → HOME END PGUP PGDN BKSP DEL

### Volume Keys

- **Volume Up** → history sebelumnya (older command)
- **Volume Down** → history berikutnya (newer command)
- Hanya aktif saat terminal focused (tidak saat drawer/dialog terbuka)

### Mouse (USB/Bluetooth)

- **Click** → focus terminal + show keyboard
- **Scroll wheel** → scroll terminal history

---

## 5. Multi-Tab & Split Pane

### Multi-Tab

- Tap **+** di TabBar → buat tab baru
- Tap tab → switch ke tab tersebut
- Tap **X** di tab → tutup tab
- Setiap tab punya command history terpisah (per-tab)

### Split Pane (tmux-style)

1. Buka minimal 2 tab
2. Tap **⬡** di TabBar → aktifkan split mode
3. Dua terminal tampil side by side:
   - **Left**: terminal aktif (dengan keyboard input)
   - **Right**: terminal tab lain (view-only)
4. Tap pane kanan → switch active tab ke tab tersebut
5. Tap **⬡** lagi → kembali ke single pane

---

## 6. Block Mode

Block Mode menampilkan terminal sebagai **card diskret** (seperti Warp):

### Mengaktifkan Block Mode

Tap **⊞** di TabBar → toggle block mode

### Yang Tampil

Setiap command + output menjadi card dengan:
- **Status icon**: ✓ success, ✗ error, ⏳ running
- **Timestamp**: kapan command dijalankan
- **Command**: `tulis command di sini`
- **Output**: collapsible (bisa di-expand/collapse)

### Actions per Block

| Button | Fungsi |
|--------|--------|
| Expand/Collapse | Tampilkan/sembunyikan output |
| ↻ Rerun | Jalankan command lagi |
| 🤖 Explain | Kirim block ke AI untuk dijelaskan |

### Kapan Pakai Block Mode vs Raw Mode

- **Block Mode**: review command history, presentasi, debugging
- **Raw Mode**: TUI apps (vim, htop, less), aktif input interaktif

---

## 7. AI Copilot

### Membuka AI Drawer

Tap tombol **AI** di TabBar (kanan atas) → drawer terbuka dari kiri

### Chat Tab

1. Ketik prompt di input bar bawah
2. Tap **Kirim** (atau tekan Enter di physical keyboard)
3. AI response muncul **token-by-token** (streaming SSE)
4. Response dirender sebagai **markdown** (headers, code blocks, lists, dll)

### Contoh Prompt

```
Tampilkan 5 proses termahal di sistem
```
```
Buat file hello.py yang print "Hello World"
```
```
Cari semua file .log ukuran > 10MB
```
```
Jelaskan output command terakhir
```

### AI Command Execution

Jika AI response berisi code block `bash`, akan muncul tombol:
- **▶ Run** — jalankan command di terminal aktif
- **💾 Save** — simpan sebagai snippet (workflow)

Jika multiple code blocks:
- **Run Auto-Pilot** — jalankan semua command berurutan
- Auto-Pilot menunggu prompt shell muncul sebelum command berikutnya
- Auto-Pilot mendeteksi error mid-sequence dan berhenti

### Multi-turn Memory

AI mengingat **20 pesan terakhir** dalam percakapan. Anda bisa ask follow-up:

```
Anda: Buat file test.py
AI: [code block: echo "print('test')" > test.py]
Anda: Sekarang jalankan
AI: [code block: python test.py]
```

### Clear Chat

Tap tombol **🗑** di header drawer → hapus semua pesan + reset memory

### Image Vision

1. Tap **📎** di input bar
2. Pilih gambar dari gallery (bisa multi-select)
3. Gambar muncul sebagai chips preview
4. Ketik prompt: "Apa di gambar ini?"
5. Kirim → AI menerima gambar (base64) + text

**Catatan:** Pastikan model AI support vision (gpt-4o, gemini-1.5, claude-3, dll). Cek 👁 icon di Settings.

---

## 8. AI Tool Calling

AI bisa memanggil **tools** untuk membaca/mengubah sistem Anda:

### Read-Only Tools (tanpa permission)

| Tool | Fungsi |
|------|--------|
| `read_file(path)` | Baca isi file |
| `list_files(dir)` | List direktori |
| `search_files(pattern, dir)` | Cari file |
| `get_terminal_output()` | Ambil output terminal |

### Destructive Tools (butuh permission)

| Tool | Fungsi |
|------|--------|
| `write_file(path, content)` | Tulis file (dengan diff preview) |
| `delete_file(path)` | Hapus file |
| `run_command(cmd)` | Jalankan command di terminal |

### Cara AI Memanggil Tools

AI menyertakan `<tool_call>` tag di response:

```xml
<tool_call>{"tool":"read_file","args":{"path":"/sdcard/test.txt"}}</tool_call>
```

Sistem memparse tag ini, mengeksekusi tool, dan mengirim hasilnya kembali ke AI untuk analisis lebih lanjut.

---

## 9. Permission System

### Permission Dialog

Saat AI request destructive tool, dialog muncul:

```
🔐 AI Permission Request

AI wants to execute:
run_command(cmd="ls -la /sdcard")

AI reasoning: Saya perlu melihat isi /sdcard
⚠ This tool can modify your system.

[Allow once]  [Deny]
```

### Permission Options

| Button | Fungsi |
|--------|--------|
| **Allow once** | Eksekusi tool ini sekali, prompt lagi next time |
| **Always allow** | Eksekusi tanpa prompt di masa depan (hanya untuk write_file) |
| **Deny** | Tolak, AI diberi tahu permission ditolak |

### Keamanan

- `run_command` dan `delete_file` **selalu** butuh prompt (no "Always Allow")
- `write_file` menampilkan **diff preview** sebelum apply
- Tool calls di markdown code blocks **tidak dieksekusi** (anti prompt injection)

---

## 10. Command Palette (Ctrl+K)

### Membuka

Tap **⌘K** di TabBar, atau tekan Ctrl+K di physical keyboard

### Fuzzy Search

Ketik untuk mencari across 5 categories:

| Category | Contoh |
|----------|--------|
| **AI** | Ask AI to explain, fix errors, discover MCP, voice input |
| **Navigation** | New tab, close tab, toggle split/block |
| **Settings** | Open AI Settings, File Explorer, Workspace, SSH |
| **Commands** | Run ls -la, pwd, clear, help |
| **Recent** | 5 command terakhir dari history |

### Keyboard Navigation

| Key | Action |
|-----|--------|
| ↑ ↓ | Navigasi item |
| Enter | Eksekusi item terpilih |
| Escape | Tutup palette |

---

## 11. @context Mentions

Di AI chat input, ketik `@` untuk mention context:

### Supported Mentations

| Mention | Resolves To |
|---------|-------------|
| `@file:/sdcard/test.txt` | Isi file (max 5000 chars) |
| `@block:3` | Output command block ke-3 |
| `@command:"ls -la"` | Execute command via MarkerExecutor + attach output (with exit code) as context |
| `@terminal` | Output terminal aktif (max 3000 chars) |
| `@snippet:my_workflow` | Saved snippet |

### Contoh Penggunaan

```
@file:/sdcard/config.json tolong perbaiki format JSON ini
```
```
@terminal jelaskan apa yang salah dengan output ini
```
```
@block:2 kenapa command ini error?
```

### Auto-Complete

Ketik `@` → suggestions muncul (file, block, snippet, dll)

---

## 12. SSH Client

### Connect ke Server

1. Tap **🔌** di TabBar → SSH Connect dialog
2. Isi form:
   - **Name**: nama koneksi (untuk tab label)
   - **Host**: IP atau hostname (e.g., 192.168.1.1)
   - **Port**: 22 (default)
   - **Username**: e.g., root
   - **Password** atau **Private Key Path**
3. Tap **Connect**

### SSH Tab

- Tab SSH muncul di TabBar bersama tab lokal
- Tap tab → switch antara lokal/SSH
- Semua fitur (AI, block mode, split pane) bekerja di SSH tab
- Type `exit` di SSH tab → disconnect

### Authentication

| Method | Cara |
|--------|------|
| Password | Isi password di form |
| Private Key | Isi path ke key file (e.g., `/sdcard/id_rsa`) |
| Key + Passphrase | Isi key path + passphrase |

### Host Key Verification

- First connect: host key di-accept (TOFU — Trust On First Use)
- Subsequent: host key diverifikasi
- Jika key berubah → dialog blocking muncul dengan fingerprint lama vs baru (Phase 41 fix).
  User harus actively pilih [Batalkan] (default, aman) atau [Lanjutkan — tidak disarankan].

---

## 13. Linux Environment (Ubuntu via proot)

> ⭐ **Fitur unggulan Phase 38-39** — Jalankan Ubuntu 24.04 asli di Android tanpa root.

### Cara Kerja

Tunnel Terminal menambahkan jalur ketiga di samping Local shell dan SSH: jalankan
proot + Ubuntu rootfs. PTY layer yang sama (`native-lib.cpp` → `forkpty()`) digunakan,
hanya target exec yang berbeda (`execve("<proot>", ...)` alih-alih `/system/bin/sh`).

### Instalasi

1. Tap tombol 🐧 di TabBar (sebelah tombol 🔌 SSH)
2. Dialog instalasi muncul → tap **Install**
3. App akan:
   - Salin binary `proot` dari assets APK ke storage app
   - Cek storage cukup (minimal 1.5GB free)
   - Download Ubuntu Base rootfs (~30-60MB) dari cdimage.ubuntu.com (dengan fallback URL)
   - Ekstrak rootfs via `/system/bin/tar`
   - Setup DNS (8.8.8.8 / 1.1.1.1)
   - Validasi binary proot (`proot --version`)
4. Setelah selesai, tab Ubuntu terbuka otomatis

### Penggunaan

```bash
# Setelah tab Ubuntu terbuka:
whoami          # root (proot fake-root dengan -0)
uname -a        # Linux ... Ubuntu 24.04 ...
apt update
DEBIAN_FRONTEND=noninteractive apt-get install -y git python3 nodejs
git clone https://github.com/your/repo
cd repo && python3 main.py
```

### Troubleshooting Proot

| Masalah | Solusi |
|---------|--------|
| Sesi mati dalam <2 detik | App auto-retry dengan `PROOT_NO_SECCOMP=1`. Restart sekali lagi. |
| `apt update` gagal resolve host | DNS di-refresh tiap start sesi. Restart tab. |
| `systemctl start nginx` tidak jalan | proot tidak support systemd. Jalankan manual: `nginx -g "daemon off;" &` |
| Storage penuh saat `apt install` | Uninstall Linux Environment (Manage → Uninstall), lalu install ulang |
| Performa lambat untuk compile C++ | Overhead ptrace proot signifikan. Cocok untuk pakai tool, kurang untuk compile project besar |

### Uninstall

1. Command Palette (Ctrl+K) → "Manage Linux Environment"
2. Tap **Uninstall**
3. Semua file rootfs + binary proot dihapus dari storage app

### Catatan Distribusi

⚠️ Fitur ini tidak kompatibel dengan Google Play Store (download+exec binary native
saat runtime melanggar kebijakan). Build "Full" (GitHub Releases/F-Droid) memiliki
fitur ini; build "playstore" menyembunyikan tombol 🐧 dan menonaktifkan fungsi.

---

## 14. Code Editor

### Membuka Editor

Ketik `open <file>` di terminal:

```
open test.kt
open /sdcard/Documents/script.py
```

### Fitur Editor

- **Syntax highlighting**: Kotlin, Python, JS/TS, Shell, JSON, XML, YAML, Markdown
- **Line numbers**: gutter di sisi kiri
- **Horizontal scroll**: baris panjang tidak wrap
- **Modified indicator**: ● jika ada perubahan belum disimpan
- **Save**: 💾 Save (tanpa close) atau Save & Close
- **File size limit**: max 5MB

### Syntax Highlighting Colors

Warna mengikuti tema aktif (ANSI palette):
- **Keywords** (fun, val, class): magenta/purple
- **Strings** ("hello"): green
- **Comments** (// comment): gray italic
- **Numbers** (42): yellow
- **Functions** (myFunc()): cyan
- **Types** (String): blue

---

## 14. File Explorer

### Membuka

Tap **📁** di TabBar → file explorer dialog

### Navigasi

| Action | Cara |
|--------|------|
| Buka folder | Tap folder |
| Ke parent | Tap ↑ (arrow up) |
| Ke home | Tap 🏠 (home) |
| Refresh | Tap 🔄 (refresh) |

### File Actions

| Action | Cara |
|--------|------|
| Buka file di editor | Tap file |
| `cd` ke folder di terminal | Tap folder |

### File Icons

| Type | Icon |
|------|------|
| Folder | 📁 (blue) |
| Code file (.kt, .py, .js) | 📄 (green) |
| Image (.png, .jpg) | 🖼 |
| Other | 📄 (gray) |

---

## 15. Themes

### Ganti Tema

1. Buka AI drawer (tap **AI**)
2. Tap **Settings** → **Theme** sub-tab
3. Pilih dari 6 tema:

| Tema | Vibe |
|------|------|
| **Matrix** | Hijau neon di hitam (default) |
| **Dracula** | Dark purple/pink |
| **Solarized Dark** | Ergonomis, dark blue |
| **Monokai Pro** | Classic Sublime Text |
| **Nord** | Arctic blue |
| **Tokyo Night** | Modern dark |

### Yang Berubah

- Terminal background + foreground
- ANSI 16-color palette
- UI drawer (buttons, text, cards)
- Syntax highlighting colors
- Cursor color

### Font Size

- **Pinch-to-zoom** di terminal → ubah font size
- Font size **persist** across tab switch + app restart
- Range: 8sp - 24sp

---

## 16. Workspace Sessions

### Save Tab Set

1. Buka beberapa tab (misal: 3 tab dengan direktori berbeda)
2. Tap **💾** di TabBar → workspace dialog
3. Ketik nama (misal: "debug-project")
4. Tap **Save**

### Restore

1. Tap **💾** di TabBar
2. Pilih session dari list
3. Tap **Restore** → semua tab di-recreate + `cd` ke direktori masing-masing

### Delete

Tap **Delete** di session card

---

## 17. Voice Input

### Cara Pakai

1. Buka Command Palette (⌘K)
2. Pilih **"Voice Input (speak AI prompt)"**
3. Speak prompt Anda (dalam Bahasa Indonesia)
4. AI drawer otomatis terbuka + prompt dikirim

### Contoh

```
Speak: "tampilkan lima proses termahal"
→ AI drawer terbuka
→ AI menerima: "tampilkan lima proses termahal"
→ AI response dengan command
```

### Bahasa

- **Bahasa Indonesia** (id-ID) — default
- Membutuhkan Google app atau speech recognizer bawaan

---

## 18. Agent Workflows

### Apa Itu

Multi-step AI agent sequence yang bisa di-save dan di-replay:

| Step Type | Fungsi |
|-----------|--------|
| **AI_STEP** | Ask AI dengan prompt tertentu |
| **COMMAND_STEP** | Jalankan command di terminal |
| **DELAY_STEP** | Tunggu N milidetik |
| **CONDITIONAL_STEP** | Jika output mengandung X, jalankan Y |

### Menjalankan Workflow

1. Buka Command Palette (⌘K)
2. Cari "Run workflow: <nama>"
3. Pilih → workflow dijalankan step by step

### Contoh Workflow (konseptual)

```
Step 1 (AI_STEP): "Analisa error di terminal"
Step 2 (COMMAND_STEP): run AI suggested fix command
Step 3 (DELAY_STEP): wait 2000ms
Step 4 (AI_STEP): "Apakah error sudah teratasi?"
```

**Catatan:** Saat ini workflow hanya bisa dibuat via code (tidak ada UI builder). Lihat `AgentWorkflowManager.addWorkflow()`.

---

## 19. MCP Protocol

### Apa Itu MCP

MCP (Model Context Protocol) adalah standard dari Anthropic untuk AI tool interoperability. Server MCP menyediakan tools yang bisa AI panggil.

### Connect ke MCP Server

1. Buka Command Palette (⌘K)
2. Pilih **"Discover MCP Tools"**
3. Semua MCP servers yang dikonfigurasi akan di-query untuk tools
4. Tools tersedia untuk AI via `mcp.servername.toolname`

### Keamanan

- `http://` ditolak kecuali `localhost`/`127.0.0.1`
- API key via HTTPS only
- Permission same as regular tools (destructive = prompt)

**Catatan:** MCP server management UI belum tersedia. Servers dikonfigurasi via `McpManager.addServer()`.

---

## 20. Storage Access (SAF)

### Setup

Ketik `setup-storage` di terminal → SAF folder picker terbuka

### Pilih Folder

1. Pilih folder (misal: /sdcard atau Documents)
2. Tap **Allow**
3. Bridge dibuat di `~/storage/shared/`

### Akses File

Setelah setup, Anda bisa:
- `cd ~/storage/shared/` → akses file dari file manager
- `cat ~/storage/shared/Documents/notes.txt`
- `open ~/storage/shared/test.py`

### Status

- `storage-status` → cek konfigurasi
- `storage-reset` → reset konfigurasi

---

## 21. Pinch-to-Zoom

### Cara

Cubit layar terminal:
- **Cubit keluar** (zoom in) → font membesar
- **Cubit masuk** (zoom out) → font mengecil

### Range

- Minimum: 8sp
- Maximum: 24sp
- Default: 12sp

### Persist

Font size **tersimpan** dan tidak reset saat:
- Switch tab
- Switch mode (raw ↔ block ↔ split)
- Close + reopen app

---

## 22. Settings AI Provider

### Membuka Settings

1. Tap **AI** di TabBar → drawer terbuka
2. Tap **Settings** → **AI** sub-tab

### Provider

Tap dropdown (▼) → pilih dari 13 presets:

| Provider | Models |
|----------|--------|
| OpenAI | gpt-4o, gpt-4o-mini |
| DeepSeek | deepseek-chat, deepseek-coder |
| Groq | llama3-8b, llama3-70b |
| OpenRouter | Claude, Gemini, ratusan model |
| Gemini | gemini-1.5-flash, gemini-1.5-pro |
| Anthropic | claude-3-5-sonnet, claude-3-haiku |
| Mistral | mistral-small, mistral-large |
| Together AI | Llama 3, Mixtral |
| Fireworks AI | Llama 3, Mistral |
| Perplexity | sonar models |
| Local (Ollama) | llama3, mistral |
| Local (LM Studio) | local-model |
| **Custom** | bebas masukin baseUrl + model |

### Fetch Models

Tap **🔄 Fetch Models** → fetch daftar model dari `/models` endpoint

### Settings Lain

| Field | Fungsi |
|-------|--------|
| Base URL | API endpoint (auto-filled dari preset) |
| Model Name | Model ID (auto-filled atau pilih dari fetch) |
| API Key | API key provider |
| Timeout | Request timeout (5000-120000 ms) |
| Temperature | Creativity (0.0-2.0) |

### Vision Indicator

Jika model support vision → **👁 Vision** badge muncul

---

## 23. Troubleshooting

### App Crash saat Dibuka

**Penyebab:** (sudah di-fix di Phase 31, pastikan pakai v5.1.0+)

**Jika masih crash:**
1. Cek logcat: `adb logcat | grep -i "tunnel\|fatal\|androidruntime"`
2. Paste log ke issue di GitHub

### Tidak Bisa Mengetik

1. Tap layar terminal → keyboard harus muncul
2. Jika tidak muncul → coba tap lagi
3. Jika masih tidak → coba tutup+buka app
4. Physical keyboard: pastikan BasicTextField focused (tap terminal area)

### Terminal Kosong / Tidak Ada Output

1. Ketik `help` → harus muncul text
2. Jika kosong → kemungkinan native library gagal load:
   - Cek logcat: `adb logcat | grep "TunnelJni\|TerminalJni"`
   - Cek APK berisi .so untuk ABI yang benar (arm64-v8a, armeabi-v7a, x86, x86_64)

### AI Tidak Merespons

1. Cek API Key di Settings
2. Cek koneksi internet
3. Cek Base URL (harus OpenAI-compatible `/v1`)
4. Cek logcat: `adb logcat | grep "AIAgent"`

### SSH Tidak Bisa Connect

1. Cek host + port
2. Cek username + password/key
3. Cek koneksi jaringan ke server
4. Cek logcat: `adb logcat | grep "SshShellExecutor"`

### Pinch-to-Zoom Tidak Berfungsi

1. Cubit dengan **dua jari**
2. Pastikan tidak sedang di block mode (pinch hanya di raw mode)
3. Jika font tidak berubah → coba restart app

### Theme Tidak Berubah

1. Ganti theme di Settings → Theme
2. Jika ada bercak warna lama → ketik `clear` di terminal
3. Theme hanya berlaku ke sel baru; sel lama tetap warna sebelumnya

### Performance Lag

1. Hindari output sangat cepat (`yes`, `find /`) dalam waktu lama
2. Tutup tab yang tidak dipakai
3. Disable block mode saat tidak diperlukan
4. Gunakan font size lebih besar (mengurangi cols/rows → less rendering)

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────────────┐
│                    TUNNEL TERMINAL v5.1.0                    │
├─────────────────────────────────────────────────────────────┤
│ TAB BAR:  [Tab 1 X] [Tab 2 X]  +  📁  💾  🔌  ⬡  ⊞  ⌘K  AI│
├─────────────────────────────────────────────────────────────┤
│ TERMINAL AREA                                                │
│  > ls -la                                                   │
│  > pwd                                                      │
│  > help                                                     │
│                                                              │
│  (tap to focus, pinch to zoom, type to input)               │
├─────────────────────────────────────────────────────────────┤
│ EXTRA KEYS:  ~ * $ " ' ; & | - / ( ) < > = { } [ ] # ! ?  │
│              ESC TAB CTRL ALT ↑ ↓ ← → HOME END PGUP PGDN   │
│              BKSP DEL                                        │
├─────────────────────────────────────────────────────────────┤
│ TabBar buttons:                                              │
│  +      New tab              📁   File Explorer             │
│  💾     Workspace Sessions   🔌   SSH Connect                │
│  ⬡      Split Pane toggle    ⊞    Block Mode toggle         │
│  ⌘K     Command Palette      AI   AI Copilot drawer         │
├─────────────────────────────────────────────────────────────┤
│ Built-in commands:                                           │
│  help  clear  setup-storage  storage-status  storage-reset  │
│  system-info  open <file>                                   │
├─────────────────────────────────────────────────────────────┤
│ @mentions: @file: @block: @command: @terminal @snippet:     │
├─────────────────────────────────────────────────────────────┤
│ AI tools: read_file write_file delete_file run_command      │
│           list_files search_files get_terminal_output       │
└─────────────────────────────────────────────────────────────┘
```

---

Untuk dokumentasi teknis lengkap, lihat [WIKI.md](WIKI.md).
Untuk source code, lihat [GitHub](https://github.com/NanoMindExplorer/tunnel-terminal).
