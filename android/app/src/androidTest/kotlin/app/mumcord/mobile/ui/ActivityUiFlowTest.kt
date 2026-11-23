// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.ActivityKind
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.ACTIVITY_EMPTY_TEST_TAG
import app.mumcord.mobile.ui.connected.ACTIVITY_ENTRY_TEST_TAG
import app.mumcord.mobile.ui.connected.ACTIVITY_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_ACTIVITY_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The activity log in the More tab (FR-5.94) on a device against a fake engine. */
class ActivityUiFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private var nextId = 1

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    private fun connect() {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        runBlocking { repository.loadServers() }
        val viewModel = AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess())
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                )
            }
        }
        compose.connectToNordlicht()
        await { shown(VOICE_BAR_TEST_TAG) }
    }

    /** What the bridge reports when someone does [kind]. */
    private fun happens(kind: ActivityKind, name: String, channelName: String? = null, self: Boolean = false) {
        val id = nextId++
        engine.emit(
            BridgeEvent.ActivityAdded(
                ActivityEntry(
                    id = id.toULong(),
                    kind = kind,
                    session = (100 + id).toUInt(),
                    name = name,
                    isSelf = self,
                    channelId = channelName?.let { if (it == "Lobby") MOCKUP_LOBBY else MOCKUP_GAMING },
                    channelName = channelName,
                    atMs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun entries() = compose.onAllNodesWithTag(ACTIVITY_ENTRY_TEST_TAG).fetchSemanticsNodes().size

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    @Test
    fun entriesAppearLiveTheFiltersWorkAndTheBadgeCountsWhatIsNew() {
        connect()
        happens(ActivityKind.CONNECTED, "Lea", self = true)
        happens(ActivityKind.JOINED, "Anna", "Lobby")
        happens(ActivityKind.LEFT, "Ben")

        compose.onNodeWithText("More").performClick()
        await { described("Activity, 2 new entries") }

        compose.onNodeWithTag(MORE_ACTIVITY_TEST_TAG).performClick()
        await { shown(ACTIVITY_SCREEN_TEST_TAG) }
        compose.onNodeWithText("Today", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("You connected to Nordlicht").assertIsDisplayed()
        compose.onNodeWithText("Anna joined Lobby").assertIsDisplayed()
        compose.onNodeWithText("Ben left the server").assertIsDisplayed()

        // While the log is open, what happens appears at once, on top.
        happens(ActivityKind.MOVED, "Jonas", "Gaming")
        happens(ActivityKind.STREAM_STARTED, "Mira", "Gaming")
        await { exists("Jonas moved to Gaming") && exists("Mira started a screen share in Gaming") }
        assertEquals(5, entries())

        compose.onNodeWithText("Streams").performClick()
        await { entries() == 1 }
        compose.onNodeWithText("Mira started a screen share in Gaming").assertIsDisplayed()

        compose.onNodeWithText("Joins").performClick()
        await { entries() == 4 && !exists("Mira started a screen share in Gaming") }

        happens(ActivityKind.STREAM_STOPPED, "Mira")
        compose.onNodeWithText("All").performClick()
        await { entries() == 6 && exists("Mira stopped their screen share") }

        // Back on More, what was in the log counts as seen.
        Espresso.pressBack()
        await { shown(MORE_ACTIVITY_TEST_TAG) }
        compose.onNodeWithContentDescription("Activity").assertIsDisplayed()
        happens(ActivityKind.JOINED, "Ben", "Lobby")
        await { described("Activity, 1 new entry") }
    }

    @Test
    fun withoutActivityTheLogSaysSoAndAnEmptyFilterToo() {
        connect()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag(MORE_ACTIVITY_TEST_TAG).performClick()
        await { shown(ACTIVITY_EMPTY_TEST_TAG) }
        compose.onNodeWithText("No activity yet").assertIsDisplayed()

        happens(ActivityKind.JOINED, "Anna", "Lobby")
        await { exists("Anna joined Lobby") }
        compose.onNodeWithText("Streams").performClick()
        await { exists("Nothing of this kind yet.") }

        compose.onNodeWithContentDescription("Back to More").performClick()
        await { shown(MORE_ACTIVITY_TEST_TAG) }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
