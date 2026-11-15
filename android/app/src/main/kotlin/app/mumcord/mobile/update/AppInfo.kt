// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

/** The repository of the app: its source code (AGPL-3.0) and its releases. */
const val REPOSITORY_URL = "https://github.com/Mumcord/mumcord-mobile"

/**
 * A release version `X.Y.Z` as the release tags `vX.Y.Z` name it (scripts/release-version.sh).
 * Anything else, such as the `0.0.0-dryrun+…` of a test build, is no release version.
 */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** The release tag of this version. */
    val tag: String get() = "v$this"

    companion object {
        private val PATTERN = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")

        /** Reads `X.Y.Z`; null for anything else. */
        fun parse(text: String): ReleaseVersion? =
            PATTERN.matchEntire(text)?.destructured?.let { (major, minor, patch) ->
                ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
            }

        /** Reads a release tag `vX.Y.Z`; null for anything else. */
        fun fromTag(tag: String): ReleaseVersion? = if (tag.startsWith("v")) parse(tag.substring(1)) else null
    }
}

/**
 * What the About screen names and what the update check compares with.
 *
 * @property appVersion the versionName of the build; a release build has the version of its tag.
 * @property engineVersion the engine inside the bridge library and its pinned reference.
 * @property debuggable a debug build: it never checks for updates, whatever its version says.
 */
data class AppInfo(val appVersion: String, val engineVersion: String, val debuggable: Boolean) {
    /** The version of a published release build; null for debug and test builds. */
    val releaseVersion: ReleaseVersion? = if (debuggable) null else ReleaseVersion.parse(appVersion)

    /** The source code of exactly this build where it has a tag, otherwise the repository. */
    val sourceUrl: String = releaseVersion?.let { "$REPOSITORY_URL/tree/${it.tag}" } ?: REPOSITORY_URL
}
