// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.mumcord.mobile.bridge.CrashKind
import app.mumcord.mobile.crash.FakeCrashSource
import app.mumcord.mobile.crash.crashReport
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.about.ABOUT_CRASH_REPORTS_TEST_TAG
import app.mumcord.mobile.ui.about.ABOUT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_ABOUT_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_DELETE_ALL_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_DELETE_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_NOTICE_DISMISS_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_NOTICE_REPORT_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_NOTICE_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_REPORTS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_REPORT_DELETE_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_REPORT_ROW_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_UPLOAD_CANCEL_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_UPLOAD_COMMENT_TEST_TAG
import app.mumcord.mobile.ui.crash.CRASH_UPLOAD_SEND_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Crash reports (FR-5.103) on a device against a fake engine and a fake crash source: the notice
 * on the server list, the upload sheet that shows what is sent, and the list under About. The
 * fake records what would be sent; nothing goes to the crash server.
 */
class CrashUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val panic = crashReport("crash-1777900000.txt", createdMs = 1_777_900_000_000)
    private val native = crashReport(
        "crash-1777300000.txt",
        createdMs = 1_777_300_000_000,
        kind = CrashKind.Signal("SIGSEGV"),
        cause = "Mumcord was stopped by SIGSEGV",
    )
    private val killed = crashReport("crash-1777950000.txt", createdMs = 1_777_950_000_000, kind = CrashKind.Unexpected)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    private fun start(source: FakeCrashSource) {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht")))
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        runBlocking { repository.loadServers() }
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            crashSource = source,
        )
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                )
            }
        }
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun shownText(text: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    @Test
    fun theNoticeLeadsToTheTextThatIsSentAndSendsItOnlyOnSend() {
        val source = FakeCrashSource(listOf(panic))
        start(source)
        await { shown(CRASH_NOTICE_TEST_TAG) }
        compose.onNodeWithText("Mumcord closed unexpectedly.", substring = true).assertExists()

        compose.onNodeWithTag(CRASH_NOTICE_REPORT_TEST_TAG).performClick()
        await { shownText("thread: ${panic.id}") }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(CRASH_UPLOAD_COMMENT_TEST_TAG)))
            .performTextInput("I was talking")
        await { shownText("comment:\nI was talking") }
        assertTrue("nothing is sent before Send", source.uploads.isEmpty())

        compose.onNodeWithTag(CRASH_UPLOAD_SEND_TEST_TAG).performScrollTo().performClick()
        await { source.uploads.isNotEmpty() }
        assertEquals(listOf(panic.id to "I was talking"), source.uploads.toList())
        await { shownText("Report sent. Thank you!") }
        compose.onNodeWithTag(CRASH_NOTICE_DISMISS_TEST_TAG).performClick()
        await { !shown(CRASH_NOTICE_TEST_TAG) }
    }

    @Test
    fun cancelAndDismissSendNothing() {
        val source = FakeCrashSource(listOf(panic))
        start(source)
        await { shown(CRASH_NOTICE_TEST_TAG) }
        compose.onNodeWithTag(CRASH_NOTICE_REPORT_TEST_TAG).performClick()
        await { shown(CRASH_UPLOAD_CANCEL_TEST_TAG) }
        compose.onNodeWithTag(CRASH_UPLOAD_CANCEL_TEST_TAG).performScrollTo().performClick()
        await { !shown(CRASH_UPLOAD_CANCEL_TEST_TAG) }
        compose.onNodeWithTag(CRASH_NOTICE_DISMISS_TEST_TAG).performClick()
        await { !shown(CRASH_NOTICE_TEST_TAG) }
        assertTrue(source.uploads.isEmpty())
    }

    @Test
    fun theSystemEndingTheAppBringsNoNotice() {
        start(FakeCrashSource(listOf(killed)))
        await { shownText("Nordlicht") }
        compose.waitForIdle()
        assertTrue(!shown(CRASH_NOTICE_TEST_TAG))
    }

    @Test
    fun savedReportsAreListedUnderAboutAndDeleted() {
        val source = FakeCrashSource(listOf(panic, native, killed), unseen = emptyList())
        start(source)
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
        compose.onNodeWithText("More").performClick()
        await { shown(MORE_ABOUT_TEST_TAG) }
        compose.onNodeWithTag(MORE_ABOUT_TEST_TAG).performScrollTo().performClick()
        await { shown(ABOUT_SCREEN_TEST_TAG) }
        compose.onNodeWithTag(ABOUT_CRASH_REPORTS_TEST_TAG).performScrollTo().performClick()
        await { shown(CRASH_REPORTS_SCREEN_TEST_TAG) }
        await { compose.onAllNodesWithTag(CRASH_REPORT_ROW_TEST_TAG).fetchSemanticsNodes().size == 2 }
        assertTrue(shownText("Mumcord was stopped by SIGSEGV"))

        // The newest shown report comes first.
        compose.onAllNodesWithTag(CRASH_REPORT_DELETE_TEST_TAG)[0].performScrollTo().performClick()
        await { shown(CRASH_DELETE_CONFIRM_TEST_TAG) }
        compose.onNodeWithTag(CRASH_DELETE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        await { shownText("Report deleted.") }
        assertEquals(listOf(native.id, killed.id), source.reports.map { it.id })
        await { compose.onAllNodesWithTag(CRASH_REPORT_ROW_TEST_TAG).fetchSemanticsNodes().size == 1 }

        compose.onNodeWithTag(CRASH_DELETE_ALL_TEST_TAG).performScrollTo().performClick()
        await { shown(CRASH_DELETE_CONFIRM_TEST_TAG) }
        compose.onNodeWithTag(CRASH_DELETE_CONFIRM_TEST_TAG).performScrollTo().performClick()
        await { shownText("No crash reports") }
        assertTrue(source.reports.isEmpty())
        compose.onNodeWithContentDescription("Back to About").performClick()
        await { shown(ABOUT_SCREEN_TEST_TAG) }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
