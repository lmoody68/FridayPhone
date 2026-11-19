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
        versionCode = 2
        versionName = "0.2.0"
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
    // Offline custom wake-words ("Friday answer the call" / "Friday hang up now").
    // Activates once the user adds assets/picovoice_key.txt + friday_answer.ppn + friday_hangup.ppn
    // (see PORCUPINE_SETUP.md). Until then the app falls back to Android's on-device recognizer.
    implementation("ai.picovoice:porcupine-android:3.0.2")
}
