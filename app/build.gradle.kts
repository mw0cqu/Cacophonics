plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.mw0cqu.cacophonics"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.mw0cqu.cacophonics"
        // Android 5.0 and up: covers the Android 10 head unit and the Android 17 phone.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // MediaSession / MediaBrowserService (Bluetooth metadata, Android Auto)
    implementation("androidx.media:media:1.7.0")

    // Audio engine: one ExoPlayer per stream, ICY metadata support
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    // WebSocket client for the native OpenWebRX+ source
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
