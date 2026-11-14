// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.InMemoryMessageNotificationSettings
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.messageRequest
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.chat.CHAT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_SEND_TEST_TAG
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_BLOCKED_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_SENDER_ONLY_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.NotificationAccess
import app.mumcord.mobile.ui.connected.NotificationSystem
import app.mumcord.mobile.ui.connected.PERSON_BLOCK_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_CHAT_REQUEST_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_MESSAGE_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.messages.BLOCKED_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.messages.BLOCKED_UNBLOCK_TEST_TAG
import app.mumcord.mobile.ui.messages.BLOCK_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.messages.CHAT_REQUEST_SEND_TEST_TAG
import app.mumcord.mobile.ui.messages.CONVERSATION_ROW_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_EMPTY_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_NEW_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_NOTIFICATIONS_OFF_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_REQUESTS_ROW_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.messages.PICKER_PERSON_TEST_TAG
import app.mumcord.mobile.ui.messages.PICKER_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.messages.REQUESTS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.messages.REQUEST_ACCEPT_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Messages tab, message requests, blocking and the notification switch on a device against
 * a fake engine, on the server of mockup `02-channels`: the user is Lea; Anna and Ben have
 * certificates, so their conversations are stored.
 */
class MessagesUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resizeForTheKeyboard() = compose.resizeForTheKeyboardLikeTheApp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val settings = InMemoryMessageNotificationSettings()
    private val annaChat = ChatId.Person("cert-anna")
    private var settingsOpened = 0

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    /** A phone that does not let the app show notifications. */
    private object NotificationsRefused : NotificationAccess {
        override val explain = MutableStateFlow(false)
        override val granted = MutableStateFlow(false)

        override fun notNow() = Unit

        override fun answer(granted: Boolean) = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Starts the app and connects to Nordlicht; the Channels tab is shown. */
    private fun connect(
        notifications: NotificationAccess = NotificationAccess.Never,
        configure: (FakeEngine) -> Unit = {},
    ): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht")))
                    .also {
                        it.certificates[MOCKUP_ANNA] = "cert-anna"
                        it.certificates[MOCKUP_BEN] = "cert-ben"
                    }
                    .also(configure)
                    .also { engine = it }
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
            notifications,
            messageSettings = settings,
        )
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                    NotificationSystem(ask = {}, openSettings = { settingsOpened++ }),
                    chatImages = ChatImageSystem(pickPhoto = {}, takePhoto = {}),
                    chatImageLoader = ChatImageLoader { _, _, _ -> null },
                )
            }
        }
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await("the connected screens") { shown(VOICE_BAR_TEST_TAG) }
        return viewModel
    }

    private fun annaWrites(id: String, text: String) =
        engine.receive(chatMessage(id, annaChat, "Anna", MOCKUP_ANNA, sentAtMs = 1_000, content = messageContent(text)))

    private fun openMessagesTab() {
        // With unread messages the tab is named "Messages, 2 unread messages" for screen readers.
        compose.onNode(hasText("Messages") or hasContentDescription("Messages, ", substring = true)).performClick()
        await("the Messages tab") { shown(MESSAGES_SCREEN_TEST_TAG) }
    }

    @Test
    fun aPrivateMessageShowsOnTheTabAndInTheListAndTheConversationCanBeAnswered() {
        connect()
        annaWrites("a1", "Joining Gaming later?")
        annaWrites("a2", "We start at eight.")

        await("the count on the Messages tab") { described("Messages, 2 unread messages") }
        openMessagesTab()
        await("the conversation with its newest message") {
            described("Anna, Online, 2 unread messages, We start at eight.", substring = true)
        }

        compose.onNodeWithTag(CONVERSATION_ROW_TEST_TAG).performClick()
        await("the conversation") { shown(CHAT_SCREEN_TEST_TAG) }
        await("both messages") { exists("Joining Gaming later?") && exists("We start at eight.") }
        await("the messages to count as read") { engine.unread[annaChat] == null }

        compose.onNode(hasSetTextAction()).performTextInput("On my way")
        compose.onNodeWithTag(CHAT_SEND_TEST_TAG).performClick()
        await("the answer to be sent") { engine.messages[annaChat].orEmpty().any { it.own } }

        compose.onNodeWithContentDescription("Back to the conversations").performClick()
        await("the list with the answer as the newest message") {
            shown(MESSAGES_SCREEN_TEST_TAG) && described("On my way", substring = true)
        }
    }

    @Test
    fun withoutConversationsTheTabSaysSoAndTheButtonStartsOneWithAPersonFoundBySearching() {
        connect()
        openMessagesTab()
        compose.onNodeWithTag(MESSAGES_EMPTY_TEST_TAG).assertIsDisplayed()

        compose.onNodeWithTag(MESSAGES_NEW_TEST_TAG).performClick()
        await("the people to write to") { shown(PICKER_SCREEN_TEST_TAG) }
        assertEquals(4, compose.onAllNodesWithTag(PICKER_PERSON_TEST_TAG).fetchSemanticsNodes().size)

        compose.onNode(hasSetTextAction()).performTextInput("mi")
        await("the search to leave one person") {
            compose.onAllNodesWithTag(PICKER_PERSON_TEST_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(PICKER_PERSON_TEST_TAG).performClick()

        await("the conversation with Mira") { shown(CHAT_SCREEN_TEST_TAG) && exists("Mira") }
        assertEquals(ChatId.Session(MOCKUP_MIRA), engine.openChat)
    }

    @Test
    fun aMessageRequestIsShownAndAcceptingItMakesItAConversation() {
        connect()
        val held = chatMessage("h1", annaChat, "Anna", MOCKUP_ANNA, content = messageContent("Hello, are you Lea?"))
        engine.receiveRequest(messageRequest(7u, "Anna", MOCKUP_ANNA, messages = listOf(held)))
        openMessagesTab()

        await("the row of the requests") { exists("Message requests (1)") }
        compose.onNodeWithTag(MESSAGES_REQUESTS_ROW_TEST_TAG).performClick()
        await("the request") { shown(REQUESTS_SCREEN_TEST_TAG) && exists("Hello, are you Lea?") }

        compose.onNodeWithTag(REQUEST_ACCEPT_TEST_TAG).performClick()

        await("the conversation in the list") { shown(CONVERSATION_ROW_TEST_TAG) }
        assertTrue(engine.incomingRequests.isEmpty())
        assertTrue(compose.onAllNodesWithTag(MESSAGES_REQUESTS_ROW_TEST_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun sendPrivateMessageOnThePersonSheetOpensTheConversation() {
        connect()
        compose.onNodeWithText("Ben").performClick()
        await("the person sheet") { shown(PERSON_MESSAGE_TEST_TAG) }

        compose.onNodeWithTag(PERSON_MESSAGE_TEST_TAG).performClick()

        await("the conversation with Ben") { shown(CHAT_SCREEN_TEST_TAG) && exists("Online · Nordlicht") }
        assertEquals(ChatId.Person("cert-ben"), engine.openChat)
    }

    @Test
    fun aPersonIsBlockedAfterAQuestionAndUnblockedInTheListUnderMore() {
        connect()
        compose.onNodeWithText("Jonas").performClick()
        await("the person sheet") { shown(PERSON_BLOCK_TEST_TAG) }
        compose.onNodeWithTag(PERSON_BLOCK_TEST_TAG).performClick()
        await("the question") { shown(BLOCK_CONFIRM_TEST_TAG) }
        assertTrue("nothing is blocked before the answer", engine.blocked.isEmpty())

        compose.onNodeWithTag(BLOCK_CONFIRM_TEST_TAG).performClick()
        await("the block") { engine.blocked.size == 1 && !shown(BLOCK_CONFIRM_TEST_TAG) }

        // The person's sheet now offers to lift the block.
        compose.onNodeWithText("Jonas").performClick()
        await("Unblock on the person sheet") { exists("Unblock") }
        compose.onNodeWithContentDescription("Mute for me").assertExists()
        Espresso.pressBack()
        await("the sheet to close") { !shown(PERSON_BLOCK_TEST_TAG) }

        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag(MORE_BLOCKED_TEST_TAG).performClick()
        await("the blocked people") { shown(BLOCKED_SCREEN_TEST_TAG) && exists("Jonas") }
        compose.onNodeWithTag(BLOCKED_UNBLOCK_TEST_TAG).performClick()

        await("the block to be lifted") { engine.blocked.isEmpty() && exists("You have not blocked anybody.") }
    }

    @Test
    fun aChatRequestIsSentWithANoteAndThenShownAsPending() {
        connect()
        compose.onNodeWithText("Mira").performClick()
        await("the person sheet") { shown(PERSON_CHAT_REQUEST_TEST_TAG) }
        compose.onNodeWithTag(PERSON_CHAT_REQUEST_TEST_TAG).performClick()
        await("the chat request") { shown(CHAT_REQUEST_SEND_TEST_TAG) }

        compose.onNode(hasSetTextAction()).performTextInput("We met in Raid")
        compose.onNodeWithTag(CHAT_REQUEST_SEND_TEST_TAG).performClick()
        await("the request to be sent") { engine.chatRequestCalls.toList() == listOf(MOCKUP_MIRA to "We met in Raid") }
        await("the sheet to close") { !shown(CHAT_REQUEST_SEND_TEST_TAG) }

        compose.onNodeWithText("Mira").performClick()
        await("the request as pending") { exists("Chat request pending") }
    }

    @Test
    fun aPhoneThatShowsNoNotificationsIsNamedOnTheMessagesTabWithTheWayToTheSettings() {
        connect(NotificationsRefused)
        openMessagesTab()

        compose.onNodeWithText("Notifications are off", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(MESSAGES_NOTIFICATIONS_OFF_TEST_TAG).performClick()

        assertEquals(1, settingsOpened)
    }

    @Test
    fun showSenderOnlyIsSwitchedOnUnderMore() {
        connect()
        compose.onNodeWithText("More").performClick()

        compose.onNodeWithTag(MORE_SENDER_ONLY_TEST_TAG).performClick()

        await("the switch to be kept") { settings.senderOnly }
        // Blocked people leave no dead end: back returns to More.
        compose.onNodeWithTag(MORE_BLOCKED_TEST_TAG).performClick()
        await("the blocked people") { shown(BLOCKED_SCREEN_TEST_TAG) }
        Espresso.pressBack()
        await("More again") { shown(MORE_SENDER_ONLY_TEST_TAG) }
    }

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String, substring: Boolean = false): Boolean = compose
        .onAllNodes(hasContentDescription(text, substring = substring))
        .fetchSemanticsNodes()
        .isNotEmpty()

    /** Waits for [condition]; a wait that runs out says what it waited for and what the screen showed. */
    private fun await(what: String, condition: () -> Boolean) = try {
        compose.waitUntil(TIMEOUT_MILLIS, condition)
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("Waited in vain for $what. The screen:\n${compose.onRoot().printToString()}", error)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
