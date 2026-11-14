// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.messages

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.session.blockedPerson
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.InMemoryMessageNotificationSettings
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.conversation
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.messageRequest
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.chat.ChatRow
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.ConnectedSheet
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.NotificationAccess
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Messages tab, requests, chat requests and blocking through the view model, against the fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val settings = InMemoryMessageNotificationSettings()
    private val annaChat = ChatId.Person("cert-anna")
    private val benChat = ChatId.Person("cert-ben")

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A view model that is connected to the mockup server; Anna and Ben have certificates. */
    private fun TestScope.connected(
        server: ServerState = mockupServer(),
        configure: (FakeEngine) -> Unit = {},
    ): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(savedServer(1, "Nordlicht")))
                    .also {
                        it.certificates[MOCKUP_ANNA] = "cert-anna"
                        it.certificates[MOCKUP_BEN] = "cert-ben"
                    }
                    .also(configure)
                    .also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            NotificationAccess.Never,
            messageSettings = settings,
        ).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            backgroundScope.launch { viewModel.messagesState.collect() }
            backgroundScope.launch { viewModel.conversationState.collect() }
            backgroundScope.launch { viewModel.chatState.collect() }
            runCurrent()
            viewModel.connect(1)
            runCurrent()
        }
    }

    private fun annaWrites(text: String = "Joining Gaming later?", id: String = "a1") =
        engine.receive(chatMessage(id, annaChat, "Anna", MOCKUP_ANNA, sentAtMs = 1_000, content = messageContent(text)))

    private val AppViewModel.ui get() = messagesState.value!!

    // ----- the list -----

    @Test
    fun theConversationsOfTheServerAreListedOnceConnected() = runTest(dispatcher) {
        val viewModel = connected {
            it.conversations += conversation("Anna", "Joining Gaming later?", peerSession = MOCKUP_ANNA)
            it.conversations += conversation("Nora", "See you tomorrow")
        }

        assertEquals(listOf("Anna", "Nora"), viewModel.ui.rows.map { it.name })
        assertEquals(listOf(true, false), viewModel.ui.rows.map { it.online })
    }

    @Test
    fun aPrivateMessageThatArrivesShowsUpInTheListWithItsUnreadCountAndOnTheTab() = runTest(dispatcher) {
        val viewModel = connected()
        assertTrue(viewModel.ui.rows.isEmpty())

        annaWrites()
        annaWrites("Are you there?", id = "a2")
        runCurrent()

        val row = viewModel.ui.rows.single()
        assertEquals("Anna", row.name)
        assertEquals(ConversationPreview.Text("Are you there?"), row.preview)
        assertEquals(2, row.unread)
        assertEquals(2, viewModel.ui.badge)
    }

    @Test
    fun aChannelMessageNeitherReadsTheListAgainNorCountsOnTheMessagesTab() = runTest(dispatcher) {
        val viewModel = connected()

        engine.receive(chatMessage("c1", ChatId.Channel(MOCKUP_LOBBY), "Anna", MOCKUP_ANNA))
        runCurrent()

        assertTrue(viewModel.ui.rows.isEmpty())
        assertEquals(0, viewModel.ui.badge)
    }

    @Test
    fun theSearchFiltersTheListAndClosingItShowsEverythingAgain() = runTest(dispatcher) {
        val viewModel = connected {
            it.conversations += conversation("Anna", "Hi")
            it.conversations += conversation("Ben", "Hi")
        }

        viewModel.messages.openSearch()
        viewModel.messages.changeQuery("be")
        runCurrent()
        assertEquals(listOf("Ben"), viewModel.ui.rows.map { it.name })

        viewModel.messages.closeSearch()
        runCurrent()
        assertEquals(2, viewModel.ui.rows.size)
        assertEquals("", viewModel.ui.query)
    }

    // ----- a conversation -----

    @Test
    fun openingAConversationShowsItsMessagesUnderThePersonsNameAndMarksThemRead() = runTest(dispatcher) {
        val viewModel = connected()
        annaWrites()
        runCurrent()
        viewModel.connected.selectTab(ConnectedTab.Messages)
        viewModel.conversation.setResumed(true)

        val row = viewModel.ui.rows.single()
        viewModel.messages.openConversation(row.chat, row.name, row.session)
        runCurrent()

        val chat = viewModel.conversationState.value!!
        assertEquals("Anna", chat.title)
        assertEquals(true, chat.peer?.online)
        assertEquals(1, chat.rows.count { it is ChatRow.Message })
        assertEquals(annaChat, engine.openChat)
        assertEquals(listOf(annaChat), engine.markReadCalls.toList())
        assertEquals(0, viewModel.ui.badge)
    }

    @Test
    fun aMessageSentInAConversationBecomesItsPreviewInTheList() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.messages.messagePerson(MOCKUP_BEN)
        runCurrent()

        viewModel.conversation.changeDraft("See you at eight", 16)
        viewModel.conversation.send()
        runCurrent()
        viewModel.messages.back()
        runCurrent()

        val row = viewModel.ui.rows.single()
        assertEquals(benChat, row.chat)
        assertEquals("Ben", row.name)
        assertEquals(ConversationPreview.Text("See you at eight"), row.preview)
    }

    @Test
    fun sendPrivateMessageFromThePersonSheetOpensTheConversationOnTheMessagesTab() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openPerson(MOCKUP_BEN)
        runCurrent()
        assertTrue(viewModel.uiState.value.connected?.sheet is ConnectedSheet.PersonSheet)

        viewModel.messages.messagePerson(MOCKUP_BEN)
        runCurrent()

        assertEquals(ConnectedTab.Messages, viewModel.uiState.value.connected?.tab)
        assertNull("the sheet is put away", viewModel.uiState.value.connected?.sheet)
        assertEquals(OpenConversation(benChat, "Ben", MOCKUP_BEN), viewModel.ui.open)
        assertEquals("Ben", viewModel.conversationState.value?.title)
    }

    @Test
    fun aPersonWithoutCertificateGetsTheChatOfTheirSession() = runTest(dispatcher) {
        val viewModel = connected()

        viewModel.messages.messagePerson(MOCKUP_JONAS)
        runCurrent()

        assertEquals(ChatId.Session(MOCKUP_JONAS), viewModel.ui.open?.chat)
        assertEquals("Jonas", viewModel.conversationState.value?.title)
    }

    @Test
    fun whatWasTypedToOnePersonIsNotCarriedIntoTheConversationWithAnother() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.messages.messagePerson(MOCKUP_BEN)
        runCurrent()
        viewModel.conversation.changeDraft("for Ben only", 12)

        viewModel.messages.back()
        viewModel.messages.messagePerson(MOCKUP_ANNA)
        runCurrent()

        assertEquals("", viewModel.conversationState.value?.draft)
    }

    @Test
    fun theChatTabAndAConversationDoNotTakeEachOthersOpenChat() = runTest(dispatcher) {
        val viewModel = connected()
        val lobby = ChatId.Channel(MOCKUP_LOBBY)
        viewModel.chat.setResumed(true)
        viewModel.conversation.setResumed(true)
        viewModel.connected.selectTab(ConnectedTab.Chat)
        runCurrent()
        assertEquals(lobby, engine.openChat)

        viewModel.messages.messagePerson(MOCKUP_BEN)
        runCurrent()
        assertEquals(benChat, engine.openChat)

        viewModel.messages.back()
        runCurrent()
        assertNull("the conversation list shows no chat", engine.openChat)

        viewModel.connected.selectTab(ConnectedTab.Chat)
        runCurrent()
        assertEquals(lobby, engine.openChat)
    }

    @Test
    fun aTappedNotificationOpensItsConversationOrTheChatTabForAMention() = runTest(dispatcher) {
        val viewModel = connected()
        annaWrites()
        runCurrent()
        viewModel.connected.openVoice()

        viewModel.openChat(annaChat)
        runCurrent()
        assertEquals(ConnectedTab.Messages, viewModel.uiState.value.connected?.tab)
        assertEquals(false, viewModel.uiState.value.connected?.voice?.open)
        assertEquals("Anna", viewModel.ui.open?.name)

        viewModel.openChat(ChatId.Channel(MOCKUP_LOBBY))
        runCurrent()
        assertEquals(ConnectedTab.Chat, viewModel.uiState.value.connected?.tab)
    }

    @Test
    fun aTappedNotificationDoesNothingOnceTheConnectionIsGone() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.disconnect()
        runCurrent()

        viewModel.openChat(annaChat)
        runCurrent()

        assertNull(viewModel.messagesState.value)
        assertNull(viewModel.messages.local.value.open)
    }

    @Test
    fun backClosesTheConversationThenThePageThenTheSearch() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.messages.openSearch()
        viewModel.messages.openPicker()
        viewModel.messages.messagePerson(MOCKUP_BEN)
        runCurrent()
        assertEquals(MessagesPage.List, viewModel.ui.page)

        viewModel.messages.back()
        runCurrent()
        assertNull(viewModel.ui.open)
        assertTrue(viewModel.ui.searching)

        viewModel.messages.openRequests()
        viewModel.messages.back()
        viewModel.messages.back()
        runCurrent()
        assertFalse(viewModel.ui.canGoBack)
    }

    @Test
    fun theEndOfTheConnectionForgetsWhatWasOpenButNotTheSwitch() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.messages.setSenderOnly(true)
        viewModel.messages.messagePerson(MOCKUP_BEN)
        runCurrent()

        viewModel.disconnect()
        runCurrent()

        assertEquals(MessagesLocal(senderOnly = true), viewModel.messages.local.value)
    }

    // ----- message requests -----

    @Test
    fun aRequestIsListedAndAcceptingItTurnsItsMessagesIntoAConversation() = runTest(dispatcher) {
        val viewModel = connected()
        val held = chatMessage("h1", annaChat, "Anna", MOCKUP_ANNA, content = messageContent("Hello?"))
        engine.receiveRequest(messageRequest(7u, "Anna", MOCKUP_ANNA, messages = listOf(held)))
        runCurrent()
        assertEquals(1, viewModel.ui.requests.size)
        assertTrue(viewModel.ui.rows.isEmpty())

        viewModel.messages.openRequests()
        viewModel.messages.acceptRequest(7u)
        runCurrent()

        assertTrue(viewModel.ui.requests.isEmpty())
        assertEquals("the page of requests has nothing left to show", MessagesPage.List, viewModel.ui.page)
        assertEquals(listOf("Anna"), viewModel.ui.rows.map { it.name })
    }

    @Test
    fun decliningARequestRemovesItAndLeavesNoConversation() = runTest(dispatcher) {
        val viewModel = connected()
        engine.receiveRequest(messageRequest(7u, "Kim", note = "We met in Raid"))
        engine.receiveRequest(messageRequest(8u, "Tom", note = "Hi"))
        runCurrent()
        viewModel.messages.openRequests()

        viewModel.messages.declineRequest(7u)
        runCurrent()

        assertEquals(listOf(8uL), viewModel.ui.requests.map { it.id })
        assertEquals(MessagesPage.Requests, viewModel.ui.page)
        assertTrue(viewModel.ui.rows.isEmpty())
    }

    @Test
    fun aRequestHeldBeforeTheListWasLookedAtIsReadWithTheConnection() = runTest(dispatcher) {
        val viewModel = connected { it.incomingRequests += messageRequest(3u, "Kim", note = "Hi") }

        assertEquals(listOf(3uL), viewModel.ui.requests.map { it.id })
    }

    // ----- chat requests -----

    @Test
    fun aChatRequestIsSentWithItsNoteAndThenShownAsPending() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openPerson(MOCKUP_MIRA)
        viewModel.messages.askChatRequest(MOCKUP_MIRA)
        runCurrent()
        assertNull("the person sheet makes room", viewModel.uiState.value.connected?.sheet)
        assertTrue(viewModel.ui.sheet is MessagesSheet.ChatRequest)

        viewModel.messages.changeRequestNote("  We met in Raid ")
        viewModel.messages.sendChatRequest()
        runCurrent()

        assertEquals(listOf(MOCKUP_MIRA to "We met in Raid"), engine.chatRequestCalls.toList())
        assertNull(viewModel.ui.sheet)
        assertEquals(ChatRequestOffer.Pending, viewModel.ui.person(MOCKUP_MIRA).chatRequest)
    }

    @Test
    fun anEmptyNoteIsNotSentAndALongOneIsCut() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.messages.askChatRequest(MOCKUP_MIRA)
        viewModel.messages.changeRequestNote("x".repeat(250))
        assertEquals(CHAT_REQUEST_NOTE_MAX, viewModel.messages.local.value.requestNote.length)

        viewModel.messages.changeRequestNote("   ")
        viewModel.messages.sendChatRequest()
        runCurrent()

        assertEquals(listOf<Pair<UInt, String?>>(MOCKUP_MIRA to null), engine.chatRequestCalls.toList())
    }

    @Test
    fun aRefusedChatRequestSaysThatThePersonsAppCannotTakeItAndAnotherFailureThatItCanBeTriedAgain() =
        runTest(dispatcher) {
            val viewModel = connected { it.chatRequestFailure = BridgeException.InvalidInput("cannot receive") }
            viewModel.messages.askChatRequest(MOCKUP_MIRA)
            viewModel.messages.sendChatRequest()
            runCurrent()

            val refused = viewModel.ui.sheet as MessagesSheet.ChatRequest
            assertEquals(ChatRequestError.Refused, refused.error)
            assertFalse(refused.sending)
            assertEquals(ChatRequestOffer.Offer, viewModel.ui.person(MOCKUP_MIRA).chatRequest)

            engine.chatRequestFailure = BridgeException.NotConnected("gone")
            viewModel.messages.sendChatRequest()
            runCurrent()
            assertEquals(ChatRequestError.Failed, (viewModel.ui.sheet as MessagesSheet.ChatRequest).error)

            viewModel.messages.dismissSheet()
            runCurrent()
            assertNull(viewModel.ui.sheet)
        }

    // ----- blocking -----

    @Test
    fun blockingAsksFirstAndThenThePersonCanBeUnblockedFromTheirSheet() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openPerson(MOCKUP_JONAS)
        viewModel.messages.askBlock(MOCKUP_JONAS)
        runCurrent()
        assertEquals(MessagesSheet.BlockConfirm(MOCKUP_JONAS, "Jonas", failed = false), viewModel.ui.sheet)
        assertTrue("nothing is blocked before the answer", engine.blocked.isEmpty())

        viewModel.messages.confirmBlock()
        runCurrent()

        assertNull(viewModel.ui.sheet)
        assertEquals(listOf("Jonas"), viewModel.ui.blocked.map { it.name })
        assertTrue(viewModel.ui.person(MOCKUP_JONAS).blocked)
        assertFalse(viewModel.ui.person(MOCKUP_ANNA).blocked)

        viewModel.messages.unblockPerson(MOCKUP_JONAS)
        runCurrent()

        assertTrue(engine.blocked.isEmpty())
        assertTrue(viewModel.ui.blocked.isEmpty())
        assertFalse(viewModel.ui.person(MOCKUP_JONAS).blocked)
    }

    /** The mockup server with Jonas blocked on an earlier connection, as the engine reports it. */
    private fun serverWithJonasBlocked(): ServerState = mockupServer().let { server ->
        server.copy(people = server.people.map { if (it.session == MOCKUP_JONAS) it.copy(blocked = true) else it })
    }

    private val earlierBlock = blockedPerson(40, "Jonas", session = MOCKUP_JONAS)

    @Test
    fun aBlockOfAnEarlierConnectionIsLiftedFromThePersonsSheet() = runTest(dispatcher) {
        val viewModel = connected(serverWithJonasBlocked()) { it.blocked += earlierBlock }
        assertTrue("the sheet offers Unblock", viewModel.ui.person(MOCKUP_JONAS).blocked)

        viewModel.messages.unblockPerson(MOCKUP_JONAS)
        runCurrent()

        assertTrue(engine.blocked.isEmpty())
        assertFalse(viewModel.ui.person(MOCKUP_JONAS).blocked)
        assertNull("nothing failed", viewModel.uiState.value.connected?.denied)
    }

    @Test
    fun unblockingAPersonLeavesTheBlocksOfOtherPeopleAlone() = runTest(dispatcher) {
        val viewModel = connected(serverWithJonasBlocked()) {
            it.blocked += earlierBlock
            it.blocked += earlierBlock.copy(id = 41, name = "Nora", session = null)
        }

        viewModel.messages.unblockPerson(MOCKUP_JONAS)
        runCurrent()

        assertEquals(listOf(41L), engine.blocked.map { it.id })
    }

    @Test
    fun aBlockOfAnEarlierConnectionIsListedAndLiftedFromTheBlockedList() = runTest(dispatcher) {
        val viewModel = connected(serverWithJonasBlocked()) { it.blocked += earlierBlock }
        viewModel.messages.openBlocked()
        runCurrent()

        assertTrue(viewModel.ui.blockedOpen)
        assertEquals(listOf(BlockedRow(40, "Jonas", "Nordlicht")), viewModel.ui.blocked)

        viewModel.messages.unblock(40)
        runCurrent()
        assertTrue(viewModel.ui.blocked.isEmpty())
        assertFalse(viewModel.ui.person(MOCKUP_JONAS).blocked)

        viewModel.messages.back()
        runCurrent()
        assertFalse(viewModel.ui.blockedOpen)
    }

    @Test
    fun unblockingInTheListAlsoForgetsTheBlockOnThePersonsSheet() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.messages.askBlock(MOCKUP_JONAS)
        viewModel.messages.confirmBlock()
        runCurrent()
        val id = viewModel.ui.blocked.single().id

        viewModel.messages.unblock(id)
        runCurrent()

        assertFalse(viewModel.ui.person(MOCKUP_JONAS).blocked)
    }

    @Test
    fun aBlockThatCouldNotBeSavedIsSaidOnTheSheet() = runTest(dispatcher) {
        val viewModel = connected { it.blockFailure = BridgeException.Io("disk") }
        viewModel.messages.askBlock(MOCKUP_JONAS)
        viewModel.messages.confirmBlock()
        runCurrent()

        assertEquals(MessagesSheet.BlockConfirm(MOCKUP_JONAS, "Jonas", failed = true), viewModel.ui.sheet)
        assertFalse(viewModel.ui.person(MOCKUP_JONAS).blocked)
    }

    // ----- notifications -----

    @Test
    fun showSenderOnlyIsKeptInTheSettings() = runTest(dispatcher) {
        val viewModel = connected()
        assertFalse(viewModel.ui.senderOnly)

        viewModel.messages.setSenderOnly(true)
        runCurrent()

        assertTrue(settings.senderOnly.value)
        assertTrue(viewModel.ui.senderOnly)
    }
}
