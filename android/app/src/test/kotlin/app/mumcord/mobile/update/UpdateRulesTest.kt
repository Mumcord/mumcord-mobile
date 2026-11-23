// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Versions, the once-a-day rule and when the notice shows (FR-5.101). */
class UpdateRulesTest {
    private fun v(text: String) = checkNotNull(ReleaseVersion.parse(text))

    @Test
    fun versionsAreCompared() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.2.1") > v("0.2.0"))
        assertEquals(v("0.3.0"), ReleaseVersion.fromTag("v0.3.0"))
        assertEquals("v0.3.0", v("0.3.0").tag)
    }

    @Test
    fun onlyPlainReleaseVersionsAreRead() {
        listOf("0.0.0-dryrun+abc", "1.2", "1.2.3.4", "v1.2.3", "01.2.3", "1.2.3 ", "", "1.2.x", "9999999999.0.0")
            .forEach { assertNull(it, ReleaseVersion.parse(it)) }
        assertNull(ReleaseVersion.fromTag("0.3.0"))
        assertNull(ReleaseVersion.fromTag("v0.3.0-rc1"))
    }

    @Test
    fun debugAndTestBuildsHaveNoReleaseVersion() {
        assertNull(AppInfo("0.1.0", "0.8.0 (6d2ac79e)", debuggable = true).releaseVersion)
        assertNull(AppInfo("0.0.0-dryrun+0123456789ab", "", debuggable = false).releaseVersion)
        assertEquals(v("0.3.0"), AppInfo("0.3.0", "", debuggable = false).releaseVersion)
    }

    @Test
    fun theSourceLinkNamesTheTagOfARelease() {
        assertEquals(
            "https://github.com/Mumcord/mumcord-mobile/tree/v0.3.0",
            AppInfo("0.3.0", "", debuggable = false).sourceUrl,
        )
        assertEquals("https://github.com/Mumcord/mumcord-mobile", AppInfo("0.1.0", "", debuggable = true).sourceUrl)
    }

    @Test
    fun aCheckIsDueOnceADay() {
        val day = CHECK_INTERVAL_MILLIS
        assertTrue(isCheckDue(1_000, null))
        assertFalse(isCheckDue(1_000 + day - 1, 1_000))
        assertTrue(isCheckDue(1_000 + day, 1_000))
        // A clock set back does not stop the checks.
        assertTrue(isCheckDue(500, 1_000))
    }

    @Test
    fun theNoticeShowsOnlyANewerUndismissedReleaseWithTheSwitchOn() {
        val update = AvailableUpdate(v("0.3.0"), hasApk = true)
        assertEquals(update, updateNotice(v("0.2.0"), enabled = true, available = update, dismissed = null))
        assertNull(updateNotice(v("0.2.0"), enabled = false, available = update, dismissed = null))
        assertNull(updateNotice(v("0.3.0"), enabled = true, available = update, dismissed = null))
        assertNull(updateNotice(v("0.4.0"), enabled = true, available = update, dismissed = null))
        assertNull(updateNotice(null, enabled = true, available = update, dismissed = null))
        assertNull(updateNotice(v("0.2.0"), enabled = true, available = update, dismissed = v("0.3.0")))
        // A newer release than the closed one comes back.
        assertEquals(update, updateNotice(v("0.2.0"), enabled = true, available = update, dismissed = v("0.2.5")))
    }

    @Test
    fun theDownloadIsTheApkOfTheReleaseOrItsPage() {
        assertEquals(
            "https://github.com/Mumcord/mumcord-mobile/releases/download/v0.3.0/mumcord-mobile-0.3.0-arm64.apk",
            AvailableUpdate(v("0.3.0"), hasApk = true).downloadUrl,
        )
        assertEquals(
            "https://github.com/Mumcord/mumcord-mobile/releases/tag/v0.3.0",
            AvailableUpdate(v("0.3.0"), hasApk = false).downloadUrl,
        )
    }
}
