// SPDX-License-Identifier: AGPL-3.0-only

// Secure storage of the phone behind the bridge's SecureStorage interface, and the start-up
// steps the engine needs before it is created (native library, system certificate checking).

plugins {
    alias(libs.plugins.android.library)
}

// The Kotlin companion library of rustls-platform-verifier must be exactly the version of the
// Rust crate that is compiled into the native library; a mismatch only fails at run time.
val lockedVerifierVersion: String =
    providers.fileContents(rootProject.layout.projectDirectory.file("../Cargo.lock")).asText.get()
        .lineSequence()
        .dropWhile { it.trim() != "name = \"rustls-platform-verifier-android\"" }
        .firstOrNull { it.startsWith("version = ") }
        ?.substringAfter('"')
        ?.substringBefore('"')
        ?: throw GradleException("rustls-platform-verifier-android not found in Cargo.lock")
val catalogVerifierVersion: String = libs.versions.rustls.platform.verifier.get()
if (lockedVerifierVersion != catalogVerifierVersion) {
    throw GradleException(
        "rustls-platform-verifier companion library is $catalogVerifierVersion in " +
            "gradle/libs.versions.toml but Cargo.lock has $lockedVerifierVersion. Raise the " +
            "catalog version and its checksums in gradle/verification-metadata.xml.",
    )
}

android {
    namespace = "app.mumcord.mobile.platform.securestore"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned on purpose and raised deliberately, not whenever a newer one exists.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    api(project(":engine"))
    // Only called from the native library through JNI.
    implementation(libs.rustls.platform.verifier)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
