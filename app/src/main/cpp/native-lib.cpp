#include <jni.h>
#include <unistd.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/stat.h>   /* Phase 45 fix Bug #1: mkdir() */
#include <termios.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>

#define TAG "TunnelTerminalJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/*
 * Membuat sesi Pseudo-Terminal (PTY) baru menggunakan forkpty().
 * Mengembalikan masterFd via parameter output, dan pid sebagai return value.
 * Jika gagal, return -1.
 *
 * Creates a new PTY session via forkpty().
 * Returns: child pid (>0) on success, -1 on failure.
 * Output: masterFd set via pointer.
 */
JNIEXPORT jint JNICALL
Java_com_tunnel_terminal_TerminalJni_createSession(JNIEnv *env, jobject thiz,
                                                    jint rows, jint cols,
                                                    jintArray outFd) {
    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, NULL);

    if (pid < 0) {
        LOGE("forkpty() gagal: %s", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        /* BUG-30 fix: Hanya panggil async-signal-safe functions antara fork-exec.
         * Old code: setenv() + LOGE() (yang lakukan I/O + malloc) sebelum execl.
         * Risk: deadlock jika thread lain pegang malloc lock saat fork.
         * Fix: Lewatkan environment via execve() parameter, bukan setenv().
         * LOGE() dihapus — jika execl gagal, _exit() langsung. */

        /* Build environment array untuk execve (async-signal-safe). */
        extern char **environ;
        char *envp[16];
        int env_idx = 0;

        /* Copy existing environ. */
        for (int i = 0; environ[i] != NULL && env_idx < 12; i++) {
            envp[env_idx++] = environ[i];
        }

        /* Tambahkan TERM, TERM_PROGRAM, HOME.
         * M1 fix: Gunakan static char arrays (writable) bukan string literal cast.
         * Cast (char*)"..." dari const char* adalah UB — bisa segfault di read-only memory. */
        static char term_env[] = "TERM=xterm-256color";
        static char term_prog_env[] = "TERM_PROGRAM=tunnel-terminal";
        static char home_env[] = "HOME=/data/data/com.tunnel.terminal/files/home";
        envp[env_idx++] = term_env;
        envp[env_idx++] = term_prog_env;
        envp[env_idx++] = home_env;
        envp[env_idx] = NULL;

        /* Phase 45 fix Bug #1: Pindah ke home directory app SEBELUM exec.
         *
         * OLD BUG: HOME di-set sebagai env var, tapi itu TIDAK memindahkan cwd
         * proses. Shell mewarisi cwd dari proses Android app (defaultnya "/",
         * root filesystem yang read-only & permission-denied untuk app biasa).
         * Akibatnya: prompt "tunnel@android:/$", ls → "Permission denied",
         * mkdir → "Read-only file system".
         *
         * FIX: mkdir() + chdir() ke home directory app SEBELUM execve().
         * - mkdir() buat folder kalau belum ada (idempotent — return -1+EEXIST
         *   kalau sudah ada, yang kita abaikan).
         * - chdir() pindah cwd proses ke home. Shell yang di-exec mewarisi
         *   cwd ini → prompt "tunnel@android:~/files/home$" (atau custom PS1).
         *
         * Keduanya async-signal-safe (POSIX.1), aman dipanggil di child process
         * antara fork-exec — konsisten dengan alasan BUG-30 fix di file ini.
         * Tidak pakai LOGE() di sini karena __android_log_print tidak dijamin
         * async-signal-safe di proses yang baru di-fork (bisa deadlock kalau
         * thread lain pegang malloc lock saat fork). Kalau gagal, lanjut saja —
         * shell tetap jalan, hanya cwd-nya tidak ideal.
         */
        static const char home_dir[] = "/data/data/com.tunnel.terminal/files/home";
        mkdir(home_dir, 0700);  /* idempotent, ignore error if exists */
        chdir(home_dir);        /* move cwd to home; ignore error if fails */

        /* execve adalah async-signal-safe.
         * M1 fix: Gunai static char array untuk argv juga. */
        static char sh_arg[] = "sh";
        char *const argv[] = {sh_arg, NULL};
        execve("/system/bin/sh", argv, envp);
        /* Jika execve gagal — _exit adalah async-signal-safe (exit() tidak). */
        _exit(1);
    }

    /* Proses Induk - atur ukuran terminal awal.
     * Parent - set initial terminal size. */
    if (rows > 0 && cols > 0) {
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short)rows;
        ws.ws_col = (unsigned short)cols;
        if (ioctl(masterFd, TIOCSWINSZ, &ws) < 0) {
            LOGE("ioctl(TIOCSWINSZ) gagal: %s", strerror(errno));
        }
    }

    /* Kirim masterFd kembali ke Java via array output.
     * Send masterFd back to Java via output array. */
    if (outFd != NULL) {
        jint fd = (jint)masterFd;
        env->SetIntArrayRegion(outFd, 0, 1, &fd);
    }

    LOGI("Sesi PTY baru: pid=%d, masterFd=%d", pid, masterFd);
    return (jint)pid;
}

/*
 * Phase 36 (proot/Ubuntu): Sama seperti createSession(), tapi exec ke program
 * custom (misalnya proot) alih-alih hardcode /system/bin/sh. Dipakai untuk
 * sesi Linux environment (proot + rootfs Ubuntu/dsb).
 *
 * PENTING: Semua GetStringUTFChars/GetObjectArrayElement HARUS selesai
 * SEBELUM forkpty(), karena child process (hasil fork di proses Android
 * multi-thread) tidak boleh memanggil JNIEnv sama sekali — sama seperti
 * alasan BUG-30 fix di createSession(). Buffer C lokal (bukan static)
 * supaya ikut ter-copy oleh fork() ke address space child.
 *
 * Same as createSession() but exec's a custom binary (e.g. proot) instead
 * of /system/bin/sh. Used for Linux environment sessions (proot + rootfs).
 *
 * All JNI string/array access MUST finish before forkpty() — child process
 * (forked inside a multithreaded Android process) must not touch JNIEnv.
 */
JNIEXPORT jint JNICALL
Java_com_tunnel_terminal_TerminalJni_createSessionExec(JNIEnv *env, jobject thiz,
                                                        jint rows, jint cols,
                                                        jintArray outFd,
                                                        jstring execPath,
                                                        jobjectArray argvArray,
                                                        jobjectArray envArray) {
    /* 1. Salin execPath ke buffer C biasa. */
    char execPathBuf[512];
    const char *cExecPath = env->GetStringUTFChars(execPath, NULL);
    if (cExecPath == NULL) {
        LOGE("createSessionExec: GetStringUTFChars(execPath) gagal");
        return -1;
    }
    strncpy(execPathBuf, cExecPath, sizeof(execPathBuf) - 1);
    execPathBuf[sizeof(execPathBuf) - 1] = '\0';
    env->ReleaseStringUTFChars(execPath, cExecPath);

    /* 2. Salin argv[] ke buffer C biasa (lokal, bukan static — supaya ikut
     *    ter-copy dengan benar oleh fork() ke address space child). */
    const int MAX_ARGS = 64;
    char argvStorage[MAX_ARGS][512];
    char *argvBuf[MAX_ARGS + 1];
    memset(argvStorage, 0, sizeof(argvStorage));
    memset(argvBuf, 0, sizeof(argvBuf));
    jsize argc = env->GetArrayLength(argvArray);
    int n = (argc < MAX_ARGS) ? argc : MAX_ARGS;
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(argvArray, i);
        if (s == NULL) {
            argvBuf[i] = argvStorage[i]; /* empty string */
            continue;
        }
        const char *cs = env->GetStringUTFChars(s, NULL);
        if (cs != NULL) {
            strncpy(argvStorage[i], cs, sizeof(argvStorage[i]) - 1);
            argvStorage[i][sizeof(argvStorage[i]) - 1] = '\0';
            env->ReleaseStringUTFChars(s, cs);
        }
        env->DeleteLocalRef(s);
        argvBuf[i] = argvStorage[i];
    }
    argvBuf[n] = NULL;

    /* 3. Salin envp[] dengan cara yang sama. */
    char envStorage[MAX_ARGS][512];
    char *envpBuf[MAX_ARGS + 1];
    memset(envStorage, 0, sizeof(envStorage));
    memset(envpBuf, 0, sizeof(envpBuf));
    jsize envc = env->GetArrayLength(envArray);
    int m = (envc < MAX_ARGS) ? envc : MAX_ARGS;
    for (int i = 0; i < m; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(envArray, i);
        if (s == NULL) {
            envpBuf[i] = envStorage[i];
            continue;
        }
        const char *cs = env->GetStringUTFChars(s, NULL);
        if (cs != NULL) {
            strncpy(envStorage[i], cs, sizeof(envStorage[i]) - 1);
            envStorage[i][sizeof(envStorage[i]) - 1] = '\0';
            env->ReleaseStringUTFChars(s, cs);
        }
        env->DeleteLocalRef(s);
        envpBuf[i] = envStorage[i];
    }
    envpBuf[m] = NULL;

    /* 4. Dari titik ini sama persis dengan createSession() yang sudah ada. */
    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, NULL);
    if (pid < 0) {
        LOGE("createSessionExec: forkpty() gagal: %s", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        /* Child — hanya async-signal-safe calls. execPathBuf/argvBuf/envpBuf
         * adalah data biasa di stack yang sudah ikut ter-copy oleh fork().
         *
         * Phase 45 note: TIDAK perlu mkdir()+chdir() ke home_dir di sini
         * (beda dengan createSession) karena proot mengatur cwd sendiri
         * lewat flag "-w /root" di argv (lihat ProotShellExecutor.kt).
         * Proot akan chdir ke rootfs Ubuntu setelah exec. */
        execve(execPathBuf, argvBuf, envpBuf);
        /* Jika execve gagal — _exit adalah async-signal-safe (exit() tidak). */
        _exit(1);
    }

    /* Parent — set initial terminal size. */
    if (rows > 0 && cols > 0) {
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short)rows;
        ws.ws_col = (unsigned short)cols;
        if (ioctl(masterFd, TIOCSWINSZ, &ws) < 0) {
            LOGE("createSessionExec: ioctl(TIOCSWINSZ) gagal: %s", strerror(errno));
        }
    }

    if (outFd != NULL) {
        jint fd = (jint)masterFd;
        env->SetIntArrayRegion(outFd, 0, 1, &fd);
    }

    LOGI("Sesi PTY exec baru: pid=%d, masterFd=%d, exec=%s", pid, masterFd, execPathBuf);
    return (jint)pid;
}

/*
 * Menulis data ke terminal (seperti mengetik di keyboard).
 * Writes data to the PTY (keyboard input).
 */
JNIEXPORT void JNICALL
Java_com_tunnel_terminal_TerminalJni_write(JNIEnv *env, jobject thiz,
                                            jint fd, jbyteArray data) {
    if (fd < 0) {
        LOGE("write() dipanggil dengan fd tidak valid: %d", fd);
        return;
    }
    jbyte *bytes = env->GetByteArrayElements(data, NULL);
    if (bytes == NULL) {
        LOGE("GetByteArrayElements mengembalikan NULL");
        return;
    }
    jsize len = env->GetArrayLength(data);
    if (len > 0) {
        /* Phase 20: Loop untuk handle partial writes.
         * write() may write less than requested (especially for large writes
         * or slow PTY). Loop until all bytes written or error.
         *
         * Handle partial writes — write() may not write everything in one call. */
        jsize offset = 0;
        while (offset < len) {
            ssize_t written = write(fd, bytes + offset, len - offset);
            if (written < 0) {
                if (errno == EINTR) continue;  /* Signal interrupted, retry. */
                LOGE("write() ke fd=%d gagal: %s", fd, strerror(errno));
                break;
            }
            if (written == 0) break;  /* Shouldn't happen for PTY, but guard. */
            offset += written;
        }
    }
    env->ReleaseByteArrayElements(data, bytes, 0);
}

/*
 * Mengatur ukuran terminal saat layar di-rotate atau keyboard muncul.
 * Resize PTY window (SIGWINCH).
 */
JNIEXPORT void JNICALL
Java_com_tunnel_terminal_TerminalJni_resize(JNIEnv *env, jobject thiz,
                                             jint fd, jint rows, jint cols) {
    if (fd < 0 || rows <= 0 || cols <= 0) {
        LOGE("resize() parameter tidak valid: fd=%d rows=%d cols=%d", fd, rows, cols);
        return;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    if (ioctl(fd, TIOCSWINSZ, &ws) < 0) {
        LOGE("ioctl(TIOCSWINSZ) gagal: %s", strerror(errno));
    }
}

/*
 * Menutup master fd terminal.
 * Closes the master PTY file descriptor.
 */
JNIEXPORT void JNICALL
Java_com_tunnel_terminal_TerminalJni_close(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return;
    if (close(fd) < 0) {
        LOGE("close(%d) gagal: %s", fd, strerror(errno));
    }
}

/*
 * Mengirim sinyal ke child process shell.
 * Sends a signal to the child shell process.
 *
 * Phase 21: Fix PID recycling risk. Sebelum kirim sinyal, cek apakah pid
 * masih child process kita dengan waitpid(WNOHANG). Jika waitpid return -1
 * (ECHILD), proses sudah bukan child kita (sudah di-reap atau PID recycled)
 * -> jangan kirim sinyal (bisa kena process lain).
 *
 * Returns: 0 on success, -1 on failure.
 */
JNIEXPORT jint JNICALL
Java_com_tunnel_terminal_TerminalJni_killSession(JNIEnv *env, jobject thiz,
                                                  jint pid, jint signal) {
    if (pid <= 1) {
        LOGE("killSession() menolak pid tidak valid: %d", pid);
        return -1;
    }

    /* Phase 21: Cek apakah pid masih child kita sebelum kirim sinyal.
     * Check if pid is still our child before sending signal (PID recycling fix). */
    int status = 0;
    pid_t check = waitpid(pid, &status, WNOHANG);
    if (check == -1) {
        /* errno == ECHILD: proses bukan child kita (sudah di-reap atau PID recycled).
         * Jangan kirim sinyal — bisa kena process lain. */
        LOGI("killSession: pid %d bukan child kita (sudah di-reap), skip kill", pid);
        return 0;
    }
    if (check == pid) {
        /* Child sudah exited, sudah di-reap. Tidak perlu kirim sinyal. */
        LOGI("killSession: pid %d sudah exited (reaped), skip kill", pid);
        return 0;
    }
    /* check == 0: child masih running. Lanjut kirim sinyal. */

    int sig = (signal == 0) ? SIGKILL : signal;
    if (kill(pid, sig) < 0) {
        LOGE("kill(pid=%d, sig=%d) gagal: %s", pid, sig, strerror(errno));
        return -1;
    }
    LOGI("Sinyal %d terkirim ke pid %d", sig, pid);

    /* Reap zombie child process untuk hindari fd/resource leak.
     * Reap zombie to prevent resource leak. */
    for (int i = 0; i < 10; i++) {
        pid_t reaped = waitpid(pid, &status, WNOHANG);
        if (reaped == pid || reaped == -1) break;
        usleep(50000); /* 50ms */
    }
    return 0;
}

/*
 * Phase 21: isAlive dihapus (dead code — tidak pernah dipanggil dari Kotlin).
 * Jika diperlukan di masa depan, gunakan waitpid(pid, &status, WNOHANG)
 * bukan kill(pid, 0) untuk hindari PID recycling false-positive.
 */

}
