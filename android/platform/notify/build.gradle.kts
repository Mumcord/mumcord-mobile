// SPDX-License-Identifier: AGPL-3.0-only

// Notifications for private messages and mentions: their channel, one notification per
// conversation with the sender and the text handed to the system, and taking them away again.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.mumcord.mobile.platform.notify"
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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
