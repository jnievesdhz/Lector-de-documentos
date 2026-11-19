import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions da un número que sube en cada compilación.
// Así cada APK nuevo se instala encima del anterior sin desinstalar.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.jnieves.lector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jnieves.lector"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"
    }

    // Llave propia para firmar la app. Siempre la misma, para que las
    // actualizaciones se instalen encima de la versión anterior.
    signingConfigs {
        create("personal") {
            storeFile = file("keystore/lector.jks")
            storePassword = "lector123"
            keyAlias = "lector"
            keyPassword = "lector123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
