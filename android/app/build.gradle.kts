// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.mumcord.mobile"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.mumcord.mobile"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // x86_64 is for the emulator only.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    // The fake engine is used by the JVM tests and by the tests on the emulator.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned on purpose and raised deliberately, not whenever a newer one exists.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Releases are arm64 only by specification; x86_64 exists for the emulator.
        disable += "ChromeOsAbiSupport"
        // Lint rules shipped inside a library that this lint version cannot load; not our code.
        disable += "ObsoleteLintCustomCheck"
        // The launcher icon comes with the design system.
        disable += "MissingApplicationIcon"
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":platform:securestore"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    // Provides the empty Activity the Compose test rule starts the screens in.
    debugImplementation(libs.compose.ui.test.manifest)
}
