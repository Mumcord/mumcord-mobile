// SPDX-License-Identifier: AGPL-3.0-only

// Voice in the background: the foreground service that keeps a session alive, the session's
// registration with the phone system as a self-managed call, its call-style notification, and
// the rules for phone calls and other audio interruptions.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.mumcord.mobile.platform.call"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned on purpose and raised deliberately, not whenever a newer one exists.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.androidx.core.telecom)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Drives the notification shade, which belongs to the system.
    androidTestImplementation(libs.androidx.test.uiautomator)
}
