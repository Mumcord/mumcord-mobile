// SPDX-License-Identifier: AGPL-3.0-only

// The phone's audio for voice: a small C++ library that runs the microphone and speaker streams
// (AAudio, voice-communication mode) and hands the samples straight to the engine's C functions,
// and the Kotlin controller that opens and closes them.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.mumcord.mobile.platform.audio"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned on purpose and raised deliberately, not whenever a newer one exists.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The streams call into the engine's library; with it in the test app they run for real.
    androidTestImplementation(project(":engine"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
