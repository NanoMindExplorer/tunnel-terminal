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
        // Phase 22: AI-Native Terminal Revolution (blocks, palette, tool calls, markdown)
        versionCode = 9
        versionName = "4.1.0-phase22-ai-native-revolution"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Phase 21: JSch untuk SSH Client
    implementation("com.github.mwiede:jsch:0.2.17")
}
