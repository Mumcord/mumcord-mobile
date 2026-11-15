// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.crash

import app.mumcord.mobile.bridge.CrashKind
import app.mumcord.mobile.crash.FakeCrashSource
import app.mumcord.mobile.crash.crashPreview
import app.mumcord.mobile.crash.crashReport
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which crash reports are shown, the notice, the upload sheet and deleting (FR-5.103), against a fake source. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashControllerTest {
    private val panic = crashReport("crash-3.txt", createdMs = 3_000)
    private val native = crashReport("crash-2.txt", createdMs = 2_000, kind = CrashKind.Signal("SIGSEGV"))
    private val killed = crashReport("crash-4.txt", createdMs = 4_000, kind = CrashKind.Unexpected)

    private fun TestScope.controller(source: FakeCrashSource): CrashController =
        CrashController(source, this, StandardTestDispatcher(testScheduler)).also { advanceUntilIdle() }

    @Test
    fun unexpectedExitsAreNeitherListedNorNoticedAndTheNewestCrashSpeaksForAll() {
        assertEquals(listOf(panic, native), shownCrashes(listOf(native, killed, panic)))
        assertEquals(panic, noticeReport(listOf(native, killed, panic)))
        assertNull(noticeReport(listOf(killed)))
        assertNull(noticeReport(emptyList()))
    }

    @Test
    fun theNoticeNamesTheNewestNewCrashOnceAndCanBeDismissed() = runTest {
        val crashes = controller(FakeCrashSource(listOf(native, panic, killed)))
        assertEquals(CrashNotice.Crashed(panic), crashes.local.value.notice)
        crashes.dismissNotice()
        assertNull(crashes.local.value.notice)
    }

    @Test
    fun onlyTheSystemEndingTheAppBringsNoNotice() = runTest {
        val crashes = controller(FakeCrashSource(listOf(killed)))
        assertNull(crashes.local.value.notice)
    }

    @Test
    fun theSheetSendsExactlyThePreviewOfTheCommentAndOnlyOnSend() = runTest {
        val source = FakeCrashSource(listOf(panic))
        val crashes = controller(source)
        crashes.openUpload(panic)
        assertFalse("nothing to send before the text is shown", crashes.local.value.upload!!.canSend)
        crashes.send()
        advanceUntilIdle()
        assertEquals(crashPreview(panic.id, ""), crashes.local.value.upload?.preview)

        crashes.changeComment("I was talking")
        assertFalse("the shown text is not the one of the new comment yet", crashes.local.value.upload!!.canSend)
        advanceUntilIdle()
        assertEquals(crashPreview(panic.id, "I was talking"), crashes.local.value.upload?.preview)
        assertTrue(source.uploads.isEmpty())

        crashes.send()
        advanceUntilIdle()
        assertEquals(listOf(panic.id to "I was talking"), source.uploads.toList())
        assertNull(crashes.local.value.upload)
        assertEquals(CrashNotice.Sent, crashes.local.value.notice)
        assertEquals(CrashStatus.Sent, crashes.local.value.status)
    }

    @Test
    fun cancellingSendsNothing() = runTest {
        val source = FakeCrashSource(listOf(panic))
        val crashes = controller(source)
        crashes.openUpload(panic)
        advanceUntilIdle()
        crashes.cancelUpload()
        advanceUntilIdle()
        assertNull(crashes.local.value.upload)
        assertTrue(source.uploads.isEmpty())
        assertEquals(CrashNotice.Crashed(panic), crashes.local.value.notice)
    }

    @Test
    fun aFailedUploadKeepsTheSheetOpenToTryAgain() = runTest {
        val source = FakeCrashSource(listOf(panic)).apply { uploadWorks = false }
        val crashes = controller(source)
        crashes.openUpload(panic)
        advanceUntilIdle()
        crashes.send()
        advanceUntilIdle()
        val upload = crashes.local.value.upload!!
        assertTrue(upload.failed)
        assertTrue(upload.canSend)
        assertEquals(CrashNotice.Crashed(panic), crashes.local.value.notice)

        source.uploadWorks = true
        crashes.send()
        advanceUntilIdle()
        assertEquals(listOf(panic.id to ""), source.uploads.toList())
    }

    @Test
    fun aReportThatCannotBeReadCannotBeSent() = runTest {
        val crashes = controller(FakeCrashSource(listOf(panic)))
        crashes.openUpload(native)
        advanceUntilIdle()
        val upload = crashes.local.value.upload!!
        assertTrue(upload.unreadable)
        assertFalse(upload.canSend)
    }

    @Test
    fun theListLeavesOutUnexpectedExitsAndDeletesOneOrAll() = runTest {
        val source = FakeCrashSource(listOf(native, panic, killed))
        val crashes = controller(source)
        crashes.loadReports()
        advanceUntilIdle()
        assertEquals(CrashList.Loaded(listOf(panic, native)), crashes.local.value.reports)

        crashes.askDelete(panic)
        assertEquals(CrashDelete.One(panic), crashes.local.value.confirm)
        crashes.confirmDelete()
        advanceUntilIdle()
        assertEquals(CrashList.Loaded(listOf(native)), crashes.local.value.reports)
        assertEquals(CrashStatus.Deleted, crashes.local.value.status)
        assertNull("the notice of a deleted report is gone", crashes.local.value.notice)

        crashes.askDeleteAll()
        crashes.cancelDelete()
        assertNull(crashes.local.value.confirm)
        crashes.askDeleteAll()
        crashes.confirmDelete()
        advanceUntilIdle()
        assertEquals(CrashList.Loaded(emptyList()), crashes.local.value.reports)
        assertEquals(CrashStatus.DeletedAll, crashes.local.value.status)
        assertTrue(source.reports.isEmpty())
    }

    @Test
    fun anUnreadableListSaysSo() = runTest {
        val crashes = controller(FakeCrashSource(listOf(panic)).apply { listWorks = false })
        crashes.loadReports()
        advanceUntilIdle()
        assertEquals(CrashList.Failed, crashes.local.value.reports)
    }

    @Test
    fun timesAreWrittenInTheZoneAndLanguageGiven() {
        val clock = CrashClock(ZoneOffset.UTC, Locale.UK)
        assertEquals("14 Nov 2023, 22:13", clock.format(1_700_000_000_000))
    }
}
