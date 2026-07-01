#include <jni.h>
#include <unistd.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <stdlib.h>
#include <string.h>

extern "C" {

// Membuat sesi Pseudo-Terminal (PTY) baru
JNIEXPORT jint JNICALL
Java_com_tunnel_terminal_TerminalJni_createSession(JNIEnv *env, jobject thiz, jint rows, jint cols) {
    int masterFd;
    pid_t pid = forkpty(&masterFd, NULL, NULL, NULL);
    
    if (pid < 0) {
        return -1; // Gagal fork
    } else if (pid == 0) {
        // Proses Anak (Child Process) - Ini yang akan menjadi shell
        execl("/system/bin/sh", "sh", NULL);
        exit(1); // Jika execl gagal
    }
    
    // Proses Induk (Parent) - Kembali ke aplikasi Android
    // Atur ukuran terminal awal
    if (rows > 0 && cols > 0) {
        struct winsize ws;
        ws.ws_row = rows;
        ws.ws_col = cols;
        ioctl(masterFd, TIOCSWINSZ, &ws);
    }
    
    return masterFd;
}

// Menulis perintah ke terminal (seperti mengetik di keyboard)
JNIEXPORT void JNICALL
Java_com_tunnel_terminal_TerminalJni_write(JNIEnv *env, jobject thiz, jint fd, jbyteArray data) {
    jbyte *bytes = env->GetByteArrayElements(data, NULL);
    jsize len = env->GetArrayLength(data);
    write(fd, bytes, len);
    env->ReleaseByteArrayElements(data, bytes, 0);
}

// Mengatur ukuran terminal saat layar di-rotate atau keyboard muncul
JNIEXPORT void JNICALL
Java_com_tunnel_terminal_TerminalJni_resize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    ws.ws_row = rows;
    ws.ws_col = cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

// Menutup terminal
JNIEXPORT void JNICALL
Java_com_tunnel_terminal_TerminalJni_close(JNIEnv *env, jobject thiz, jint fd) {
    close(fd);
}

}
