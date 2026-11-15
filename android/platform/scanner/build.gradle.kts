// SPDX-License-Identifier: AGPL-3.0-only

// Reads QR codes with the phone's camera: CameraX shows the picture (its Compose viewfinder;
// the View-based one would bring CameraX's video recording and all of Guava along) and hands
// out its frames, ZXing finds the code in them. Both are plain libraries; nothing here needs Google Play
// services, which a phone that got the app outside a store may not have.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.mumcord.mobile.platform.scanner"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned on purpose and raised deliberately, not whenever a newer one exists.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // The lint checks bundled with the lifecycle library were built for an older lint; the
        // checks are skipped either way, as in the app module.
        disable += "ObsoleteLintCustomCheck"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
