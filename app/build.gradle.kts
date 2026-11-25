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
        versionCode = 33
        versionName = "0.12.3-textfield-deep"
    }

    // Fixed signing key so every build has the SAME signature → updates install in place
    // (no uninstall), which keeps the accessibility service enabled and permissions granted.
    signingConfigs {
        getByName("debug") {
            storeFile = file("friday.keystore")
            storePassword = "fridaykey"
            keyAlias = "friday"
            keyPassword = "fridaykey"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign release with the SAME stable key as debug → identical signature → still installs
            // in place over the debug build. Release is non-debuggable, which removes the OS
            // "Android App Compatibility / debuggable app" warning dialog.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
