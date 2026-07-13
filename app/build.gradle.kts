plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tunnel.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tunnel.terminal"
        minSdk = 24
        targetSdk = 34
        // Phase 41: Security & Privacy fixes (CRIT-01..04, LOW-02)
        // Phase 45: Realtime audit fixes (Bug #1 shell cwd, Bug #2 pseudo-cmd stick, Bug #3 proot readiness)
        // Phase 46: AI↔Ubuntu integration (4 pillars: MarkerExecutor fix, environmentDescription, non-interactive apt, AgentWorkflow unify)
        // Phase 47: Storage permission fix (workspace sandbox) + Agent Mode (autonomous task runner)
        // Phase 48: Rendering fixes (F-1 atomic snapshot, F-2 alt-screen resize, F-5 throttle) + A-5 random marker + C-1 .gradle cleanup + D-1/D-2 WIKI update
        // Phase 49: Scrollback buffer (E-1) + Screen persistence via Application (F-3) + MCP server management UI (D-4)
        // Phase 50: Project Context Awareness (B-5) + Checkpointing/Undo (B-4)
        // Phase 51: Automated tests (C-5) + BlockMode incremental parse (F-4)
        // Phase 52: Agent Mode audit fixes (Bug #1 approval dialog, Bug #2 success detection, Bug #3 Stop cancel)
        // Phase 58: TaskPlanManager (plan/act/observe/verify) + SFTP for SSH file I/O
        // Phase 59: Native API tool-calling (B-1) + AGP/Kotlin upgrade (C-2)
        /* Wave 17: AI chat / Auto-Pilot / Agent UX polish. */
        versionCode = 52
        versionName = "8.1.0"

        /* Phase 40 fix (M10): Restrict ke arm64-v8a saja — proot binary di assets
         * hanya arm64. Tanpa abiFilters, APK universal akan crash di device x86_64
         * karena proot arm64 tidak bisa exec. */
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    /* Phase 41 fix (CRIT-04): Product flavors untuk distribusi dual-channel.
     *
     * - "full" (default): Semua fitur termasuk proot/Ubuntu. Untuk GitHub Releases/F-Droid.
     *   Fitur proot didownload+exec runtime → melanggar Play Store policy.
     *
     * - "playstore": Exclude kode path proot (ProotBootstrap, ProotShellExecutor) +
     *   assets/proot. Aman untuk Play Store, tapi fitur 🐧 dinonaktifkan.
     *
     * Build command:
     *   ./gradlew assembleFullRelease      → APK untuk GitHub/F-Droid (dengan proot)
     *   ./gradlew assemblePlaystoreRelease → APK untuk Play Store (tanpa proot)
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            /* Default — semua fitur aktif. */
            buildConfigField("Boolean", "ENABLE_PROOT", "true")
        }
        create("playstore") {
            dimension = "distribution"
            /* Exclude assets/proot folder dari playstore build. */
            buildConfigField("Boolean", "ENABLE_PROOT", "false")
        }
    }

    buildTypes {
        release {
            /* BUG-29 fix: Enable R8/ProGuard untuk release build.
             * Old code: isMinifyEnabled=false — APK lebih besar dari perlu. */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    /* Phase 59 fix (C-2): Kotlin 2.0+ uses Compose Compiler Gradle Plugin
     * (declared in plugins block above) — kotlinCompilerExtensionVersion
     * no longer needed in composeOptions. */
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Konfigurasi NDK & CMake
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    /* Phase 20: Upgrade compose-bom from 2023.08.00 to 2024.02.00
     * - material3 1.1.1 -> 1.2.0+ (HorizontalDivider available, security fixes)
     * - Better stability + performance */
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // Phase 19: material-icons-extended untuk FileExplorer icons (Folder, Description, Image, dll)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Phase 18: Flow/callbackFlow untuk AI streaming SSE
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Phase 17: DocumentFile untuk Storage Access Framework (StorageManager)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Phase 41 fix (CRIT-01): EncryptedSharedPreferences untuk menyimpan API key &
    // SSH credentials secara aman (sebelumnya plaintext di SharedPreferences).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // BUG-21 fix: Update JSch dari 0.2.17 ke 0.2.21 (versi terbaru mwiede fork)
    implementation("com.github.mwiede:jsch:0.2.21")

    // Phase 51 fix (C-5): Automated tests — JUnit + Coroutines test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
}

// Phase 51 fix (C-5): Allow Robolectric to run unit tests that need Android framework classes
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
