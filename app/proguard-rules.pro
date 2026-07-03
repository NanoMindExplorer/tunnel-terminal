# BUG-29: ProGuard keep rules untuk Tunnel Terminal release build

# JSch (SSH library)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Compose (usually handled by compose compiler, but be safe)
-keep class androidx.compose.** { *; }

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
