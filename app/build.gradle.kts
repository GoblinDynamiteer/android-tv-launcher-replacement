plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "se.kampe.tvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.kampe.tvlauncher"
        minSdk = 30 // the Philips TV runs Android 11
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // Fixed debug key (git-ignored; CI writes it from the DEBUG_KEYSTORE_B64 secret), so
    // every build has the same signature and `adb install -r` can update the app on the TV.
    val sharedDebugKey = file("debug.keystore")
    if (sharedDebugKey.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = sharedDebugKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
