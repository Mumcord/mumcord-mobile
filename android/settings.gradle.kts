// SPDX-License-Identifier: AGPL-3.0-only

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // The Kotlin companion library of the Rust crate rustls-platform-verifier is published
        // only here. Nothing else may come from this repository, and the files are pinned by
        // checksum in gradle/verification-metadata.xml.
        exclusiveContent {
            forRepository {
                maven {
                    url = uri("https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/")
                }
            }
            filter {
                includeGroup("org.rustls")
            }
        }
    }
}

rootProject.name = "mumcord-mobile"

include(":app", ":design", ":engine", ":platform:securestore")
// End-to-end tests against the local test server; they only run through e2e/run.sh.
include(":e2e")
