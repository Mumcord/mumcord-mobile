// SPDX-License-Identifier: AGPL-3.0-only

// End-to-end tests: they drive the real app with the real engine on the emulator against the
// local Mumble test server. A module of its own, so the app's own emulator tests (which use a
// fake engine) stay independent of a server. Started by e2e/run.sh; without the server address
// as a runner argument every test here skips itself.

plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.mumcord.mobile.e2e"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.test.junit4)
    implementation(libs.espresso.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    // Drives the system's own permission dialog, which is outside the app.
    implementation(libs.androidx.test.uiautomator)
}
