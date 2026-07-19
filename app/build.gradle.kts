plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.adbcommander"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adbcommander"
        minSdk = 24
        targetSdk = 35
        versionCode = 37
        versionName = "2.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // ADB library with wireless pairing support
    implementation(libs.libadb)

    // Conscrypt for TLS 1.3 (required for ADB pairing on Android 7-8)
    implementation(libs.conscrypt)

    // BouncyCastle for X509 certificate generation (used by ADB key management)
    implementation(libs.bouncy.castle)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore for persisting settings
    implementation(libs.androidx.datastore.preferences)
}
