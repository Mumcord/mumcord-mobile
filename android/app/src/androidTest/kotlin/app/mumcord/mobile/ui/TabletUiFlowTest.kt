// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.mockupStream
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.chat.CHAT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.CHANNEL_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MAIN_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.NAVIGATION_RAIL_TEST_TAG
import app.mumcord.mobile.ui.connected.SIDE_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.streams.LocalStreamPicture
import app.mumcord.mobile.ui.streams.STREAM_CHAT_STRIP_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_COLLAPSE_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_EXPAND_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_FILL_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_FULL_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_LEAVE_FULL_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_MINI_WINDOW_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_TILE_TEST_TAG
import app.mumcord.mobile.ui.streams.StreamView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The two-column tablet layout (AC-5.31) against a fake engine. The tablet's size is forced on
 * the emulator's phone screen, and changed while the app runs, as rotating the tablet or
 * resizing a split screen does; what the user did must survive every change.
 */
class TabletUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine

    /** The forced size of the app; null is the emulator's own phone screen. */
    private var size by mutableStateOf<DpSize?>(LANDSCAPE)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    private fun connect(): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            streamsAutoplay = flowOf(false),
        )
        compose.setContent {
            MumcordTheme {
                CompositionLocalProvider(LocalStreamPicture provides { _, _ -> }) {
                    val app = @Composable {
                        AppRoute(
                            viewModel,
                            LocalNetworkSystem(ask = {}, openSettings = {}),
                            MicrophoneSystem(ask = {}, openSettings = {}),
                        )
                    }
                    val forced = size
                    if (forced == null) app() else DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(forced)) { app() }
                }
            }
        }
        compose.connectToNordlicht()
        await { exists("Lobby") && shown(NAVIGATION_RAIL_TEST_TAG) }
        return viewModel
    }

    @Test
    fun channelsAndChatSideBySideAndAStreamFillsTheRightColumnWithTheChatStripBelow() {
        val viewModel = connect()
        // Left: the rail and the channel tree with the voice bar; right: the channel chat.
        await { shown(SIDE_COLUMN_TEST_TAG) && shown(CHANNEL_ROW_TEST_TAG) && shown(CHAT_SCREEN_TEST_TAG) }
        assertFalse("no bottom tab bar in two columns", compose.onAllNodes(hasText("Chat")).fetchSemanticsNodes().isNotEmpty())
        type("Draft from the tablet")

        engine.showStreams(listOf(mockupStream()))
        await { shown(STREAM_TILE_TEST_TAG) }
        compose.onNodeWithTag(STREAM_TILE_TEST_TAG).press()
        await { shown(STREAM_COLUMN_TEST_TAG) && shown(STREAM_CHAT_STRIP_TEST_TAG) }
        assertFalse("the stream covers only the right column", shown(STREAM_FULL_SCREEN_TEST_TAG))
        assertFalse("its tile left the tree", shown(STREAM_TILE_TEST_TAG))
        assertTrue(shown(CHANNEL_ROW_TEST_TAG) && shown(NAVIGATION_RAIL_TEST_TAG))
        await { exists("Draft from the tablet") }

        // The full screen button takes the whole screen; leaving it returns to the column.
        compose.onNodeWithTag(STREAM_FILL_SCREEN_TEST_TAG).performClick()
        await { shown(STREAM_FULL_SCREEN_TEST_TAG) && !shown(NAVIGATION_RAIL_TEST_TAG) }
        compose.onNodeWithTag(STREAM_LEAVE_FULL_SCREEN_TEST_TAG).performClick()
        await { shown(STREAM_COLUMN_TEST_TAG) && shown(NAVIGATION_RAIL_TEST_TAG) }

        // Portrait keeps both columns and the stream in the right one.
        size = PORTRAIT
        await { shown(STREAM_COLUMN_TEST_TAG) && shown(SIDE_COLUMN_TEST_TAG) }

        // The mini window button leaves the whole chat with the stream floating above it.
        compose.onNodeWithTag(STREAM_COLLAPSE_TEST_TAG).performClick()
        await { shown(STREAM_MINI_WINDOW_TEST_TAG) && shown(CHAT_SCREEN_TEST_TAG) }
        compose.onNodeWithTag(STREAM_EXPAND_TEST_TAG).performClick()
        await { shown(STREAM_COLUMN_TEST_TAG) }
        assertEquals(StreamView.Full, viewModel.streams.state.value.open?.view)
    }

    @Test
    fun resizingBetweenPhoneAndTabletKeepsTheTabTheDraftAndTheStream() {
        val viewModel = connect()
        type("Kept across sizes")
        engine.showStreams(listOf(mockupStream()))
        await { shown(STREAM_TILE_TEST_TAG) }
        compose.onNodeWithTag(STREAM_TILE_TEST_TAG).press()
        await { shown(STREAM_COLUMN_TEST_TAG) }

        // Narrow as half a split screen: the phone layout, where an open stream is full screen.
        size = SPLIT_HALF
        await { shown(STREAM_FULL_SCREEN_TEST_TAG) && !shown(NAVIGATION_RAIL_TEST_TAG) }
        compose.onNodeWithTag(STREAM_COLLAPSE_TEST_TAG).performClick()
        await { shown(STREAM_MINI_WINDOW_TEST_TAG) }
        compose.onNode(hasText("Chat") or hasContentDescription("Chat, ", substring = true)).performClick()
        await { shown(CHAT_SCREEN_TEST_TAG) && exists("Kept across sizes") }

        // Wide again: the Chat tab is part of Channels, the stream still floats over the chat.
        size = LANDSCAPE
        await { shown(NAVIGATION_RAIL_TEST_TAG) && shown(CHANNEL_ROW_TEST_TAG) && shown(STREAM_MINI_WINDOW_TEST_TAG) }
        await { exists("Kept across sizes") }

        // Messages takes the right column; the channels stay on the left.
        compose.onNode(hasText("Messages") or hasContentDescription("Messages, ", substring = true)).performClick()
        await { shown(MESSAGES_SCREEN_TEST_TAG) && shown(CHANNEL_ROW_TEST_TAG) && shown(MAIN_COLUMN_TEST_TAG) }
        assertTrue(shown(STREAM_MINI_WINDOW_TEST_TAG))
        assertEquals(StreamView.Mini, viewModel.streams.state.value.open?.view)
    }

    private fun type(text: String) = compose.onNode(hasSetTextAction()).performTextInput(text)

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        val LANDSCAPE = DpSize(1180.dp, 820.dp)
        val PORTRAIT = DpSize(820.dp, 1180.dp)
        val SPLIT_HALF = DpSize(580.dp, 820.dp)
    }
}
