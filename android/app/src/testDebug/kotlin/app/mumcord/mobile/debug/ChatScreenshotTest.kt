// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.UnsupportedHint
import app.mumcord.mobile.session.UnsupportedKind
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.span
import app.mumcord.mobile.session.withHint
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSource
import app.mumcord.mobile.ui.chat.ChatLocal
import app.mumcord.mobile.ui.chat.ChatOverlays
import app.mumcord.mobile.ui.chat.ChatSheetKind
import app.mumcord.mobile.ui.chat.LoadedImage
import app.mumcord.mobile.ui.chat.ReplyTarget
import app.mumcord.mobile.ui.chat.chatUiState
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.connectedUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Draws the Chat tab in both themes at the size of the mockups, with the conversation of mockup
 * `07-channel-chat` and the favourites of `08-gif-favourites`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ChatScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val chatActions = screenshotChatController(repository, scope)

    private val lobby = ChatId.Channel(MOCKUP_LOBBY)

    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }
        .withHint(UnsupportedHint(1, "Jonas", UnsupportedKind.FileTransfer))

    /** The pictures of the mockups are colour gradients; the loader draws the same ones. */
    private val gradients = listOf(
        intArrayOf(0xFF3B2F8F.toInt(), 0xFFD0607F.toInt()),
        intArrayOf(0xFF0E6F78.toInt(), 0xFF7A3FA0.toInt()),
        intArrayOf(0xFF7A3FA0.toInt(), 0xFFE0905A.toInt()),
        intArrayOf(0xFFD0607F.toInt(), 0xFF3B2F8F.toInt()),
        intArrayOf(0xFF2F6F4F.toInt(), 0xFF3B2F8F.toInt()),
        intArrayOf(0xFFE0905A.toInt(), 0xFF7A3FA0.toInt()),
    )
    private val favourites = gradients.indices.map { GifFavourite("https://gifs.example/$it.gif", addedAtMs = 0, lastUsedAtMs = 0) }

    private val loader = ChatImageLoader { source, _, _ ->
        val (colours, width, height) = when (source) {
            is ChatImageSource.Embedded ->
                Triple(intArrayOf(0xFF3B2F8F.toInt(), 0xFF7A3FA0.toInt(), 0xFFD0607F.toInt()), source.width, source.height)
            is ChatImageSource.Remote -> Triple(gradients[favourites.indexOfFirst { it.url == source.url }], 400, 400)
        }
        val drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, colours).apply { setSize(width, height) }
        LoadedImage(drawable, width, height)
    }

    private fun at(hour: Int, minute: Int): Long =
        MOCKUP_DAY.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun message(id: String, name: String, session: UInt, minute: Int, text: String): ChatMessage =
        chatMessage(id, lobby, name, session, sentAtMs = at(18, minute), content = messageContent(text))

    private val annaAsks = message("m4", "Anna", MOCKUP_ANNA, 48, "").let { message ->
        message.copy(
            mentionsMe = true,
            content = messageContent(
                blocks = listOf(
                    ContentBlock.Paragraph(listOf(span("@Lea").copy(mention = true), span(" can you host the first round?"))),
                ),
            ),
            quote = ReplyQuote("Anna", "can you host the first round?"),
        )
    }

    private val messages = listOf(
        message("m1", "Anna", MOCKUP_ANNA, 42, "Evening all! Ready for tonight's session?"),
        message("m2", "Ben", MOCKUP_BEN, 44, "").let { message ->
            message.copy(
                content = messageContent(
                    blocks = listOf(
                        ContentBlock.Paragraph(listOf(span("Always. Here is the map we picked:"))),
                        ContentBlock.Image(ImageRef.Embedded("map", "image/png", 400u, 225u, 1000u, animated = false)),
                    ),
                ),
            )
        },
        message("m3", "Mira", MOCKUP_MIRA, 46, "").let { message ->
            message.copy(
                content = messageContent(
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            listOf(
                                span("Rules are here: "),
                                span("nordlicht.example/rules").copy(
                                    link = SafeLink("https://nordlicht.example/rules", "nordlicht.example", LinkKind.WEB, misleading = false),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        },
        annaAsks,
        chatMessage("m5", lobby, "Lea", own = true, sentAtMs = at(18, 49), content = messageContent("Sure, give me five minutes."))
            .let { it.copy(content = it.content.copy(reply = annaAsks.quote)) },
    )

    private val chat = ChatLocal(chat = lobby, messages = messages)

    @After
    fun tearDown() = scope.cancel()

    private fun capture(name: String, dark: Boolean, local: ChatLocal, session: SessionState = this.session) =
        composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
            val state = checkNotNull(connectedUiState(session, ConnectedLocal(tab = ConnectedTab.Chat), "Nordlicht"))
            val chatState = checkNotNull(chatUiState(session, local, "Nordlicht", ZoneOffset.UTC))
            val environment = screenshotChatEnvironment(loader)
            MumcordTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    ConnectedShell(
                        state,
                        actions,
                        onDisconnect = {},
                        onMicrophone = {},
                        chat = chatState,
                        chatBadge = 0,
                        chatActions = chatActions,
                        chatEnvironment = environment,
                        topInsets = top,
                        bottomInsets = bottom,
                    )
                    ChatOverlays(chatState, chatActions, loader, environment.images, top, bottom)
                }
            }
        }

    private fun chat(dark: Boolean) = capture("android-chat", dark, chat)

    private fun gifFavourites(dark: Boolean) =
        capture("android-gif-favourites", dark, chat.copy(sheet = ChatSheetKind.Gifs, gifs = favourites))

    // No mockup exists for the states below; they are built from the design system.
    private fun reply(dark: Boolean) = capture(
        "android-chat-reply",
        dark,
        chat.copy(reply = ReplyTarget(annaAsks.id, annaAsks.quote), draft = "On it", cursor = 5),
    )

    private fun mentionAutocomplete(dark: Boolean) = capture(
        "android-chat-mention-autocomplete",
        dark,
        chat.copy(
            draft = "Thanks @",
            cursor = 8,
            mentions = listOf(
                MentionCandidate(MOCKUP_ANNA, "Anna"),
                MentionCandidate(MOCKUP_BEN, "Ben"),
                MentionCandidate(MOCKUP_MIRA, "Mira"),
                MentionCandidate(MOCKUP_JONAS, "Jonas"),
            ),
        ),
    )

    private fun linkWarning(dark: Boolean) = capture(
        "android-chat-link-warning",
        dark,
        chat.copy(linkWarning = SafeLink("https://nordlicht.evil.example/rules", "nordlicht.evil.example", LinkKind.WEB, misleading = true)),
    )

    private fun imageViewer(dark: Boolean) = capture(
        "android-chat-image-viewer",
        dark,
        chat.copy(viewer = ChatImageSource.Embedded(lobby, "m2", "map", 400, 225)),
    )

    private fun empty(dark: Boolean) =
        capture("android-chat-empty", dark, ChatLocal(chat = lobby), session.copy(hints = emptyList()))

    @Test fun chatDark() = chat(dark = true)

    @Test fun chatLight() = chat(dark = false)

    @Test fun gifFavouritesDark() = gifFavourites(dark = true)

    @Test fun gifFavouritesLight() = gifFavourites(dark = false)

    @Test fun replyDark() = reply(dark = true)

    @Test fun replyLight() = reply(dark = false)

    @Test fun mentionAutocompleteDark() = mentionAutocomplete(dark = true)

    @Test fun mentionAutocompleteLight() = mentionAutocomplete(dark = false)

    @Test fun linkWarningDark() = linkWarning(dark = true)

    @Test fun linkWarningLight() = linkWarning(dark = false)

    @Test fun imageViewerDark() = imageViewer(dark = true)

    @Test fun imageViewerLight() = imageViewer(dark = false)

    @Test fun emptyDark() = empty(dark = true)

    @Test fun emptyLight() = empty(dark = false)
}
