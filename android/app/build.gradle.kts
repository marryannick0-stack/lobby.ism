plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lobbyism.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lobbyism.app"
        minSdk = 26
        targetSdk = 35
        // The CI run number makes every build newer than the last, so updates install over each other.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.0a-" + (System.getenv("GITHUB_RUN_NUMBER") ?: "local")
    }

    signingConfigs {
        // A fixed TEST key that lives in the repository. Because every build is signed
        // with the same key, a new APK installs over the old one and keeps its data.
        // It is public on purpose and must never sign a store release.
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "lobbyism"
            keyAlias = "lobbyism-debug"
            keyPassword = "lobbyism"
        }
    }

    buildTypes {
        getByName("debug") {
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

    lint {
        // Lint findings are reported but do not block a test build.
        abortOnError = false
    }
}

dependencies {
    // The app itself has no library dependencies: only the Android framework and the Kotlin standard library.
    testImplementation("junit:junit:4.13.2")
}
