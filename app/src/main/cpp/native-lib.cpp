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
                                                    jintArray outFd,
                                                    jstring homePath) {
    /* Wave-1: Copy home path from Java BEFORE fork (child must not touch JNIEnv).
     * Fallback keeps previous hard-coded path for safety if null/empty. */
    char homeDirBuf[512];
    const char *fallbackHome = "/data/data/com.tunnel.terminal/files/home";
    homeDirBuf[0] = '\0';
    if (homePath != NULL) {
        const char *cHome = env->GetStringUTFChars(homePath, NULL);
        if (cHome != NULL) {
            strncpy(homeDirBuf, cHome, sizeof(homeDirBuf) - 1);
            homeDirBuf[sizeof(homeDirBuf) - 1] = '\0';
            env->ReleaseStringUTFChars(homePath, cHome);
        }
    }
    if (homeDirBuf[0] == '\0') {
        strncpy(homeDirBuf, fallbackHome, sizeof(homeDirBuf) - 1);
        homeDirBuf[sizeof(homeDirBuf) - 1] = '\0';
    }

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

        /* Copy existing environ, skip TERM/HOME/TERM_PROGRAM so our values win
         * (most libc env lookups use first match). */
        for (int i = 0; environ[i] != NULL && env_idx < 12; i++) {
            const char *e = environ[i];
            if (strncmp(e, "TERM=", 5) == 0) continue;
            if (strncmp(e, "HOME=", 5) == 0) continue;
            if (strncmp(e, "TERM_PROGRAM=", 13) == 0) continue;
            envp[env_idx++] = environ[i];
        }

        /* Tambahkan TERM, TERM_PROGRAM, HOME.
         * HOME is built from homeDirBuf (stack copy from parent, safe after fork). */
        static char term_env[] = "TERM=xterm-256color";
        static char term_prog_env[] = "TERM_PROGRAM=tunnel-terminal";
        char home_env[512 + 5];
        /* Build HOME=<path> without snprintf (not async-signal-safe). */
        {
            const char *prefix = "HOME=";
            size_t pi = 0;
            while (prefix[pi] != '\0' && pi < sizeof(home_env) - 1) {
                home_env[pi] = prefix[pi];
                pi++;
            }
            size_t hi = 0;
            while (homeDirBuf[hi] != '\0' && pi + hi < sizeof(home_env) - 1) {
                home_env[pi + hi] = homeDirBuf[hi];
                hi++;
            }
            home_env[pi + hi] = '\0';
        }
        envp[env_idx++] = term_env;
        envp[env_idx++] = term_prog_env;
        envp[env_idx++] = home_env;
        envp[env_idx] = NULL;

        /* Phase 45 + Wave-1: mkdir + chdir to real app home (multi-user safe).
         * Both are async-signal-safe (POSIX.1). */
        mkdir(homeDirBuf, 0700);  /* idempotent, ignore error if exists */
        chdir(homeDirBuf);        /* move cwd to home; ignore error if fails */

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

    /*
     * 3. Build envp = parent environ + Java overrides.
     *
     * Wave-30 CRITICAL: Old code passed ONLY the Java env array (3 entries:
     * LD_LIBRARY_PATH, PROOT_TMP_DIR, PATH). That *replaced* the entire
     * process environment. Android's dynamic linker + proot then failed to
     * resolve libtalloc.so.2 / libandroid-shmem.so (binary DT_RUNPATH points
     * at Termux /data/data/com.termux/...) → child exited in ~20ms with no
     * usable shell. Screenshot: "Ubuntu session mati prematur" + PROOT_NO_SECCOMP.
     *
     * Fix: start from parent environ, then overlay Java KEY=value entries.
     */
    const int MAX_ENV = 128;
    const int ENV_STR = 768;
    char envStorage[MAX_ENV][ENV_STR];
    char *envpBuf[MAX_ENV + 1];
    memset(envStorage, 0, sizeof(envStorage));
    memset(envpBuf, 0, sizeof(envpBuf));
    int env_idx = 0;

    extern char **environ;
    if (environ != NULL) {
        for (int i = 0; environ[i] != NULL && env_idx < MAX_ENV - 32; i++) {
            strncpy(envStorage[env_idx], environ[i], ENV_STR - 1);
            envStorage[env_idx][ENV_STR - 1] = '\0';
            envpBuf[env_idx] = envStorage[env_idx];
            env_idx++;
        }
    }

    jsize envc = (envArray != NULL) ? env->GetArrayLength(envArray) : 0;
    for (int j = 0; j < envc && env_idx < MAX_ENV; j++) {
        jstring s = (jstring)env->GetObjectArrayElement(envArray, j);
        if (s == NULL) continue;
        const char *cs = env->GetStringUTFChars(s, NULL);
        if (cs == NULL) {
            env->DeleteLocalRef(s);
            continue;
        }
        /* Extract KEY from KEY=value */
        const char *eq = strchr(cs, '=');
        size_t keyLen = (eq != NULL) ? (size_t)(eq - cs) : strlen(cs);

        /* Replace existing KEY=… or append. */
        int replaced = 0;
        for (int k = 0; k < env_idx; k++) {
            if (strncmp(envpBuf[k], cs, keyLen) == 0 && envpBuf[k][keyLen] == '=') {
                strncpy(envStorage[k], cs, ENV_STR - 1);
                envStorage[k][ENV_STR - 1] = '\0';
                envpBuf[k] = envStorage[k];
                replaced = 1;
                break;
            }
        }
        if (!replaced && env_idx < MAX_ENV) {
            strncpy(envStorage[env_idx], cs, ENV_STR - 1);
            envStorage[env_idx][ENV_STR - 1] = '\0';
            envpBuf[env_idx] = envStorage[env_idx];
            env_idx++;
        }
        env->ReleaseStringUTFChars(s, cs);
        env->DeleteLocalRef(s);
    }
    envpBuf[env_idx] = NULL;

    /* 4. forkpty + execve */
    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, NULL);
    if (pid < 0) {
        LOGE("createSessionExec: forkpty() gagal: %s", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        /* Child — only async-signal-safe calls. */
        execve(execPathBuf, argvBuf, envpBuf);
        _exit(127); /* 127 = exec failed (linker / not found) */
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
