plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lublo.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lublo.agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug key so the release APK installs directly (sideload use).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions"
        )
    }
    packaging {
        resources {
            excludes += setOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "/META-INF/*.version")
        }
    }
    lint { abortOnError = false }
}

// Zero third-party dependencies on purpose: smallest possible APK.
// We intentionally do NOT use a TTS library — Android's built-in TextToSpeech
// is used everywhere so the APK stays ~1MB and dependency-free.
dependencies {}
