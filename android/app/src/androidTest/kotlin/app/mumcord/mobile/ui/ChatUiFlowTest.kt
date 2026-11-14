// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import android.graphics.drawable.GradientDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.session.span
import app.mumcord.mobile.ui.chat.CHAT_GIF_BUTTON_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_IMAGE_BUTTON_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_IMAGE_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_LIST_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_MENTION_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_NEW_MESSAGES_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_REPLY_BAR_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_RETRY_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_SEND_TEST_TAG
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.chat.GIF_TILE_TEST_TAG
import app.mumcord.mobile.ui.chat.IMAGE_SOURCE_CAMERA_TEST_TAG
import app.mumcord.mobile.ui.chat.IMAGE_SOURCE_LIBRARY_TEST_TAG
import app.mumcord.mobile.ui.chat.IMAGE_VIEWER_TEST_TAG
import app.mumcord.mobile.ui.chat.LINK_WARNING_OPEN_TEST_TAG
import app.mumcord.mobile.ui.chat.LINK_WARNING_TEST_TAG
import app.mumcord.mobile.ui.chat.LoadedImage
import app.mumcord.mobile.ui.chat.plainText
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Chat tab on a device against a fake engine, on the server of mockup `02-channels`: the
 * user is Lea in the Lobby.
 */
class ChatUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resizeForTheKeyboard() = compose.resizeForTheKeyboardLikeTheApp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val lobby = ChatId.Channel(MOCKUP_LOBBY)
    private var cameraAsked = 0

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    /** Pictures are small colour fields; nothing is fetched. */
    private val loader = ChatImageLoader { _, _, _ ->
        val drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(-0xc4d071, -0x2f9f81)).apply { setSize(160, 90) }
        LoadedImage(drawable, 160, 90)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun from(id: String, text: String, sentAtMs: Long = 0): ChatMessage =
        chatMessage(id, lobby, "Anna", MOCKUP_ANNA, sentAtMs = sentAtMs, content = messageContent(text))

    /** Starts the app, connects to Nordlicht and opens the Chat tab. */
    private fun openChat(configure: (FakeEngine) -> Unit = {}): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht")))
                    .also(configure)
                    .also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        val viewModel = AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess())
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                    // Stands in for the system's photo picker and camera app.
                    chatImages = ChatImageSystem(
                        pickPhoto = { viewModel.chat.sendImage(byteArrayOf(1, 2, 3), "image/png") },
                        takePhoto = { cameraAsked++ },
                    ),
                    chatImageLoader = loader,
                )
            }
        }
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
        compose.onNodeWithText("Chat").performClick()
        await { shown(CHAT_SCREEN_TEST_TAG) }
        return viewModel
    }

    private fun type(text: String) = compose.onNode(hasSetTextAction()).performTextInput(text)

    private fun own(): List<ChatMessage> = engine.messages[lobby].orEmpty().filter { it.own }

    @Test
    fun theChatTabShowsTheCurrentChannelWithItsMessagesAndMarkupAsPlainText() {
        openChat {
            it.messages[lobby] = mutableListOf(
                from("m1", "Evening all!"),
                from("m2", "<script>alert('x')</script> <b>not bold</b> &amp;"),
            )
        }
        compose.onNodeWithText("# Lobby").assertIsDisplayed()
        compose.onNodeWithText("3 people · Nordlicht").assertIsDisplayed()
        compose.onNodeWithText("Evening all!").assertIsDisplayed()
        compose.onNodeWithText("<script>alert('x')</script> <b>not bold</b> &amp;").assertIsDisplayed()
        await { engine.openChat == lobby }
    }

    @Test
    fun aTypedMessageIsSentAndShown() {
        openChat()
        compose.onNodeWithText("No messages here yet.").assertIsDisplayed()
        type("Hello Lobby")
        compose.onNodeWithTag(CHAT_SEND_TEST_TAG).performClick()
        await { own().size == 1 && exists("Hello Lobby") }
        assertEquals("Hello Lobby", own().single().content.plainText())
        compose.onNodeWithText("Message Lobby…").assertIsDisplayed()
    }

    @Test
    fun aMessageThatDidNotGetOutStaysVisibleAndCanBeRetried() {
        openChat()
        engine.sendFailure = BridgeException.NotConnected("gone")
        type("Do not lose me")
        compose.onNodeWithTag(CHAT_SEND_TEST_TAG).performClick()
        await { shown(CHAT_RETRY_TEST_TAG) }
        awaitDisplayed("Do not lose me")
        awaitDisplayed("Not sent.")

        engine.sendFailure = null
        compose.onNodeWithTag(CHAT_RETRY_TEST_TAG).performClick()
        await { own().size == 1 && !shown(CHAT_RETRY_TEST_TAG) }
        awaitDisplayed("Do not lose me")
    }

    @Test
    fun holdingAMessageStartsAReplyThatCarriesItsQuote() {
        openChat {
            it.messages[lobby] = mutableListOf(
                from("m1", "can you host the first round?").copy(quote = ReplyQuote("Anna", "can you host the first round?")),
            )
        }
        compose.onNodeWithText("can you host the first round?").performTouchInput { longClick() }
        await { shown(CHAT_REPLY_BAR_TEST_TAG) }
        compose.onNodeWithText("Replying to Anna").assertIsDisplayed()

        compose.onNodeWithContentDescription("Cancel reply").performClick()
        await { !shown(CHAT_REPLY_BAR_TEST_TAG) }

        compose.onNodeWithText("can you host the first round?").performTouchInput { longClick() }
        await { shown(CHAT_REPLY_BAR_TEST_TAG) }
        type("Sure")
        compose.onNodeWithTag(CHAT_SEND_TEST_TAG).performClick()
        await { own().size == 1 }
        assertEquals(ReplyQuote("Anna", "can you host the first round?"), own().single().content.reply)
        await { !shown(CHAT_REPLY_BAR_TEST_TAG) }
    }

    @Test
    fun typingAnAtSignOffersPeopleAndPickingOneCompletesTheMention() {
        openChat { it.mentionNames += listOf(MentionCandidate(MOCKUP_ANNA, "Anna"), MentionCandidate(2u, "Ben")) }
        type("Hi @")
        await { compose.onAllNodesWithTag(CHAT_MENTION_TEST_TAG).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("@Anna").performClick()
        await { !shown(CHAT_MENTION_TEST_TAG) }
        compose.onNode(hasSetTextAction() and hasText("Hi @Anna ")).assertIsDisplayed()
    }

    @Test
    fun aMessageArrivingWhileReadingBackShowsThePillWhichJumpsToIt() {
        openChat { engine ->
            engine.messages[lobby] = (0 until 60).map { from("m$it", "Message number $it", sentAtMs = it * 1000L) }.toMutableList()
        }
        await { exists("Message number 59") }
        // Row 0 is the newest; this scrolls back to the oldest ones that are loaded.
        compose.onNodeWithTag(CHAT_LIST_TEST_TAG).performScrollToIndex(45)
        await { !exists("Message number 59") }

        engine.receive(from("new", "Fresh news", sentAtMs = 100_000))
        await { shown(CHAT_NEW_MESSAGES_TEST_TAG) }
        assertEquals("not seen yet, so still unread", 1u, engine.unread[lobby])

        compose.onNodeWithTag(CHAT_NEW_MESSAGES_TEST_TAG).performClick()
        await { exists("Fresh news") && !shown(CHAT_NEW_MESSAGES_TEST_TAG) }
        await { engine.unread[lobby] == null }
    }

    @Test
    fun readingBackLoadsOlderMessagesWithoutLosingThePlace() {
        openChat { engine ->
            engine.messages[lobby] = (0 until 120).map { from("m$it", "Message number $it", sentAtMs = it * 1000L) }.toMutableList()
        }
        await { exists("Message number 119") }
        compose.onNodeWithTag(CHAT_LIST_TEST_TAG).performScrollToIndex(48)
        await { exists("Message number 71") }
        // The next page was read in behind the scenes; what was on screen is still there.
        compose.onNodeWithText("Message number 71").assertIsDisplayed()
        compose.onNodeWithTag(CHAT_LIST_TEST_TAG).performScrollToIndex(95)
        await { exists("Message number 24") }
    }

    @Test
    fun unreadMessagesShowOnTheChatTabUntilItIsOpened() {
        openChat()
        compose.onNodeWithText("Channels").performClick()
        await { engine.openChat == null }
        engine.receive(from("a", "While you were away"))
        await { compose.onAllNodes(hasContentDescription("Chat, 1 unread message")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Chat, 1 unread message").performClick()
        await { exists("While you were away") && engine.unread[lobby] == null }
        compose.onNodeWithText("Chat").assertIsDisplayed()
    }

    @Test
    fun theGifSheetSendsAFavouriteAndTheStarRemovesOne() {
        openChat {
            it.gifFavourites += GifFavourite("https://gifs.example/a.gif", 0, 0)
            it.gifFavourites += GifFavourite("https://gifs.example/b.gif", 0, 0)
        }
        compose.onNodeWithTag(CHAT_GIF_BUTTON_TEST_TAG).performClick()
        await { compose.onAllNodesWithTag(GIF_TILE_TEST_TAG).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Long-press a GIF in chat to add it to your favourites").assertIsDisplayed()

        compose.onAllNodes(hasContentDescription("Remove from favourites")).onFirst().performClick()
        await { compose.onAllNodesWithTag(GIF_TILE_TEST_TAG).fetchSemanticsNodes().size == 1 }
        assertEquals(listOf("https://gifs.example/b.gif"), engine.gifFavourites.map { it.url })

        compose.onNodeWithTag(GIF_TILE_TEST_TAG).performClick()
        await { own().size == 1 && !shown(GIF_TILE_TEST_TAG) }
        assertEquals("https://gifs.example/b.gif", own().single().content.plainText())
    }

    @Test
    fun holdingAGifInTheChatAddsItToTheFavourites() {
        val gif = SafeLink("https://gifs.example/party.gif", "gifs.example", LinkKind.GIF, misleading = false)
        openChat {
            it.messages[lobby] = mutableListOf(
                from("m1", "").copy(
                    content = messageContent(blocks = listOf(ContentBlock.Paragraph(listOf(span(gif.url).copy(link = gif))))),
                ),
            )
        }
        await { shown(CHAT_IMAGE_TEST_TAG) }
        compose.onNodeWithTag(CHAT_IMAGE_TEST_TAG).performTouchInput { longClick() }
        await { engine.gifFavourites.any { it.url == gif.url } }
        await { exists("Added to your GIF favourites.") }
    }

    @Test
    fun thePictureButtonOffersLibraryAndCameraAndAChosenPictureIsSent() {
        openChat()
        compose.onNodeWithTag(CHAT_IMAGE_BUTTON_TEST_TAG).performClick()
        await { shown(IMAGE_SOURCE_CAMERA_TEST_TAG) }
        compose.onNodeWithTag(IMAGE_SOURCE_CAMERA_TEST_TAG).performClick()
        await { cameraAsked == 1 && !shown(IMAGE_SOURCE_CAMERA_TEST_TAG) }

        compose.onNodeWithTag(CHAT_IMAGE_BUTTON_TEST_TAG).performClick()
        await { shown(IMAGE_SOURCE_LIBRARY_TEST_TAG) }
        compose.onNodeWithTag(IMAGE_SOURCE_LIBRARY_TEST_TAG).performClick()
        await { own().size == 1 && shown(CHAT_IMAGE_TEST_TAG) }

        // Tapping the picture opens it large; going back closes it.
        compose.onNodeWithTag(CHAT_IMAGE_TEST_TAG).performClick()
        await { shown(IMAGE_VIEWER_TEST_TAG) }
        Espresso.pressBack()
        await { !shown(IMAGE_VIEWER_TEST_TAG) }
        compose.onNodeWithTag(CHAT_SCREEN_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun aMisleadingLinkShowsItsRealTargetBeforeAnythingOpens() {
        val link = SafeLink("https://bank.example.evil.test/login", "bank.example.evil.test", LinkKind.WEB, misleading = true)
        val viewModel = openChat {
            it.messages[lobby] = mutableListOf(
                from("m1", "").copy(
                    content = messageContent(blocks = listOf(ContentBlock.Paragraph(listOf(span("https://bank.example/login").copy(link = link))))),
                ),
            )
        }
        // The real host is written next to the misleading text.
        compose.onNodeWithText("https://bank.example/login (bank.example.evil.test)").performFirstLinkClick()
        await { shown(LINK_WARNING_TEST_TAG) }
        compose.onNodeWithTag(LINK_WARNING_TEST_TAG).assertIsDisplayed().assert(hasText("https://bank.example.evil.test/login", substring = true))
        assertNull("nothing was handed to the browser", viewModel.chat.local.value.urlToOpen)

        compose.onNodeWithText("Cancel").performClick()
        await { !shown(LINK_WARNING_TEST_TAG) }
        assertNull(viewModel.chat.local.value.urlToOpen)
        assertTrue(shown(CHAT_SCREEN_TEST_TAG))
        compose.onAllNodesWithTag(LINK_WARNING_OPEN_TEST_TAG).assertCountEquals(0)
    }

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * A message that was just added is in the list at once but on the screen only when the list
     * has scrolled to it, so being displayed is waited for.
     */
    private fun awaitDisplayed(text: String) = try {
        await {
            try {
                compose.onNodeWithText(text).assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("\"$text\" did not come onto the screen:\n${compose.onRoot().printToString()}", error)
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
