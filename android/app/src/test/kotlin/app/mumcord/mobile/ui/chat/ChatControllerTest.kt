// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.chat

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
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

/** The Chat tab's actions through the view model, against the fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val lobby = ChatId.Channel(MOCKUP_LOBBY)
    private val gaming = ChatId.Channel(MOCKUP_GAMING)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun history(chat: ChatId, count: Int): List<ChatMessage> =
        (0 until count).map { chatMessage("m$it", chat, "Anna", MOCKUP_ANNA, sentAtMs = it * 1000L) }

    /** A view model that is already connected to the mockup server; the user is Lea in the Lobby. */
    private fun TestScope.connected(configure: (FakeEngine) -> Unit = {}): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht")))
                    .also(configure)
                    .also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess()).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            backgroundScope.launch { viewModel.chatState.collect() }
            backgroundScope.launch { viewModel.chatBadge.collect() }
            runCurrent()
            viewModel.connect(1)
            runCurrent()
        }
    }

    /** The Chat tab is selected and the app is in front. */
    private fun TestScope.openChatTab(viewModel: AppViewModel) {
        viewModel.connected.selectTab(ConnectedTab.Chat)
        viewModel.chat.setResumed(true)
        runCurrent()
    }

    private val AppViewModel.chatUi get() = chatState.value!!
    private val AppViewModel.chatLocal get() = chat.local.value

    private fun incoming(id: String, chat: ChatId = lobby) = chatMessage(id, chat, "Anna", MOCKUP_ANNA, sentAtMs = 1_000_000)

    // ----- history -----

    @Test
    fun connectingShowsTheNewestPageOfTheCurrentChannelsChat() = runTest(dispatcher) {
        val viewModel = connected { it.messages[lobby] = history(lobby, 120).toMutableList() }
        assertEquals(lobby, viewModel.chatUi.chat)
        assertEquals("Lobby", viewModel.chatUi.title)
        assertEquals(50, viewModel.chatLocal.messages.size)
        assertEquals("m119", viewModel.chatLocal.messages.last().id)
        assertTrue(viewModel.chatUi.hasMore)
        assertFalse(viewModel.chatUi.loading)
    }

    @Test
    fun readingBackLoadsOlderPagesUntilTheHistoryEnds() = runTest(dispatcher) {
        val viewModel = connected { it.messages[lobby] = history(lobby, 120).toMutableList() }
        val firstJump = viewModel.chatLocal.scrollToken

        viewModel.chat.loadOlder()
        assertTrue(viewModel.chatLocal.loadingOlder)
        viewModel.chat.loadOlder()
        runCurrent()
        assertEquals("asking twice reads the page once", 100, viewModel.chatLocal.messages.size)
        assertEquals("m20", viewModel.chatLocal.messages.first().id)
        assertFalse(viewModel.chatUi.historyStart)

        viewModel.chat.loadOlder()
        runCurrent()
        assertEquals((0 until 120).map { "m$it" }, viewModel.chatLocal.messages.map { it.id })
        assertFalse(viewModel.chatUi.hasMore)
        assertTrue(viewModel.chatUi.historyStart)
        assertEquals("the list never jumps while reading back", firstJump, viewModel.chatLocal.scrollToken)

        viewModel.chat.loadOlder()
        runCurrent()
        assertEquals(120, viewModel.chatLocal.messages.size)
    }

    @Test
    fun joiningAnotherChannelSwitchesTheChatAndKeepsWhatWasTyped() = runTest(dispatcher) {
        val viewModel = connected {
            it.messages[lobby] = history(lobby, 3).toMutableList()
            it.messages[gaming] = mutableListOf(chatMessage("g1", gaming, "Mira"))
        }
        openChatTab(viewModel)
        viewModel.chat.changeDraft("half a sentence", 15)
        viewModel.chat.startReply(viewModel.chatLocal.messages.first())

        viewModel.connected.join(MOCKUP_GAMING)
        runCurrent()

        assertEquals(gaming, viewModel.chatUi.chat)
        assertEquals("Gaming", viewModel.chatUi.title)
        assertEquals(listOf("g1"), viewModel.chatLocal.messages.map { it.id })
        assertEquals("half a sentence", viewModel.chatUi.draft)
        assertNull("a reply belongs to the chat it was started in", viewModel.chatUi.reply)
        assertEquals(gaming, engine.openChat)
    }

    @Test
    fun aChatThatCannotBeReadSaysSoAndCanBeTriedAgain() = runTest(dispatcher) {
        val viewModel = connected()
        engine.messages[lobby] = history(lobby, 2).toMutableList()
        engine.pageFailure = BridgeException.Io("disk")
        viewModel.connected.join(MOCKUP_GAMING)
        runCurrent()
        assertTrue(viewModel.chatUi.loadFailed)

        engine.pageFailure = null
        viewModel.chat.reload()
        runCurrent()
        assertFalse(viewModel.chatUi.loadFailed)
        assertTrue(viewModel.chatUi.empty)
    }

    // ----- open chat, unread, the badge -----

    @Test
    fun theEngineKnowsTheChatOnlyWhileItsTabIsOnScreenAndTheAppInFront() = runTest(dispatcher) {
        val viewModel = connected()
        assertNull(engine.openChat)

        viewModel.connected.selectTab(ConnectedTab.Chat)
        runCurrent()
        assertNull("not while the app is in the background", engine.openChat)

        viewModel.chat.setResumed(true)
        runCurrent()
        assertEquals(lobby, engine.openChat)

        viewModel.connected.openVoice()
        runCurrent()
        assertNull("the full voice view covers the chat", engine.openChat)
        viewModel.connected.closeVoice()
        runCurrent()
        assertEquals(lobby, engine.openChat)

        viewModel.chat.setResumed(false)
        runCurrent()
        assertNull(engine.openChat)

        viewModel.chat.setResumed(true)
        viewModel.connected.selectTab(ConnectedTab.Channels)
        runCurrent()
        assertNull(engine.openChat)
    }

    @Test
    fun unreadMessagesOfTheCurrentChannelShowOnTheChatTabUntilItIsOpened() = runTest(dispatcher) {
        val viewModel = connected()
        engine.receive(incoming("a"))
        engine.receive(incoming("b"))
        engine.receive(incoming("x", gaming))
        runCurrent()
        assertEquals(2, viewModel.chatBadge.value)
        assertTrue(engine.markReadCalls.isEmpty())

        openChatTab(viewModel)
        assertEquals(0, viewModel.chatBadge.value)
        assertEquals("seen messages are marked read", listOf<ChatId>(lobby), engine.markReadCalls.toList())
        assertEquals(mapOf(gaming to 1u), engine.unread.toMap())
    }

    @Test
    fun unreadCountsFromBeforeTheConnectionAreShown() = runTest(dispatcher) {
        val viewModel = connected { it.unread[lobby] = 5u }
        assertEquals(5, viewModel.chatBadge.value)
    }

    @Test
    fun aMessageArrivingAtTheBottomIsFollowedAndRead() = runTest(dispatcher) {
        val viewModel = connected()
        openChatTab(viewModel)
        val before = viewModel.chatUi.scrollToken

        engine.receive(incoming("a"))
        runCurrent()

        assertEquals(listOf("a"), viewModel.chatLocal.messages.map { it.id })
        assertEquals(before + 1, viewModel.chatUi.scrollToken)
        assertEquals(0, viewModel.chatUi.newMessages)
        assertTrue(engine.unread.isEmpty())
    }

    @Test
    fun aMessageArrivingWhileReadingFurtherUpShowsThePillAndStaysUnread() = runTest(dispatcher) {
        val viewModel = connected { it.messages[lobby] = history(lobby, 30).toMutableList() }
        openChatTab(viewModel)
        viewModel.chat.setAtBottom(false)
        val before = viewModel.chatUi.scrollToken

        engine.receive(incoming("a"))
        runCurrent()
        assertEquals(1, viewModel.chatUi.newMessages)
        assertEquals(before, viewModel.chatUi.scrollToken)
        assertEquals(mapOf(lobby to 1u), engine.unread.toMap())

        viewModel.chat.scrollToNewest()
        runCurrent()
        assertEquals(0, viewModel.chatUi.newMessages)
        assertEquals(before + 1, viewModel.chatUi.scrollToken)
        // The list reports that it arrived at the newest message.
        viewModel.chat.setAtBottom(true)
        runCurrent()
        assertTrue(engine.unread.isEmpty())
    }

    @Test
    fun messagesArrivingWhileTheAppIsInTheBackgroundStayUnread() = runTest(dispatcher) {
        val viewModel = connected()
        openChatTab(viewModel)
        viewModel.chat.setResumed(false)
        runCurrent()

        engine.receive(incoming("a"))
        runCurrent()
        assertEquals(mapOf(lobby to 1u), engine.unread.toMap())
        assertEquals(listOf("a"), viewModel.chatLocal.messages.map { it.id })

        viewModel.chat.setResumed(true)
        runCurrent()
        assertTrue(engine.unread.isEmpty())
    }

    // ----- writing -----

    @Test
    fun typingAnAtSignSuggestsPeopleAndPickingOneCompletesTheName() = runTest(dispatcher) {
        val viewModel = connected {
            it.mentionNames += listOf(MentionCandidate(MOCKUP_ANNA, "Anna"), MentionCandidate(2u, "Ben"), MentionCandidate(null, "channel"))
        }
        viewModel.chat.changeDraft("Hi @", 4)
        runCurrent()
        assertEquals(listOf("Anna", "Ben", "channel"), viewModel.chatUi.mentions.map { it.name })

        viewModel.chat.changeDraft("Hi @an", 6)
        runCurrent()
        assertEquals(listOf("Anna", "channel"), viewModel.chatUi.mentions.map { it.name })

        val revision = viewModel.chatUi.draftRevision
        viewModel.chat.pickMention(viewModel.chatUi.mentions.first())
        runCurrent()
        assertEquals("Hi @Anna ", viewModel.chatUi.draft)
        assertEquals(9, viewModel.chatUi.cursor)
        assertEquals("the field takes the completed text over", revision + 1, viewModel.chatUi.draftRevision)
        assertTrue(viewModel.chatUi.mentions.isEmpty())

        viewModel.chat.changeDraft("Hi @Anna x", 10)
        runCurrent()
        assertTrue("no list outside a mention", viewModel.chatUi.mentions.isEmpty())
    }

    @Test
    fun sendingClearsTheFieldAndAppendsTheOwnMessage() = runTest(dispatcher) {
        val viewModel = connected()
        openChatTab(viewModel)
        viewModel.chat.changeDraft("  Hello there  ", 15)
        runCurrent()
        assertTrue(viewModel.chatUi.canSend)
        val before = viewModel.chatUi.scrollToken

        viewModel.chat.send()
        assertEquals("the field is empty at once", "", viewModel.chatLocal.draft)
        assertEquals(1, viewModel.chatLocal.pending.size)
        runCurrent()

        assertTrue(viewModel.chatLocal.pending.isEmpty())
        val sent = viewModel.chatLocal.messages.single()
        assertTrue(sent.own)
        assertEquals("Hello there", sent.content.plainText())
        assertTrue(viewModel.chatUi.scrollToken > before)
    }

    @Test
    fun nothingIsSentWhileTheFieldIsEmptyOrTheTextTooLong() = runTest(dispatcher) {
        val viewModel = connected { it.limits = ChatLimits(text = 10u, image = 0u, allowHtml = true) }
        viewModel.chat.send()
        viewModel.chat.changeDraft("   ", 3)
        viewModel.chat.send()
        viewModel.chat.changeDraft("12345678901", 11)
        runCurrent()
        assertEquals(-1, viewModel.chatUi.charactersLeft)
        assertFalse(viewModel.chatUi.canSend)
        viewModel.chat.send()
        runCurrent()
        assertTrue(engine.messages[lobby].isNullOrEmpty())
        assertEquals("the text stays in the field", "12345678901", viewModel.chatUi.draft)
    }

    @Test
    fun aReplyCarriesTheQuoteOfTheMessageAndCanBeCancelled() = runTest(dispatcher) {
        val original = incoming("a").copy(quote = ReplyQuote("Anna", "can you host?"))
        val viewModel = connected { it.messages[lobby] = mutableListOf(original) }

        viewModel.chat.startReply(original)
        runCurrent()
        assertEquals(ReplyTarget("a", ReplyQuote("Anna", "can you host?")), viewModel.chatUi.reply)
        viewModel.chat.cancelReply()
        runCurrent()
        assertNull(viewModel.chatUi.reply)

        viewModel.chat.startReply(original)
        viewModel.chat.changeDraft("Sure", 4)
        viewModel.chat.send()
        runCurrent()
        assertNull(viewModel.chatUi.reply)
        assertEquals(ReplyQuote("Anna", "can you host?"), viewModel.chatLocal.messages.last().content.reply)
    }

    @Test
    fun aMessageThatDidNotGetOutKeepsItsTextAndCanBeRetried() = runTest(dispatcher) {
        val viewModel = connected()
        engine.sendFailure = BridgeException.NotConnected("gone")
        viewModel.chat.changeDraft("Important", 9)
        viewModel.chat.send()
        runCurrent()

        val failed = viewModel.chatLocal.pending.single()
        assertEquals(SendFailure.NotSent, failed.failure)
        assertEquals(PendingContent.Text("Important", null), failed.content)
        assertTrue(viewModel.chatUi.rows.any { it is ChatRow.Pending })

        viewModel.chat.retry(failed.id)
        runCurrent()
        assertEquals("still failing: still there", SendFailure.NotSent, viewModel.chatLocal.pending.single().failure)

        engine.sendFailure = null
        viewModel.chat.retry(failed.id)
        runCurrent()
        assertTrue(viewModel.chatLocal.pending.isEmpty())
        assertEquals("Important", viewModel.chatLocal.messages.single().content.plainText())
    }

    @Test
    fun aFailedMessageCanBeDiscarded() = runTest(dispatcher) {
        val viewModel = connected()
        engine.sendFailure = BridgeException.Io("x")
        viewModel.chat.changeDraft("Never mind", 10)
        viewModel.chat.send()
        runCurrent()
        viewModel.chat.discard(viewModel.chatLocal.pending.single().id)
        runCurrent()
        assertTrue(viewModel.chatLocal.pending.isEmpty())
        assertTrue(viewModel.chatUi.empty)
    }

    @Test
    fun aPictureIsSentAndARefusedOneSaysSoPlainly() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.chat.openImageSources()
        runCurrent()
        assertEquals(ChatSheet.ImageSource, viewModel.chatUi.sheet)

        viewModel.chat.sendImage(byteArrayOf(1, 2, 3), "image/png")
        runCurrent()
        assertNull(viewModel.chatUi.sheet)
        assertTrue(viewModel.chatLocal.messages.single().own)

        engine.sendFailure = BridgeException.InvalidInput("too large")
        viewModel.chat.sendImage(ByteArray(10), "image/jpeg")
        runCurrent()
        assertEquals("the size rules refuse it; trying again cannot work", SendFailure.Refused, viewModel.chatLocal.pending.single().failure)

        viewModel.chat.imageUnreadable()
        runCurrent()
        assertEquals(ChatNotice.ImageUnreadable, viewModel.chatUi.notice)
        viewModel.chat.dismissNotice()
        runCurrent()
        assertNull(viewModel.chatUi.notice)
    }

    // ----- GIF favourites -----

    @Test
    fun gifFavouritesAreListedSentRemovedAndAdded() = runTest(dispatcher) {
        val viewModel = connected {
            it.gifFavourites += GifFavourite("https://gifs.example/a.gif", 0, 0)
            it.gifFavourites += GifFavourite("https://gifs.example/b.gif", 0, 0)
        }
        viewModel.chat.openGifs()
        runCurrent()
        assertEquals(
            listOf("https://gifs.example/a.gif", "https://gifs.example/b.gif"),
            (viewModel.chatUi.sheet as ChatSheet.Gifs).favourites.map { it.url },
        )

        viewModel.chat.removeGif("https://gifs.example/a.gif")
        runCurrent()
        assertEquals(listOf("https://gifs.example/b.gif"), (viewModel.chatUi.sheet as ChatSheet.Gifs).favourites.map { it.url })
        assertEquals(listOf("https://gifs.example/b.gif"), engine.gifFavourites.map { it.url })

        viewModel.chat.sendGif("https://gifs.example/b.gif")
        runCurrent()
        assertNull("the sheet closes when a GIF is picked", viewModel.chatUi.sheet)
        assertEquals("https://gifs.example/b.gif", viewModel.chatLocal.messages.single().content.plainText())

        viewModel.chat.addGif("https://gifs.example/c.gif")
        runCurrent()
        assertEquals(ChatNotice.GifSaved, viewModel.chatUi.notice)
        assertTrue(engine.gifFavourites.any { it.url == "https://gifs.example/c.gif" })
    }

    // ----- links and pictures -----

    @Test
    fun anOrdinaryLinkGoesStraightToTheBrowser() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.chat.tapLink(SafeLink("https://nordlicht.example/rules", "nordlicht.example", LinkKind.WEB, misleading = false))
        runCurrent()
        assertEquals("https://nordlicht.example/rules", viewModel.chatUi.urlToOpen)
        assertNull(viewModel.chatUi.sheet)
        viewModel.chat.urlOpened()
        runCurrent()
        assertNull(viewModel.chatUi.urlToOpen)
    }

    @Test
    fun aMisleadingLinkShowsItsRealTargetAndOpensOnlyAfterConfirming() = runTest(dispatcher) {
        val viewModel = connected()
        val link = SafeLink("https://bank.example.evil.test/login", "bank.example.evil.test", LinkKind.WEB, misleading = true)

        viewModel.chat.tapLink(link)
        runCurrent()
        assertEquals(ChatSheet.LinkWarning(link), viewModel.chatUi.sheet)
        assertNull("nothing is opened before the user decides", viewModel.chatUi.urlToOpen)

        viewModel.chat.dismissSheet()
        runCurrent()
        assertNull(viewModel.chatUi.sheet)
        assertNull(viewModel.chatUi.urlToOpen)

        viewModel.chat.tapLink(link)
        viewModel.chat.confirmLink()
        runCurrent()
        assertNull(viewModel.chatUi.sheet)
        assertEquals("https://bank.example.evil.test/login", viewModel.chatUi.urlToOpen)
    }

    @Test
    fun aPictureOpensInTheViewerAndCloses() = runTest(dispatcher) {
        val viewModel = connected()
        val source = ChatImageSource.Embedded(lobby, "m1", "i1", 10, 10)
        viewModel.chat.openViewer(source)
        runCurrent()
        assertEquals(source, viewModel.chatUi.viewer)
        viewModel.chat.closeViewer()
        runCurrent()
        assertNull(viewModel.chatUi.viewer)
    }

    @Test
    fun disconnectingClosesTheChat() = runTest(dispatcher) {
        val viewModel = connected { it.unread[lobby] = 2u }
        openChatTab(viewModel)
        viewModel.disconnect()
        runCurrent()
        assertNull(viewModel.chatState.value)
        assertNull(viewModel.chatLocal.chat)
        assertNull(engine.openChat)
        assertEquals(0, viewModel.chatBadge.value)
    }
}
