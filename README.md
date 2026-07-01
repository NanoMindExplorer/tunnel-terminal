# 🚀 Tunnel Terminal

**Tunnel Terminal** adalah aplikasi terminal Android tingkat lanjut (melampaui Termux) yang menggabungkan kekuatan mesin C/C++ NDK (Pseudo-Terminal) dengan AI Copilot Multi-Provider untuk pengembang modern.

![Architecture](https://img.shields.io/badge/Architecture-NDK%20%2B%20Jetpack%20Compose-purple)
![AI](https://img.shields.io/badge/AI-AutoPilot%20Agent-cyan)

## 🌟 Fitur Revolusioner

### 1. True Linux Terminal (C++ NDK PTY)
Tidak menggunakan trik Java `Runtime.exec()`. Tunnel Terminal menggunakan `forkpty()` dari C/C++ untuk membuat sesi terminal asli di kernel Linux Android. Mendukung penuh aplikasi TUI seperti `vim`, `nano`, dan `htop`.

### 2. AI Auto-Pilot (Agentic Workflow)
Alih-alih sekadar memberi saran, AI bisa menjalankan rangkaian perintah secara otomatis. Cukup minta *"Setup server Node.js Express dan jalankan"*, AI akan menginstal, membuat file, dan menjalankannya secara berurutan.

### 3. Multi-Provider AI (No Monopoly)
Pengguna bebas memilih provider AI favorit mereka:
- OpenAI (GPT-4o)
- Anthropic Claude (via OpenRouter)
- Google Gemini
- DeepSeek Coder
- Groq (Llama 3)
- Local Ollama

### 4. UX Mobile-First yang Cerdas
- **Built-in Code Editor:** Ketik `open <file>` untuk membuka UI Editor ramah sentuhan.
- **Volume Key History:** Gunakan tombol Volume Up/Down untuk navigasi riwayat perintah.
- **Pinch to Zoom:** Cubit layar untuk mengatur ukuran font.
- **Smart Modifier Keys:** Tombol CTRL dan ALT yang berfungsi penuh (Ctrl+C untuk kill process).
- **Session Lifecycle Guard:** Terminal tidak akan membeku saat diketik `exit`.

### 5. Storage Access Framework
Ketik `setup-storage` untuk membuat jembatan ke `/sdcard`. Anda bisa mengerjakan proyek web yang bisa diakses langsung oleh aplikasi File Manager HP atau dibagikan ke PC.

## 🛠️ Build From Source
Aplikasi ini dirancang untuk dibangun sepenuhnya menggunakan GitHub Actions CI/CD.
1. Clone repositori ini.
2. Buka tab **Actions** di GitHub.
3. Download *artifact* APK hasil build terbaru.
4. Instal di perangkat Android Anda (min SDK 24 / Android 7.0+).

## 🤝 Kontribusi
Proyek ini dibangun dengan filosofi *full-code*, tanpa *mockup*, dan kejujuran teknis. Kontribusi untuk menambahkan fitur atau mengoptimasi mesin emulator sangat diterima.

---
*Built with ❤️ by NanoMindExplorer*
