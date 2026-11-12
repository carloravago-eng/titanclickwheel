plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.orbit.clickwheel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orbit.clickwheel"
        minSdk = 30
        targetSdk = 35
        // CI passes the run number so every published build installs as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // CI writes the private signing key here from the CLICKWHEEL_KEYSTORE_BASE64 secret.
    // Without it, release builds fall back to the debug key.
    val keystore = rootProject.file("release.keystore")
    signingConfigs {
        if (keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("CLICKWHEEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLICKWHEEL_KEY_ALIAS") ?: "clickwheel"
                keyPassword = System.getenv("CLICKWHEEL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
