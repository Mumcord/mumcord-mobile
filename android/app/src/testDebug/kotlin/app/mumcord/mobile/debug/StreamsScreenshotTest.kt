// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.Stream
import app.mumcord.mobile.bridge.StreamPath
import app.mumcord.mobile.bridge.StreamUnreachable
import app.mumcord.mobile.bridge.StreamWatch
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.mockupStream
import app.mumcord.mobile.ui.chat.ChatLocal
import app.mumcord.mobile.ui.chat.chatUiState
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.streams.LocalStreamPicture
import app.mumcord.mobile.ui.streams.OpenStream
import app.mumcord.mobile.ui.streams.StreamKey
import app.mumcord.mobile.ui.streams.StreamVideo
import app.mumcord.mobile.ui.streams.StreamView
import app.mumcord.mobile.ui.streams.StreamsController
import app.mumcord.mobile.ui.streams.StreamsLocal
import app.mumcord.mobile.ui.streams.key
import app.mumcord.mobile.ui.streams.streamsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Draws the stream screens in both themes at the size of the mockups: the tile of mockup
 * `02-channels`, the full screen of `11-stream-fullscreen` and the mini window of
 * `12-stream-mini-window`, with Mira sharing her screen. The live picture is drawn on the GPU in
 * the app; here a stand-in in the colours of the mockups' picture takes its place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class StreamsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val streamActions = StreamsController(repository, scope, flowOf(false), StreamVideo.None)
    private val chatActions = screenshotChatController(repository, scope)
    private val messagesTab = ScreenshotMessages(repository, actions, scope)
    private val more = ScreenshotMore(repository, actions, scope)

    private val key = mockupStream().key
    private val lobby = ChatId.Channel(MOCKUP_LOBBY)

    private fun session(stream: Stream, question: Boolean = false): SessionState = listOfNotNull(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
        BridgeEvent.StreamsChanged(listOf(stream)),
        BridgeEvent.DirectConnectionsQuestion.takeIf { question },
    ).fold(SessionState()) { state, event -> state.fold(event) }

    @After
    fun tearDown() = scope.cancel()

    private fun capture(
        name: String,
        dark: Boolean,
        stream: Stream,
        streams: StreamsLocal = StreamsLocal(),
        tab: ConnectedTab = ConnectedTab.Channels,
        question: Boolean = false,
    ) = composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
        val session = session(stream, question)
        val state = checkNotNull(connectedUiState(session, ConnectedLocal(tab = tab), "Nordlicht"))
        val streamsState = streamsUiState(
            session.streams,
            names = { session.server?.people?.get(it)?.name },
            local = streams,
            autoplay = false,
            directConnectionsQuestion = session.directConnectionsQuestion,
        )
        val chat = if (tab == ConnectedTab.Chat) chatUiState(session, ChatLocal(chat = lobby, messages = messages), "Nordlicht", ZoneOffset.UTC) else null
        MumcordTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalStreamPicture provides { _, modifier -> MockupPicture(modifier) }) {
                Box(Modifier.fillMaxSize()) {
                    ConnectedShell(
                        state,
                        actions,
                        onDisconnect = {},
                        onMicrophone = {},
                        chat = chat,
                        chatBadge = 0,
                        chatActions = chatActions,
                        chatEnvironment = screenshotChatEnvironment(),
                        messages = messagesTab.state(session),
                        messagesActions = messagesTab.actions,
                        conversation = null,
                        conversationActions = messagesTab.conversationActions,
                        more = more.state(session),
                        moreActions = more.actions,
                        onNotificationSettings = {},
                        onIdentities = {},
                        topInsets = top,
                        bottomInsets = bottom,
                        streams = streamsState,
                        streamActions = streamActions,
                    )
                }
            }
        }
    }

    private val watching = mockupStream(watch = StreamWatch.Watching(StreamPath.DIRECT))

    private fun at(minute: Int): Long = MOCKUP_DAY.atTime(19, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // The conversation of mockup 12.
    private val messages = listOf(
        chatMessage("s1", lobby, "Anna", MOCKUP_ANNA, sentAtMs = at(2), content = messageContent("Is Mira streaming the new level?")),
        chatMessage("s2", lobby, "Mira", MOCKUP_MIRA, sentAtMs = at(3), content = messageContent("Yes, jump into the stream. The boss fight is next.")),
        chatMessage("s3", lobby, "Ben", MOCKUP_BEN, sentAtMs = at(4), content = messageContent("Watching while I read the chat, this is handy.")),
        chatMessage("s4", lobby, "Lea", own = true, sentAtMs = at(5), content = messageContent("Same here. Stream keeps playing in the corner.")),
        chatMessage("s5", lobby, "Anna", MOCKUP_ANNA, sentAtMs = at(6), content = messageContent("Nice, no need to switch tabs.")),
    )

    @Test
    fun tileDark() = capture("android-streams-tile", true, watching)

    @Test
    fun tileLight() = capture("android-streams-tile", false, watching)

    @Test
    fun fullScreenDark() = fullScreen(true)

    @Test
    fun fullScreenLight() = fullScreen(false)

    private fun fullScreen(dark: Boolean) = capture(
        "android-streams-full-screen",
        dark,
        mockupStream(watch = StreamWatch.Watching(StreamPath.RELAY), viewers = 3u),
        StreamsLocal(open = OpenStream(key, StreamView.Full)),
    )

    @Test
    fun miniWindowDark() = miniWindow(true)

    @Test
    fun miniWindowLight() = miniWindow(false)

    private fun miniWindow(dark: Boolean) = capture(
        "android-streams-mini-window",
        dark,
        watching,
        StreamsLocal(open = OpenStream(key, StreamView.Mini)),
        tab = ConnectedTab.Chat,
    )

    // No mockup exists for the states below; they are built from the design system.

    @Test
    fun tapToWatchDark() = capture("android-streams-tap-to-watch", true, mockupStream())

    @Test
    fun tapToWatchLight() = capture("android-streams-tap-to-watch", false, mockupStream())

    @Test
    fun unreachableDark() = unreachable(true)

    @Test
    fun unreachableLight() = unreachable(false)

    private fun unreachable(dark: Boolean) = capture(
        "android-streams-unreachable",
        dark,
        mockupStream(watch = StreamWatch.Unreachable(StreamUnreachable.NO_PATH)),
        StreamsLocal(open = OpenStream(key, StreamView.Full), requested = setOf(StreamKey(MOCKUP_MIRA, key.kind))),
    )

    @Test
    fun directQuestionDark() = capture("android-streams-direct-question", true, mockupStream(watch = StreamWatch.Connecting), question = true)

    @Test
    fun directQuestionLight() = capture("android-streams-direct-question", false, mockupStream(watch = StreamWatch.Connecting), question = true)
}

/**
 * The picture of the mockups (a code window and a game on a violet desktop), letterboxed at 16:9
 * on black as the real picture is.
 */
@androidx.compose.runtime.Composable
private fun MockupPicture(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color.Black)
        val width = minOf(size.width, size.height * 16f / 9f)
        val height = width * 9f / 16f
        val left = (size.width - width) / 2f
        val topEdge = (size.height - height) / 2f
        fun rect(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float = 0f) = drawRoundRect(
            color,
            topLeft = Offset(left + x * width, topEdge + y * height),
            size = Size(w * width, h * height),
            cornerRadius = CornerRadius(radius),
        )
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF10163A), Color(0xFF2A1A5E), Color(0xFF5B2A73)),
                start = Offset(left, topEdge),
                end = Offset(left + width, topEdge + height),
            ),
            topLeft = Offset(left, topEdge),
            size = Size(width, height),
        )
        clipRect(left, topEdge, left + width, topEdge + height) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0x8CFF9A6B), Color(0x8CFF5F9A), Color.Transparent),
                    center = Offset(left + 0.86f * width, topEdge),
                    radius = 0.3f * width,
                ),
                radius = 0.3f * width,
                center = Offset(left + 0.86f * width, topEdge),
            )
        }
        rect(0.06f, 0.12f, 0.56f, 0.66f, Color(0xFF0D1117), 12f)
        rect(0.06f, 0.12f, 0.56f, 0.09f, Color(0xFF1B2233), 12f)
        listOf(0.24f to Color(0xFF5B8CFF), 0.34f to Color(0xFF3A4668), 0.44f to Color(0xFF3A4668), 0.54f to Color(0xFF3EE39E), 0.64f to Color(0xFF3A4668))
            .forEachIndexed { index, (y, color) -> rect(0.105f, 0.12f + y * 0.66f, 0.56f * listOf(0.6f, 0.44f, 0.7f, 0.36f, 0.52f)[index], 0.033f, color, 4f) }
        rect(0.55f, 0.20f, 0.40f, 0.52f, Color(0xFF1A3A6B), 12f)
        rect(0.55f, 0.48f, 0.40f, 0.24f, Color(0xFF0B1A1D), 0f)
        rect(0.71f, 0.38f, 0.056f, 0.18f, Color(0xFFFFB08A), 12f)
        rect(0f, 0.9f, 1f, 0.1f, Color(0xCC0B0D1E))
        listOf(Color(0xFF6C7BD4), Color(0xFFD46C9D), Color(0xFF6CD4A8), Color(0xFFD46C9D))
            .forEachIndexed { index, color -> rect(0.03f + index * 0.08f, 0.915f, 0.05f, 0.07f, color, 4f) }
    }
}
