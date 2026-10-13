// Gradle build for Android Studio users. The APK in releases is produced by tools/build-apk.sh
// from the same sources with pinned tools (no Google Maven access needed). Both paths must agree:
// zero third-party runtime libraries (enforced by tools/verify/static_checks.py).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing comes ONLY from environment variables (CI secrets or your shell), never from
// files in the repository. Without them, release builds are produced unsigned.
val releaseKeystore = System.getenv("PF_KEYSTORE_FILE")

android {
    namespace = "dev.pocketforge.ide"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pocketforge.ide"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PF_KEY_ALIAS")
                keyPassword = System.getenv("PF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    lint {
        // Mirrors the API-26 compile rule of tools/build-apk.sh.
        error += "NewApi"
        checkReleaseBuilds = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:engine"))
}
