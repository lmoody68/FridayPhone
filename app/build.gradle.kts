plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.macvision.fridayphone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.macvision.fridayphone"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // Offline wake-word ("Friday answer the call") via Vosk — no account, no cloud, Apache-2.0.
    // The ~40 MB small English model is fetched into app/src/main/assets/model-en-us at CI build
    // time (see .github/workflows/android-build.yml) and unpacked to internal storage on first run.
    implementation("com.alphacephei:vosk-android:0.3.47")
    // Vosk's native layer talks through JNA. The Android @aar classifier doesn't always resolve
    // transitively, so pin it explicitly (version matches what vosk-android 0.3.47 expects).
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
