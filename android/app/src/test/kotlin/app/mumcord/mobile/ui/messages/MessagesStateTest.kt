// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.messages

import app.mumcord.mobile.bridge.BlockedPerson
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.Conversation
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.bridge.MessageRequests
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.SentChatRequest
import app.mumcord.mobile.session.blockedPerson
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.conversation
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.messageContent
import app.mumcord.mobile.session.messageRequest
import app.mumcord.mobile.session.mockupServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesStateTest {
    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    private val none = MessageRequests(emptyList(), emptyList())
    private val anna = conversation("Anna", "Joining Gaming later?", sentAtMs = 3_000, peerSession = MOCKUP_ANNA)
    private val ben = conversation("Ben", "Thanks, got the map!", sentAtMs = 2_000, peerSession = MOCKUP_BEN)
    private val nora = conversation("Nora", "See you tomorrow", sentAtMs = 1_000)

    private fun state(
        local: MessagesLocal = MessagesLocal(),
        conversations: List<Conversation> = listOf(anna, ben, nora),
        requests: MessageRequests = none,
        blocked: List<BlockedPerson> = emptyList(),
        unread: Map<ChatId, Int> = emptyMap(),
        granted: Boolean = true,
    ) = checkNotNull(messagesUiState(session, local, conversations, requests, blocked, unread, granted))

    @Test
    fun thereIsNoMessagesTabWithoutAServer() {
        assertNull(messagesUiState(SessionState(), MessagesLocal(), listOf(anna), none, emptyList(), emptyMap(), true))
    }

    @Test
    fun aRowShowsNamePreviewTimeAndWhetherThePersonIsOnTheServer() {
        val rows = state().rows

        assertEquals(listOf("Anna", "Ben", "Nora"), rows.map { it.name })
        assertEquals(ConversationPreview.Text("Joining Gaming later?"), rows[0].preview)
        assertEquals(3_000L, rows[0].sentAtMs)
        assertEquals(listOf(true, true, false), rows.map { it.online })
        assertEquals(listOf(MOCKUP_ANNA, MOCKUP_BEN, null), rows.map { it.session })
    }

    @Test
    fun aPersonWhoLeftSinceTheListWasReadIsNotShownAsOnline() {
        val gone = anna.copy(peerSession = 77u)

        assertFalse(state(conversations = listOf(gone)).rows.single().online)
    }

    @Test
    fun theUnreadCountIsTheLiveOneNotTheOneOfTheList() {
        val stale = anna.copy(unread = 5u)

        assertEquals(0, state(conversations = listOf(stale)).rows.single().unread)
        assertEquals(2, state(conversations = listOf(stale), unread = mapOf(anna.chat to 2)).rows.single().unread)
    }

    @Test
    fun aPictureWithoutWordsIsNamedAndAnEmptyMessageShowsNothing() {
        val image = ContentBlock.Image(ImageRef.Embedded("i", "image/png", 1u, 1u, 1u, false))
        val picture = anna.copy(last = anna.last.copy(content = messageContent(blocks = listOf(image))))
        val empty = anna.copy(last = anna.last.copy(content = messageContent(blocks = emptyList())))

        assertEquals(ConversationPreview.Picture, state(conversations = listOf(picture)).rows.single().preview)
        assertEquals(ConversationPreview.Nothing, state(conversations = listOf(empty)).rows.single().preview)
    }

    @Test
    fun aConversationWithOnlyOwnMessagesTakesTheNameOfThePersonOnTheServer() {
        val unnamed = anna.copy(peerName = "")

        assertEquals("Anna", state(conversations = listOf(unnamed)).rows.single().name)
        assertEquals("", state(conversations = listOf(unnamed.copy(peerSession = null))).rows.single().name)
    }

    @Test
    fun theSearchFiltersByNameIgnoringCaseAndOnlyWhileItIsOpen() {
        assertEquals(3, state(MessagesLocal(query = "an")).rows.size)
        assertEquals(listOf("Anna"), state(MessagesLocal(searching = true, query = " AN ")).rows.map { it.name })
        assertEquals(3, state(MessagesLocal(searching = true, query = "  ")).rows.size)

        val nothing = state(MessagesLocal(searching = true, query = "zz"))
        assertTrue(nothing.rows.isEmpty())
        assertTrue("the search hides them, they are still there", nothing.hasConversations)
    }

    @Test
    fun theBadgeCountsUnreadPrivateMessagesAndNoChannelMessages() {
        val unread = mapOf(anna.chat to 2, ChatId.Session(9u) to 1, ChatId.Channel(MOCKUP_LOBBY) to 7)

        assertEquals(3, messagesTabBadge(unread))
        assertEquals(3, state(unread = unread).badge)
        assertEquals(0, messagesTabBadge(emptyMap()))
    }

    @Test
    fun aRequestShowsTheNoteOfAChatRequestOrTheFirstHeldMessage() {
        val held = chatMessage("h1", ChatId.Session(90u), "Tom", content = messageContent("Are you there?"))
        val requests = MessageRequests(
            incoming = listOf(
                messageRequest(1u, "Kim", note = "We met in Raid"),
                messageRequest(2u, "Tom", messages = listOf(held, held.copy(id = "h2"))),
                messageRequest(3u, "Ole", chatRequest = true),
            ),
            sent = emptyList(),
        )

        val rows = state(requests = requests).requests

        assertEquals(listOf(1uL, 2uL, 3uL), rows.map { it.id })
        assertEquals(ConversationPreview.Text("We met in Raid"), rows[0].text)
        assertTrue(rows[0].chatRequest)
        assertEquals(ConversationPreview.Text("Are you there?"), rows[1].text)
        assertEquals(2, rows[1].messageCount)
        assertFalse(rows[1].chatRequest)
        assertEquals(ConversationPreview.Nothing, rows[2].text)
    }

    @Test
    fun thePickerListsEverybodyElseByNameAndFilters() {
        assertEquals(listOf("Anna", "Ben", "Jonas", "Mira"), state().pickerPeople.map { it.name })
        assertEquals(listOf(MOCKUP_MIRA), state(MessagesLocal(pickerQuery = "mi")).pickerPeople.map { it.session })
        assertTrue(state(MessagesLocal(pickerQuery = "lea")).pickerPeople.isEmpty())
    }

    @Test
    fun theOpenConversationTakesItsNameFromTheListThenFromWhereItWasOpenedThenFromTheServer() {
        val listed = state(MessagesLocal(open = OpenConversation(anna.chat, name = "Old name")))
        assertEquals("Anna", listed.open?.name)
        assertTrue(listed.openOnline)

        val fresh = state(MessagesLocal(open = OpenConversation(ChatId.Person("new"), "Mira", MOCKUP_MIRA)))
        assertEquals("Mira", fresh.open?.name)
        assertTrue(fresh.openOnline)

        val bySession = state(MessagesLocal(open = OpenConversation(ChatId.Session(MOCKUP_JONAS))))
        assertEquals("Jonas", bySession.open?.name)
        assertTrue(bySession.openOnline)

        val offline = state(MessagesLocal(open = OpenConversation(nora.chat)))
        assertEquals("Nora", offline.open?.name)
        assertFalse(offline.openOnline)
    }

    @Test
    fun thePersonSheetOffersAChatRequestUntilOneWasSent() {
        val requests = MessageRequests(
            incoming = emptyList(),
            sent = listOf(SentChatRequest(MOCKUP_BEN, "Ben", accepted = false), SentChatRequest(MOCKUP_MIRA, "Mira", accepted = true)),
        )
        val ui = state(requests = requests)

        assertEquals(PersonMessaging(blocked = false, chatRequest = ChatRequestOffer.Offer), ui.person(MOCKUP_ANNA))
        assertEquals(ChatRequestOffer.Pending, ui.person(MOCKUP_BEN).chatRequest)
        assertEquals(ChatRequestOffer.Accepted, ui.person(MOCKUP_MIRA).chatRequest)
    }

    private fun sessionWith(session: UInt, change: (Person) -> Person): SessionState =
        this.session.fold(BridgeEvent.PersonChanged(change(checkNotNull(this.session.server).people.getValue(session))))

    @Test
    fun thePersonSheetOffersUnblockForEveryoneTheEngineCallsBlocked() {
        val blocked = sessionWith(MOCKUP_JONAS) { it.copy(blocked = true) }
        val ui = checkNotNull(messagesUiState(blocked, MessagesLocal(), emptyList(), none, emptyList(), emptyMap(), true))

        assertTrue(ui.person(MOCKUP_JONAS).blocked)
        assertFalse(ui.person(MOCKUP_ANNA).blocked)
    }

    @Test
    fun aChatRequestIsOfferedOnlyToPeopleWhoseAppUnderstandsIt() {
        val otherClient = sessionWith(MOCKUP_ANNA) { it.copy(supportsChatRequests = false) }
        val ui = checkNotNull(messagesUiState(otherClient, MessagesLocal(), emptyList(), none, emptyList(), emptyMap(), true))

        assertEquals(ChatRequestOffer.Unsupported, ui.person(MOCKUP_ANNA).chatRequest)
        assertFalse(ui.person(MOCKUP_ANNA).chatRequest.shown)
        assertEquals(ChatRequestOffer.Offer, ui.person(MOCKUP_BEN).chatRequest)
        assertTrue(ui.person(MOCKUP_BEN).chatRequest.shown)
        assertFalse(ChatRequestOffer.Accepted.shown)
        assertTrue(ChatRequestOffer.Pending.shown)
    }

    @Test
    fun theBlockQuestionComesBeforeTheChatRequestAndBothNeedThePersonOnTheServer() {
        val both = state(MessagesLocal(blockSession = MOCKUP_JONAS, blockFailed = true, requestSession = MOCKUP_BEN))
        assertEquals(MessagesSheet.BlockConfirm(MOCKUP_JONAS, "Jonas", failed = true), both.sheet)

        val request = state(
            MessagesLocal(requestSession = MOCKUP_BEN, requestNote = "Hi", requestError = ChatRequestError.Refused),
        )
        assertEquals(MessagesSheet.ChatRequest(MOCKUP_BEN, "Ben", "Hi", sending = false, ChatRequestError.Refused), request.sheet)

        assertNull("the person left", state(MessagesLocal(blockSession = 77u)).sheet)
    }

    @Test
    fun blockedPeopleNotificationsAndTheSwitchArePassedOn() {
        val ui = state(
            MessagesLocal(blockedOpen = true, senderOnly = true),
            blocked = listOf(blockedPerson(3, "Troll")),
            granted = false,
        )

        assertEquals(listOf(BlockedRow(3, "Troll", "Nordlicht")), ui.blocked)
        assertTrue(ui.blockedOpen)
        assertTrue(ui.senderOnly)
        assertTrue(ui.notificationsOff)
        assertFalse(state().notificationsOff)
    }

    @Test
    fun backHasSomethingToCloseWhileAConversationAPageTheSearchOrTheBlockedListIsOpen() {
        assertFalse(state().canGoBack)
        assertTrue(state(MessagesLocal(open = OpenConversation(anna.chat))).canGoBack)
        assertTrue(state(MessagesLocal(page = MessagesPage.Requests)).canGoBack)
        assertTrue(state(MessagesLocal(page = MessagesPage.Picker)).canGoBack)
        assertTrue(state(MessagesLocal(searching = true)).canGoBack)
        assertTrue(state(MessagesLocal(blockedOpen = true)).canGoBack)
    }
}
