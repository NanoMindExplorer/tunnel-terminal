# BUG-29: ProGuard keep rules untuk Tunnel Terminal release build

# JSch (SSH library)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Phase 41 fix (CRIT-01): EncryptedSharedPreferences (security-crypto) depends on
# Google Tink library, which references annotation classes from errorprone and
# javax.annotation that are not bundled in the APK. These are compile-time-only
# annotations — safe to suppress at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.checkerframework.**

# Keep Tink classes (used internally by EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# v8.5.0 fix (L5): Removed over-broad `-keep class androidx.compose.** { *; }`.
# Compose compiler already generates proper keep rules untuk Compose runtime.
# Over-broad keep mencegah R8 shrink unused Compose code → APK lebih besar.
# Jika ada class Compose tertentu yang butuh keep (rare), add specific rule here.

# Kotlin coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Keep data classes used in JSON serialization
-keep class com.tunnel.terminal.AISettings { *; }
-keep class com.tunnel.terminal.ChatMessage { *; }
-keep class com.tunnel.terminal.McpServerConfig { *; }
-keep class com.tunnel.terminal.McpTool { *; }
-keep class com.tunnel.terminal.ModelInfo { *; }
-keep class com.tunnel.terminal.Snippet { *; }
-keep class com.tunnel.terminal.AgentWorkflow { *; }
-keep class com.tunnel.terminal.AgentStep { *; }
-keep class com.tunnel.terminal.WorkspaceSession { *; }
-keep class com.tunnel.terminal.SshConnectionConfig { *; }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep TerminalJni
-keep class com.tunnel.terminal.TerminalJni { *; }

# Phase 52 fix: Tink library (security-crypto) references Google HTTP Client + Joda Time
# yang tidak di-bundle di APK. Safe to suppress — hanya dipakai untuk remote key fetching
# yang tidak kita gunakan (kita pakai local Android Keystore).
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.json.**
-dontwarn com.google.api.client.googleapis.**
-dontwarn com.google.api.client.util.**
-dontwarn org.joda.time.**

# Phase 60 fix (audit #1, defensive): SessionTargetResolver — dipakai oleh
# AiToolCall untuk resolve path berdasarkan sesi aktif. Sebelumnya pakai
# reflection ke field private 'sessionType' yang gagal di build minified.
# Sekarang field sudah publik, tapi tetap tambah keep-rule sebagai
# belt-and-suspenders supaya R8 tidak menghapus method yang dipanggil
# via property access di tempat lain.
-keep class com.tunnel.terminal.SessionTargetResolver { *; }
