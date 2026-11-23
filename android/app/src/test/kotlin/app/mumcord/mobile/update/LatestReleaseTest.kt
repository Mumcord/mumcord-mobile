// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reading the untrusted answer of GitHub's latest-release API (FR-5.101). */
@RunWith(RobolectricTestRunner::class)
class LatestReleaseTest {
    @Test
    fun aPublishedReleaseWithItsApkIsOffered() {
        val update = parseLatestRelease(releaseJson())
        assertEquals(ReleaseVersion(0, 3, 0), update.version)
        assertTrue(update.hasApk)
    }

    @Test
    fun withoutTheExpectedApkThePageIsOffered() {
        val elsewhere = releaseJson(apkUrl = "https://example.com/mumcord-mobile-0.3.0-arm64.apk")
        assertFalse(parseLatestRelease(elsewhere).hasApk)
        val renamed = releaseJson(apkName = "mumcord.apk")
        assertFalse(parseLatestRelease(renamed).hasApk)
        assertFalse(parseLatestRelease(releaseJson(assets = "[]")).hasApk)
    }

    @Test
    fun draftsPrereleasesAndOtherTagsAreRefused() {
        listOf(
            releaseJson(draft = "true"),
            releaseJson(prerelease = "true"),
            releaseJson(prerelease = "\"false\""),
            releaseJson(tag = "v0.3.0-rc1"),
            releaseJson(tag = "0.3.0"),
            releaseJson(page = "https://github.com/someone/else/releases/tag/v0.3.0"),
            releaseJson().replace("\"draft\": false,", ""),
        ).forEach { json -> assertThrows(json, InvalidReleaseException::class.java) { parseLatestRelease(json) } }
    }

    @Test
    fun onlyStrictJsonIsRead() {
        listOf(
            "",
            "[]",
            "not json",
            "{'tag_name': 'v0.3.0'}",
            releaseJson().dropLast(2),
            releaseJson() + "{}",
            releaseJson().replace("\"tag_name\"", "tag_name"),
        ).forEach { json -> assertThrows(json, InvalidReleaseException::class.java) { parseLatestRelease(json) } }
    }

    @Test
    fun unknownFieldsAreSkipped() {
        val json = releaseJson().replaceFirst("{", "{\"body\": \"notes\", \"author\": {\"login\": \"x\", \"ids\": [1, 2]},")
        assertEquals(ReleaseVersion(0, 3, 0), parseLatestRelease(json).version)
    }
}
