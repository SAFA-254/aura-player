plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки GitHub Actions — каждая новая APK ставится поверх старой
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.safa254.aura"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.safa254.aura"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Один и тот же ключ для всех сборок, иначе Android не даст обновить приложение поверх
    signingConfigs {
        create("aura") {
            storeFile = file("aura.keystore")
            storePassword = "aura-player"
            keyAlias = "aura"
            keyPassword = "aura-player"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aura")
        }
        debug {
            signingConfig = signingConfigs.getByName("aura")
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
