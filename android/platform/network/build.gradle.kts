// SPDX-License-Identifier: AGPL-3.0-only

// The phone's network as the app needs to know it: which network is the default one, whether
// it has been checked to work, and whether it is metered (mobile data).

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.mumcord.mobile.platform.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
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
}
