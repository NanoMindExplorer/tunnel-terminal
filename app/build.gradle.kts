plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 32
        versionName = "6.7.0-phase48-rendering-fixes"

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
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
}
