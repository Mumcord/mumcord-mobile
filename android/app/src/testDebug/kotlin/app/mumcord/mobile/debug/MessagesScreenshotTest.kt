// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.blockedPerson
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.conversation
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.messageRequest
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.ui.chat.ChatLocal
import app.mumcord.mobile.ui.chat.conversationUiState
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedSheetHost
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.messages.ChatRequestError
import app.mumcord.mobile.ui.messages.MessagesLocal
import app.mumcord.mobile.ui.messages.MessagesPage
import app.mumcord.mobile.ui.messages.MessagesSheetHost
import app.mumcord.mobile.ui.messages.MessagesUiState
import app.mumcord.mobile.ui.messages.OpenConversation
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
 * Draws the Messages tab and what belongs to it in both themes at the size of the mockups,
 * with the conversations of mockup `09-messages`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class MessagesScreenshotTest {
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
    private val messages = ScreenshotMessages(repository, actions, scope)
    private val more = ScreenshotMore(repository, actions, scope)

    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    private fun at(hour: Int, minute: Int, daysAgo: Long = 0): Long =
        MOCKUP_DAY.minusDays(daysAgo).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val anna = conversation("Anna", "Joining Gaming later?", at(18, 51), MOCKUP_ANNA)
    private val ben = conversation("Ben", "Thanks, got the map!", at(17, 20), MOCKUP_BEN)

    // As in the mockup, Mira and Jonas are not on the server when the list is read.
    private val mira = conversation("Mira", "Sent you the screenshot.", at(14, 3))
    private val jonas = conversation("Jonas", "See you tomorrow", at(21, 15, daysAgo = 1))
    private val conversations = listOf(anna, ben, mira, jonas)
    private val unread = mapOf(anna.chat to 2, mira.chat to 1)

    private val requests = listOf(
        messageRequest(1u, "Kim", note = "Hi, we met in the Raid channel yesterday."),
        messageRequest(
            2u,
            "Tom",
            messages = listOf(
                chatMessage("h1", ChatId.Session(91u), "Tom", content = messageContent("Is the server down tonight?")),
                chatMessage("h2", ChatId.Session(91u), "Tom", content = messageContent("Never mind.")),
            ),
        ),
    )

    @After
    fun tearDown() = scope.cancel()

    private fun capture(
        name: String,
        dark: Boolean,
        tab: ConnectedTab = ConnectedTab.Messages,
        connected: ConnectedLocal = ConnectedLocal(tab = tab),
        chat: ChatLocal? = null,
        state: () -> MessagesUiState,
    ) = composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
        val shell = checkNotNull(connectedUiState(session, connected, "Nordlicht"))
        val messagesState = state()
        val conversation = chat?.let {
            conversationUiState(
                session,
                it,
                messagesState.open?.chat,
                messagesState.open?.name.orEmpty(),
                messagesState.openOnline,
                "Nordlicht",
                ZoneOffset.UTC,
            )
        }
        MumcordTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize()) {
                ConnectedShell(
                    shell,
                    actions,
                    onDisconnect = {},
                    onMicrophone = {},
                    chat = null,
                    chatBadge = 0,
                    chatActions = chatActions,
                    chatEnvironment = screenshotChatEnvironment(),
                    messages = messagesState,
                    messagesActions = messages.actions,
                    conversation = conversation,
                    conversationActions = messages.conversationActions,
                    more = more.state(session),
                    moreActions = more.actions,
                    onNotificationSettings = {},
                    onIdentities = {},
                    topInsets = top,
                    bottomInsets = bottom,
                )
                shell.sheet?.let {
                    ConnectedSheetHost(
                        it,
                        actions,
                        top,
                        bottom,
                        onMicrophoneContinue = {},
                        onMicrophoneNotNow = {},
                        messages = messagesState,
                        messagesActions = messages.actions,
                    )
                }
                messagesState.sheet?.let { MessagesSheetHost(it, messages.actions, top, bottom) }
            }
        }
    }

    private fun list(local: MessagesLocal = MessagesLocal(), granted: Boolean = true) =
        messages.state(session, local, conversations, requests.take(1), unread = unread, notificationsGranted = granted)

    private fun messages(dark: Boolean) = capture("android-messages", dark) { list() }

    // No mockup exists for the states below; they are built from the design system.
    private fun empty(dark: Boolean) = capture("android-messages-empty", dark) { messages.state(session) }

    private fun notificationsOff(dark: Boolean) =
        capture("android-messages-notifications-off", dark) { list(granted = false) }

    private fun search(dark: Boolean) =
        capture("android-messages-search", dark) { list(MessagesLocal(searching = true, query = "an")) }

    private fun requests(dark: Boolean) = capture("android-message-requests", dark) {
        messages.state(session, MessagesLocal(page = MessagesPage.Requests), conversations, requests, unread = unread)
    }

    private fun picker(dark: Boolean) =
        capture("android-compose-picker", dark) { list(MessagesLocal(page = MessagesPage.Picker)) }

    private fun conversation(dark: Boolean) = capture(
        "android-conversation",
        dark,
        chat = ChatLocal(
            chat = anna.chat,
            messages = listOf(
                chatMessage("p1", anna.chat, "Lea", own = true, sentAtMs = at(18, 40), content = messageContent("Are you on tonight?")),
                chatMessage("p2", anna.chat, "Anna", MOCKUP_ANNA, sentAtMs = at(18, 50), content = messageContent("Yes, after dinner.")),
                chatMessage("p3", anna.chat, "Anna", MOCKUP_ANNA, sentAtMs = at(18, 51), content = messageContent("Joining Gaming later?")),
            ),
        ),
    ) { list(MessagesLocal(open = OpenConversation(anna.chat, "Anna", MOCKUP_ANNA))) }

    private fun personSheet(dark: Boolean) = capture(
        "android-person-sheet-message",
        dark,
        connected = ConnectedLocal(personSession = MOCKUP_JONAS),
    ) { list() }

    private fun blockConfirm(dark: Boolean) = capture("android-block-confirm", dark, tab = ConnectedTab.Channels) {
        list(MessagesLocal(blockSession = MOCKUP_JONAS))
    }

    private fun chatRequest(dark: Boolean) = capture("android-chat-request", dark, tab = ConnectedTab.Channels) {
        list(
            MessagesLocal(
                requestSession = MOCKUP_JONAS,
                requestNote = "Hi Jonas, Lea from the Lobby.",
                requestError = ChatRequestError.Refused,
            ),
        )
    }

    private fun blocked(dark: Boolean) = capture("android-blocked-people", dark, tab = ConnectedTab.More) {
        messages.state(
            session,
            MessagesLocal(blockedOpen = true),
            blocked = listOf(blockedPerson(1, "Troll42"), blockedPerson(2, "Spammer")),
        )
    }

    @Test fun messagesDark() = messages(dark = true)

    @Test fun messagesLight() = messages(dark = false)

    @Test fun emptyDark() = empty(dark = true)

    @Test fun emptyLight() = empty(dark = false)

    @Test fun notificationsOffDark() = notificationsOff(dark = true)

    @Test fun notificationsOffLight() = notificationsOff(dark = false)

    @Test fun searchDark() = search(dark = true)

    @Test fun searchLight() = search(dark = false)

    @Test fun requestsDark() = requests(dark = true)

    @Test fun requestsLight() = requests(dark = false)

    @Test fun pickerDark() = picker(dark = true)

    @Test fun pickerLight() = picker(dark = false)

    @Test fun conversationDark() = conversation(dark = true)

    @Test fun conversationLight() = conversation(dark = false)

    @Test fun personSheetDark() = personSheet(dark = true)

    @Test fun personSheetLight() = personSheet(dark = false)

    @Test fun blockConfirmDark() = blockConfirm(dark = true)

    @Test fun blockConfirmLight() = blockConfirm(dark = false)

    @Test fun chatRequestDark() = chatRequest(dark = true)

    @Test fun chatRequestLight() = chatRequest(dark = false)

    @Test fun blockedDark() = blocked(dark = true)

    @Test fun blockedLight() = blocked(dark = false)
}
