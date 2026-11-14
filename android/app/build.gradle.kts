// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The screenshot tests run with every `test`, where they only draw the screens into
// build/outputs/screenshots. `recordScreenshots` writes them to docs/images/screenshots, and
// `verifyScreenshots` compares them with the files there. The comparison is its own task
// because pixels differ slightly between machines (fonts are drawn by the host's libraries).
val screenshotGoldens = rootProject.layout.projectDirectory.dir("../docs/images/screenshots")
val screenshotMode = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.let { names ->
    when {
        "recordScreenshots" in names -> "record"
        "verifyScreenshots" in names -> "verify"
        else -> "render"
    }
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

    testOptions {
        unitTests {
            // The screenshot tests draw real screens and need the fonts and other resources.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("mumcord.screenshots.mode", screenshotMode)
                it.systemProperty("mumcord.screenshots.goldens", screenshotGoldens.asFile.absolutePath)
                it.systemProperty(
                    "mumcord.screenshots.output",
                    layout.buildDirectory.dir("outputs/screenshots").get().asFile.absolutePath,
                )
            }
        }
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
    implementation(project(":design"))
    implementation(project(":engine"))
    implementation(project(":platform:audio"))
    implementation(project(":platform:call"))
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
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    // The Espresso that the Compose test library asks for is too old for the Android version
    // the tests are drawn with.
    testImplementation(libs.espresso.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    // Provides the empty Activity the Compose test rule starts the screens in.
    debugImplementation(libs.compose.ui.test.manifest)
}

for (name in listOf("recordScreenshots", "verifyScreenshots")) {
    tasks.register(name) {
        group = "verification"
        description = "Runs the screenshot tests of the debug build in ${name.removeSuffix("Screenshots")} mode."
        dependsOn("testDebugUnitTest")
    }
}
