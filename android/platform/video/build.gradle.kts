// SPDX-License-Identifier: AGPL-3.0-only

// The pictures of watched streams on screen: a TextureView with its own OpenGL ES render thread,
// and a small C++ library that takes each picture from the engine's C functions and uploads its
// three planes straight into the textures.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.mumcord.mobile.platform.video"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 31
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
    testImplementation(libs.junit)
}
