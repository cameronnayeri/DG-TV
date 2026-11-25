plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions numbers every build, so each APK installs over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.cameron.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.cameron.tvhome"
        minSdk = 28
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // One fixed key, kept in the repo, so every build is signed the same way.
    // Without it each GitHub build would get a new key and refuse to update the
    // copy already on the TV. It protects nothing beyond this sideloaded app.
    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "tvhome-sideload"
            keyAlias = "tvhome"
            keyPassword = "tvhome-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}
