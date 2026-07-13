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
        versionCode = 34
        versionName = "2.3.1"

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

    // v2.3.0: Glassmorphism backdrop blur.
    //
    // The build brief specifies `com.github.skydoves:cloudy:0.6.1`, but
    // 0.6.1 was compiled with Kotlin 2.3.0 metadata which the project's
    // Kotlin 2.1.0 compiler cannot read, AND it transitively pulls
    // androidx.core:core-ktx:1.17.0 which demands compileSdk 36 + AGP
    // 8.9.1 (this project is pinned to compileSdk 35 + AGP 8.7.3 per
    // libs.versions.toml). Rather than bump the entire toolchain just
    // for a blur modifier, we implement the equivalent of cloudy's
    // `Modifier.cloudy(radius)` in-house in `GlassCard` using Android's
    // native `android.graphics.RenderEffect` (API 31+) with a software
    // fallback for API 24–30. This is the same approach cloudy uses
    // internally and produces visually identical results. See
    // `GlassCard` in MainActivity.kt for the implementation.
}