# proot binary — cara mendapatkan & menempatkannya

File ini (README) saja tidak cukup untuk fitur Ubuntu Linux Environment jalan.
Kamu harus menaruh binary `proot` di folder ini dengan nama **`proot`** (tanpa
ekstensi) sebelum melakukan `./gradlew assembleRelease`.

App membaca binary ini dari `assets/proot/proot` saat instalasi Ubuntu pertama
kali, menyalinnya ke `context.filesDir/linux/proot`, lalu `setExecutable(true, true)`.
Kalau file ini tidak ada, instalasi Ubuntu akan gagal dengan pesan jelas ke user.

## Cara tercepat: ambil binary jadi dari package Termux

Jalankan ini di komputer Linux/macOS/WSL (BUKAN di Android app):

```bash
# 1. Cari nama file .deb proot arm64 terbaru di packages.termux.dev
#    Buka di browser: https://packages.termux.dev/apt/termux-main/pool/main/p/proot/
#    Cari file berakhilan _aarch64.deb. Ganti URL di bawah sesuai versi terbaru.

wget https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_<versi>_aarch64.deb

# 2. Ekstrak .deb (itu cuma arsip ar berisi data.tar.xz)
ar x proot_<versi>_aarch64.deb

# 3. Ekstrak data.tar.xz
tar -xf data.tar.xz

# 4. Binary proot ada di:
#    ./data/data/com.termux/files/usr/bin/proot
cp ./data/data/com.termux/files/usr/bin/proot /path/ke/tunnel-terminal/app/src/main/assets/proot/proot

# 5. Set permission executable (tidak wajib — akan di-set ulang oleh app di runtime,
#    tapi bagus untuk konsistensi)
chmod 755 /path/ke/tunnel-terminal/app/src/main/assets/proot/proot
```

## Cek dependency `libtalloc.so.2`

Beberapa build proot Termux link ke `libtalloc.so.2`. Cek dengan:

```bash
# Di komputer (bukan Android):
readelf -d ./data/data/com.termux/files/usr/bin/proot | grep NEEDED
```

Kalau ada `libtalloc.so.2` di output, kamu juga perlu:
1. Salin `./data/data/com.termux/files/usr/lib/libtalloc.so.2` ke `app/src/main/assets/proot/libtalloc.so.2`.
2. Update `ProotBootstrap.kt` untuk menyalin file ini ke `baseDir/lib/` saat install.
3. Tambahkan `LD_LIBRARY_PATH=${baseDir.absolutePath}/lib` ke `envp` di `ProotShellExecutor.kt`.

Sebagian besar build proot Termux terbaru sudah statically link talloc, jadi sering
tidak perlu. Tapi kalau sesi proot langsung keluar dengan "library not found", ini
penyebabnya.

## Cara alternatif: build dari source

Lebih bersih secara provenance, tapi butuh Android NDK + setup `termux-packages`:

```bash
git clone https://github.com/termux/termux-packages
cd termux-packages
./scripts/run-docker.sh ./build-package.sh proot
# Binary output ada di: ./debs/proot_<versi>_aarch64.deb
# Lanjutkan dengan langkah ekstrak .deb di atas.
```

Pakai cara ini kalau kamu mau kontrol penuh atas patch yang masuk ke binary, atau
mau publish build resmi ke GitHub Releases.

## Arsitektur (ABI)

Saat ini `ProotBootstrap.kt` mendeteksi ABI device dan otomatis pilih rootfs arm64 atau amd64.
Tapi binary proot yang kamu bundle di assets HARUS cocok dengan ABI target APK:

| APK ABI    | proot binary yang harus di-bundle |
|------------|-----------------------------------|
| arm64-v8a  | proot_aarch64 (dari .deb _aarch64) |
| x86_64     | proot_x86_64 (dari .deb _x86_64)   |

Kalau kamu build APK dengan `universalApk = true` (atau ndk abiFilters multiple),
kamu perlu menyediakan binary untuk setiap ABI dan deteksi ABI di runtime di
`ProotBootstrap.kt` untuk pilih binary yang benar.

Untuk simplifikasi, sebaiknya publish APK per-ABI (satu APK arm64, satu APK x86_64)
dan bundle hanya satu binary proot yang cocok di masing-masing.

## Verifikasi binary

Sebelum commit, jalankan (di komputer Linux/WSL dengan multiarch atau di Termux):

```bash
file app/src/main/assets/proot/proot
# Harusnya output: ELF 64-bit LSB shared object, ARM aarch64, ...
```

Kalau outputnya "x86_64" tapi kamu target arm64, binary salah — ulangi langkah ekstrak
dari .deb yang benar.

## Catatan Play Store

Fitur ini melanggar kebijakan Play Store (download + eksekusi binary native saat
runtime). **Distribusikan APK lewat GitHub Releases atau F-Droid saja.** Jangan submit
ke Play Store, atau app kamu akan ditolak/dihapus.
