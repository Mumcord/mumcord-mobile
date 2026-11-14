// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.FakeIdleNotifier
import app.mumcord.mobile.session.IdleDisconnect
import app.mumcord.mobile.session.IdleTimes
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connect.SESSION_ENDED_CLOSE_TEST_TAG
import app.mumcord.mobile.ui.connect.SESSION_ENDED_RECONNECT_TEST_TAG
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_SETTINGS_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.SETTINGS_AUTOPLAY_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_IDLE_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_LOAD_MEDIA_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_REDUCED_VOICE_TEST_TAG
import app.mumcord.mobile.ui.connected.SETTINGS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
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
 * The settings in the More tab (FR-5.41, FR-5.42) and "disconnect when alone" (AC-5.30) on a
 * device against a fake engine, where the user is alone in the Lobby. The times of the rule
 * are shortened to two seconds alone and two seconds of warning.
 */
class SettingsUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val notifier = FakeIdleNotifier()
    private lateinit var idle: IdleDisconnect

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Starts the app and connects to Nordlicht. */
    private fun connect() {
        val server = mockupVoiceServer().let { all ->
            all.copy(people = all.people.map { if (it.isSelf) it else it.copy(channelId = MOCKUP_GAMING) })
        }
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        runBlocking { repository.loadServers() }
        idle = IdleDisconnect(repository, notifier, scope, clock = System::currentTimeMillis) { IdleTimes(ALONE_MILLIS, WARNING_MILLIS) }
        val viewModel = AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess(), idle = idle)
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                )
            }
        }
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
    }

    private fun openSettings() {
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag(MORE_SETTINGS_TEST_TAG).performClick()
        await { shown(SETTINGS_SCREEN_TEST_TAG) }
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    @Test
    fun moreLeadsToTheSettingsWithMobileDataAndBatteryAndEverySwitchIsSaved() {
        connect()
        openSettings()

        compose.onNodeWithText("Mobile data", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Battery", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Start streams automatically").assertIsDisplayed()
        compose.onNodeWithText("Load images and GIFs").assertIsDisplayed()
        compose.onNodeWithText("Reduced voice quality").assertIsDisplayed()
        compose.onNodeWithText("Disconnect when alone for 30 minutes").assertIsDisplayed()
        compose.onNodeWithText("You get a notification first").assertIsDisplayed()

        // The defaults of the specification.
        compose.onNodeWithTag(SETTINGS_AUTOPLAY_TEST_TAG).assertIsOff()
        compose.onNodeWithTag(SETTINGS_LOAD_MEDIA_TEST_TAG).assertIsOn()
        compose.onNodeWithTag(SETTINGS_REDUCED_VOICE_TEST_TAG).assertIsOff()
        compose.onNodeWithTag(SETTINGS_IDLE_TEST_TAG).assertIsOff()

        compose.onNodeWithTag(SETTINGS_AUTOPLAY_TEST_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_LOAD_MEDIA_TEST_TAG).performClick()
        compose.onNodeWithTag(SETTINGS_REDUCED_VOICE_TEST_TAG).performClick()

        await {
            engine.mobileSettings.run { mobileDataAutoplayStreams && !mobileDataLoadMedia && mobileDataReducedVoice }
        }
        compose.onNodeWithTag(SETTINGS_AUTOPLAY_TEST_TAG).assertIsOn()
        compose.onNodeWithTag(SETTINGS_LOAD_MEDIA_TEST_TAG).assertIsOff()
        compose.onNodeWithTag(SETTINGS_REDUCED_VOICE_TEST_TAG).assertIsOn()

        compose.onNodeWithContentDescription("Back to More").performClick()
        await { shown(MORE_SETTINGS_TEST_TAG) }
    }

    @Test
    fun aloneWithTheSwitchOnTheAppWarnsAndStayConnectedKeepsTheSession() {
        connect()
        openSettings()
        compose.onNodeWithTag(SETTINGS_IDLE_TEST_TAG).performClick()

        await { notifier.warning }
        idle.stayConnected()
        await { !notifier.warning }
        assertTrue("Still connected", shown(VOICE_BAR_TEST_TAG))

        // Alone for as long again: the warning comes back, the session is still there.
        await { notifier.shown.size == 2 }
        assertEquals(0, engine.disconnects)
    }

    @Test
    fun withoutATapTheAppDisconnectsSaysSoAndOffersToReconnect() {
        connect()
        openSettings()
        compose.onNodeWithTag(SETTINGS_IDLE_TEST_TAG).performClick()

        await { notifier.warning }
        await { shown(SESSION_ENDED_RECONNECT_TEST_TAG) }

        assertEquals(1, engine.disconnects)
        assertTrue(notifier.disconnected)
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onNodeWithText("You were alone in a channel on Nordlicht for 30 minutes, so Mumcord disconnected.")
            .assertIsDisplayed()

        compose.onNodeWithTag(SESSION_ENDED_CLOSE_TEST_TAG).performClick()
        await { !shown(SESSION_ENDED_CLOSE_TEST_TAG) }
        assertTrue("The notification goes with the notice", !notifier.disconnected)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val ALONE_MILLIS = 2_000L
        const val WARNING_MILLIS = 2_000L
    }
}
