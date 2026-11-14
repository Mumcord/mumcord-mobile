// SPDX-License-Identifier: AGPL-3.0-only

// The shared Rust engine for Android: the native library and its generated Kotlin bindings
// (package app.mumcord.mobile.bridge). Both are build outputs of scripts/build-android.sh and
// are not checked in; this module contains no hand-written code.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.mumcord.mobile.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    lint {
        abortOnError = true
        // The generated bindings use java.lang.ref.Cleaner (API 33) only after checking at run
        // time that the class exists, and fall back to JNA's cleaner otherwise. Lint cannot see
        // that check, and this module holds no other code the rule could protect.
        disable += "NewApi"
    }
}

dependencies {
    api(variantOf(libs.jna) { artifactType("aar") })
    api(libs.kotlinx.coroutines.core)
}

val repositoryRoot: File = rootDir.parentFile

val buildRustBridge = tasks.register<Exec>("buildRustBridge") {
    description = "Builds the native engine library for Android and generates its Kotlin bindings."
    group = "build"
    workingDir = repositoryRoot
    commandLine(repositoryRoot.resolve("scripts/build-android.sh").path)

    // Everything that decides what the script produces, so Gradle skips it when nothing changed.
    inputs.dir(repositoryRoot.resolve("bridge"))
    inputs.files(
        repositoryRoot.resolve("Cargo.toml"),
        repositoryRoot.resolve("Cargo.lock"),
        repositoryRoot.resolve("engine.lock.toml"),
        repositoryRoot.resolve("rust-toolchain.toml"),
        repositoryRoot.resolve("scripts/build-android.sh"),
    )
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    outputs.dir(layout.projectDirectory.dir("src/main/kotlin"))
}

tasks.named("preBuild") {
    dependsOn(buildRustBridge)
}
