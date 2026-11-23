// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.Stream
import app.mumcord.mobile.bridge.StreamPath
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
import app.mumcord.mobile.session.span
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatLocal
import app.mumcord.mobile.ui.chat.LoadedImage
import app.mumcord.mobile.ui.chat.chatUiState
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.MAIN_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.connected.NAVIGATION_RAIL_TEST_TAG
import app.mumcord.mobile.ui.connected.SIDE_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.streams.LocalStreamPicture
import app.mumcord.mobile.ui.streams.OpenStream
import app.mumcord.mobile.ui.streams.StreamVideo
import app.mumcord.mobile.ui.streams.StreamView
import app.mumcord.mobile.ui.streams.StreamsController
import app.mumcord.mobile.ui.streams.StreamsLocal
import app.mumcord.mobile.ui.streams.key
import app.mumcord.mobile.ui.streams.streamsUiState
import java.time.ZoneOffset
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

/** The tablet of the mockups in landscape: 1180 x 820 dp at two pixels per dp, so the files can be laid over them. */
const val TABLET_LANDSCAPE_QUALIFIERS = "w1180dp-h820dp-land-xhdpi"

/** The same tablet turned to portrait (no mockup exists for it). */
const val TABLET_PORTRAIT_QUALIFIERS = "w820dp-h1180dp-port-xhdpi"

/**
 * Draws the two-column tablet layout (AC-5.31) in both themes: channels and chat of mockup
 * `21-tablet-channels-chat` and the stream in the right column of `22-tablet-stream`, in
 * landscape at the mockups' size and in portrait. The live picture is the stand-in of
 * [StreamsScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TABLET_LANDSCAPE_QUALIFIERS)
class TabletScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockups' tablet.
    private val top = WindowInsets(top = 28.dp)
    private val bottom = WindowInsets(bottom = 20.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val streamActions = StreamsController(repository, scope, flowOf(false), StreamVideo.None)
    private val chatActions = screenshotChatController(repository, scope)
    private val messagesTab = ScreenshotMessages(repository, actions, scope)
    private val more = ScreenshotMore(repository, actions, scope)

    private val lobby = ChatId.Channel(MOCKUP_LOBBY)
    private val key = mockupStream().key

    /** Ben's picture of the sunset is a colour gradient, like the mockup's. */
    private val loader = ChatImageLoader { _, _, _ ->
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF4A3A9A.toInt(), 0xFF7A3FA0.toInt(), 0xFFD0607F.toInt()),
        ).apply { setSize(400, 300) }
        LoadedImage(drawable, 400, 300)
    }

    private fun at(minute: Int): Long = MOCKUP_DAY.atTime(18, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // The strip under a stream shows a message by its quote, as the bridge makes it.
    private fun message(id: String, name: String, session: UInt, minute: Int, text: String): ChatMessage =
        chatMessage(id, lobby, name, session, sentAtMs = at(minute), content = messageContent(text))
            .copy(quote = ReplyQuote(name, text))

    // The conversation of mockup 21.
    private val conversation = listOf(
        message("t1", "Anna", MOCKUP_ANNA, 2, "Hey everyone, ready for game night?"),
        message("t2", "Ben", MOCKUP_BEN, 4, "").let { message ->
            message.copy(
                content = messageContent(
                    blocks = listOf(
                        ContentBlock.Paragraph(listOf(span("Always. Look at the sunset from my hike today:"))),
                        ContentBlock.Image(ImageRef.Embedded("sunset", "image/png", 400u, 300u, 1000u, animated = false)),
                    ),
                ),
            )
        },
        message("t3", "Mira", MOCKUP_MIRA, 7, "").let { message ->
            message.copy(
                mentionsMe = true,
                content = messageContent(
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            listOf(
                                span("@Lea").copy(mention = true),
                                span(" can you pick the first map? I will share my screen in Gaming in a minute."),
                            ),
                        ),
                    ),
                ),
            )
        },
        chatMessage("t4", lobby, "Lea", own = true, sentAtMs = at(8), content = messageContent("Sure, give me two minutes.")),
    )

    // The strip of mockup 22.
    private val duringTheStream = listOf(
        message("u1", "Anna", MOCKUP_ANNA, 20, "Wow, that boss fight looks hard"),
        message("u2", "Ben", MOCKUP_BEN, 21, "Nice, the stream is crisp"),
    )

    @After
    fun tearDown() = scope.cancel()

    private fun capture(name: String, dark: Boolean, messages: List<ChatMessage>, stream: Stream? = null, streams: StreamsLocal = StreamsLocal()) =
        composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
            val session = listOfNotNull(
                BridgeEvent.ConnectionChanged(ConnectionState.Connected),
                BridgeEvent.ServerSynced(mockupServer()),
                stream?.let { BridgeEvent.StreamsChanged(listOf(it)) },
            ).fold(SessionState()) { state, event -> state.fold(event) }
            val state = checkNotNull(
                connectedUiState(session, ConnectedLocal(tab = ConnectedTab.Channels, twoColumns = true), "Nordlicht"),
            )
            val chat = chatUiState(session, ChatLocal(chat = lobby, messages = messages), "Nordlicht", ZoneOffset.UTC)
            val streamsState = streamsUiState(
                session.streams,
                names = { session.server?.people?.get(it)?.name },
                local = streams,
                autoplay = false,
                directConnectionsQuestion = false,
                channels = { "Gaming" },
            )
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
                            chatEnvironment = screenshotChatEnvironment(loader),
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

    private fun channelsAndChat(name: String, dark: Boolean) = capture(name, dark, conversation)

    private fun stream(name: String, dark: Boolean) = capture(
        name,
        dark,
        duringTheStream,
        mockupStream(watch = StreamWatch.Watching(StreamPath.DIRECT), viewers = 1u),
        StreamsLocal(open = OpenStream(key, StreamView.Full)),
    )

    @Test
    fun channelsAndChatLandscapeDark() {
        channelsAndChat("android-tablet-channels-chat-landscape", true)
        // TalkBack finishes one column before it moves to the next.
        for (tag in listOf(NAVIGATION_RAIL_TEST_TAG, SIDE_COLUMN_TEST_TAG, MAIN_COLUMN_TEST_TAG)) {
            composeRule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
        }
    }

    @Test
    fun channelsAndChatLandscapeLight() = channelsAndChat("android-tablet-channels-chat-landscape", false)

    @Test
    fun streamLandscapeDark() = stream("android-tablet-stream-landscape", true)

    @Test
    fun streamLandscapeLight() = stream("android-tablet-stream-landscape", false)

    // No mockup exists for portrait; it is the same layout with a narrower right column.

    @Config(qualifiers = TABLET_PORTRAIT_QUALIFIERS)
    @Test
    fun channelsAndChatPortraitDark() = channelsAndChat("android-tablet-channels-chat-portrait", true)

    @Config(qualifiers = TABLET_PORTRAIT_QUALIFIERS)
    @Test
    fun channelsAndChatPortraitLight() = channelsAndChat("android-tablet-channels-chat-portrait", false)

    @Config(qualifiers = TABLET_PORTRAIT_QUALIFIERS)
    @Test
    fun streamPortraitDark() = stream("android-tablet-stream-portrait", true)

    @Config(qualifiers = TABLET_PORTRAIT_QUALIFIERS)
    @Test
    fun streamPortraitLight() = stream("android-tablet-stream-portrait", false)

    // No mockup: the stream collapsed to the mini window over the chat in the right column.

    @Test
    fun miniWindowDark() = capture(
        "android-tablet-mini-window",
        true,
        conversation,
        mockupStream(watch = StreamWatch.Watching(StreamPath.DIRECT), viewers = 1u),
        StreamsLocal(open = OpenStream(key, StreamView.Mini)),
    )

    @Test
    fun miniWindowLight() = capture(
        "android-tablet-mini-window",
        false,
        conversation,
        mockupStream(watch = StreamWatch.Watching(StreamPath.DIRECT), viewers = 1u),
        StreamsLocal(open = OpenStream(key, StreamView.Mini)),
    )
}
