// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.about.ABOUT_LICENCES_TEST_TAG
import app.mumcord.mobile.ui.about.ABOUT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.about.ABOUT_SOURCE_TEST_TAG
import app.mumcord.mobile.ui.about.LICENCES_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.about.NoticesSource
import app.mumcord.mobile.ui.about.UPDATE_DISMISS_TEST_TAG
import app.mumcord.mobile.ui.about.UPDATE_DOWNLOAD_TEST_TAG
import app.mumcord.mobile.ui.about.UPDATE_NOTICE_TEST_TAG
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_ABOUT_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_SETTINGS_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.SETTINGS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_UPDATE_CHECK_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.update.AppInfo
import app.mumcord.mobile.update.InMemoryUpdateStore
import app.mumcord.mobile.update.UpdateChecker
import app.mumcord.mobile.update.releaseJson
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * The update notice on the server list (FR-5.101, AC-5.34) and About Mumcord with its licences
 * (FR-5.100) on a device against a fake engine and a fake release source: GitHub is never asked.
 */
class AboutUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val opened = CopyOnWriteArrayList<String>()
    private var asked = 0

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Starts the app as release 0.2.0 while 0.3.0 is the latest release. */
    private fun start() {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        runBlocking { repository.loadServers() }
        val info = AppInfo("0.2.0", "0.8.0 (6d2ac79e)", debuggable = false)
        val checker = UpdateChecker(info, { releaseJson().also { asked++ } }, InMemoryUpdateStore())
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            appInfo = info,
            updates = checker,
            notices = NoticesSource { "Mumcord Mobile notices\n\nInstrument Sans (font)\nOFL-1.1\n" },
        )
        val links = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides links) {
                MumcordTheme {
                    AppRoute(
                        viewModel,
                        LocalNetworkSystem(ask = {}, openSettings = {}),
                        MicrophoneSystem(ask = {}, openSettings = {}),
                    )
                }
            }
        }
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    @Test
    fun theNoticeOpensTheApkDownloadAndCanBeClosed() {
        start()
        await { shown(UPDATE_NOTICE_TEST_TAG) }
        compose.onNodeWithText("Version 0.3.0 is available.", substring = true).assertExists()
        compose.onNodeWithTag(UPDATE_DOWNLOAD_TEST_TAG).performClick()
        await { opened.isNotEmpty() }
        assertEquals(
            listOf("https://github.com/Mumcord/mumcord-mobile/releases/download/v0.3.0/mumcord-mobile-0.3.0-arm64.apk"),
            opened.toList(),
        )
        compose.onNodeWithTag(UPDATE_DISMISS_TEST_TAG).performClick()
        await { !shown(UPDATE_NOTICE_TEST_TAG) }
        assertEquals("asked once", 1, asked)
    }

    @Test
    fun aboutShowsTheVersionsLicencesAndSourceAndTheSwitchIsSaved() {
        start()
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
        compose.onNodeWithText("More").performClick()
        await { shown(MORE_ABOUT_TEST_TAG) }
        compose.onNodeWithTag(MORE_ABOUT_TEST_TAG).performScrollTo().performClick()
        await { shown(ABOUT_SCREEN_TEST_TAG) }
        compose.onNodeWithText("0.8.0 (6d2ac79e)").assertExists()
        compose.onNodeWithTag(ABOUT_SOURCE_TEST_TAG).performScrollTo().performClick()
        await { "https://github.com/Mumcord/mumcord-mobile/tree/v0.2.0" in opened }

        compose.onNodeWithTag(ABOUT_LICENCES_TEST_TAG).performScrollTo().performClick()
        await { shown(LICENCES_SCREEN_TEST_TAG) }
        await { compose.onAllNodesWithText("Instrument Sans", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back to About").performClick()
        await { shown(ABOUT_SCREEN_TEST_TAG) }
        compose.onNodeWithContentDescription("Back to More").performClick()

        await { shown(MORE_SETTINGS_TEST_TAG) }
        compose.onNodeWithTag(MORE_SETTINGS_TEST_TAG).performClick()
        await { shown(SETTINGS_SCREEN_TEST_TAG) }
        compose.onNodeWithTag(SETTINGS_UPDATE_CHECK_TEST_TAG).performScrollTo().performClick()
        await { !engine.mobileSettings().updateCheck }
        assertFalse(engine.mobileSettings().updateCheck)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
