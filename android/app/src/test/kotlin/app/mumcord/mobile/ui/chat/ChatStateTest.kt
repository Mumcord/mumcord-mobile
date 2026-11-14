// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.chat

import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ChatPage
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.LinkKind
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.UnsupportedHint
import app.mumcord.mobile.session.UnsupportedKind
import app.mumcord.mobile.session.chatMessage
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.withHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChatStateTest {
    private val lobby = ChatId.Channel(MOCKUP_LOBBY)
    private val day = LocalDate.of(2026, 5, 4)

    private fun at(hour: Int, minute: Int, date: LocalDate = day): Long =
        date.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun message(id: String, name: String = "Anna", sentAtMs: Long = at(18, 0), own: Boolean = false): ChatMessage =
        chatMessage(id, lobby, name, own = own, sentAtMs = sentAtMs)

    private fun rows(vararg messages: ChatMessage, pending: List<PendingSend> = emptyList()) =
        chatRows(messages.toList(), pending, emptyList(), ZoneOffset.UTC)

    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    // ----- rows -----

    @Test
    fun theFirstMessageOfEachDayGetsASeparator() {
        val rows = rows(
            message("a", sentAtMs = at(23, 59, day.minusDays(1))),
            message("b", sentAtMs = at(0, 1)),
            message("c", sentAtMs = at(0, 2)),
        )
        assertEquals(
            listOf("day-2026-05-03", "message-a", "day-2026-05-04", "message-b", "message-c"),
            rows.map { it.key },
        )
    }

    @Test
    fun consecutiveMessagesOfOneSenderWithinFiveMinutesShareOneHeader() {
        val rows = rows(
            message("a", sentAtMs = at(18, 0)),
            message("b", sentAtMs = at(18, 5)),
            message("c", sentAtMs = at(18, 11)),
            message("d", name = "Ben", sentAtMs = at(18, 11)),
            message("e", sentAtMs = at(18, 12)),
        ).filterIsInstance<ChatRow.Message>()
        assertEquals(listOf(true, false, true, true, true), rows.map { it.showHeader })
    }

    @Test
    fun aNewDayStartsANewGroupEvenWithinFiveMinutes() {
        val rows = rows(
            message("a", sentAtMs = at(23, 58, day.minusDays(1))),
            message("b", sentAtMs = at(0, 1)),
        ).filterIsInstance<ChatRow.Message>()
        assertEquals(listOf(true, true), rows.map { it.showHeader })
    }

    @Test
    fun anOwnMessageAndASameNamedStrangerAreNotGrouped() {
        val rows = rows(message("a", name = "Lea", own = true), message("b", name = "Lea")).filterIsInstance<ChatRow.Message>()
        assertEquals(listOf(true, true), rows.map { it.showHeader })
    }

    @Test
    fun messagesOfBlockedPeopleAreLeftOut() {
        val blocked = message("b", name = "Troll").copy(blocked = true)
        assertEquals(listOf("day-2026-05-04", "message-a"), rows(message("a"), blocked).map { it.key })
        assertTrue(rows(blocked).isEmpty())
    }

    @Test
    fun pendingMessagesAndHintsComeLast() {
        val rows = chatRows(
            listOf(message("a")),
            listOf(PendingSend(7, PendingContent.Text("hi", null))),
            listOf(UnsupportedHint(3, "Jonas", UnsupportedKind.FileTransfer)),
            ZoneOffset.UTC,
        )
        assertEquals(listOf("day-2026-05-04", "message-a", "pending-7", "hint-3"), rows.map { it.key })
    }

    @Test
    fun rowKeysStayTheSameWhenOlderMessagesAreAddedInFront() {
        val before = rows(message("b", sentAtMs = at(18, 2)))
        val after = rows(message("a", sentAtMs = at(18, 1)), message("b", sentAtMs = at(18, 2)))
        assertTrue(after.map { it.key }.containsAll(before.map { it.key }))
    }

    @Test
    fun daysAreNamedTodayYesterdayOrByDate() {
        assertEquals(DayLabel.Today, dayLabel(day, day))
        assertEquals(DayLabel.Yesterday, dayLabel(day.minusDays(1), day))
        assertEquals(DayLabel.Date(day.minusDays(2)), dayLabel(day.minusDays(2), day))
    }

    // ----- paging and arriving messages -----

    @Test
    fun anOlderPageGoesInFrontWithoutDuplicates() {
        val local = ChatLocal(chat = lobby, messages = listOf(message("c"), message("d")), hasMore = true, loadingOlder = true)
        val next = local.prepended(ChatPage(listOf(message("a"), message("b"), message("c")), hasMore = false))
        assertEquals(listOf("a", "b", "c", "d"), next.messages.map { it.id })
        assertFalse(next.hasMore)
        assertFalse(next.loadingOlder)
        assertEquals("reading older messages never jumps to the newest", local.scrollToken, next.scrollToken)
    }

    @Test
    fun aMessageArrivingAtTheBottomIsFollowed() {
        val local = ChatLocal(chat = lobby, atBottom = true, visible = true)
        val next = local.received(message("a"))
        assertEquals(local.scrollToken + 1, next.scrollToken)
        assertEquals(0, next.newBelow)
    }

    @Test
    fun aMessageArrivingWhileReadingFurtherUpIsCountedAndNotFollowed() {
        val local = ChatLocal(chat = lobby, atBottom = false, visible = true)
        val next = local.received(message("a")).received(message("b"))
        assertEquals(local.scrollToken, next.scrollToken)
        assertEquals(2, next.newBelow)
        assertEquals(0, next.scrolled(atBottom = true).newBelow)
    }

    @Test
    fun aMessageArrivingWhileTheChatIsNotOnScreenIsNotFollowed() {
        val next = ChatLocal(chat = lobby, atBottom = true, visible = false).received(message("a"))
        assertEquals(0, next.scrollToken)
        assertEquals(1, next.newBelow)
    }

    @Test
    fun messagesOfOtherChatsAndRepeatsAreIgnored() {
        val local = ChatLocal(chat = lobby, messages = listOf(message("a")))
        assertEquals(local, local.received(message("a")))
        assertEquals(local, local.received(chatMessage("x", ChatId.Channel(MOCKUP_GAMING))))
    }

    @Test
    fun aBlockedSendersMessageIsNotAnnouncedByThePill() {
        val next = ChatLocal(chat = lobby, atBottom = false, visible = true).received(message("a").copy(blocked = true))
        assertEquals(0, next.newBelow)
    }

    @Test
    fun anOwnMessageReplacesItsPendingRowAndIsFollowed() {
        val send = PendingSend(1, PendingContent.Text("hi", null))
        val local = ChatLocal(chat = lobby, atBottom = false, newBelow = 3).sending(send)
        assertEquals(0, local.newBelow)
        val next = local.sent(1, message("own-1", own = true))
        assertTrue(next.pending.isEmpty())
        assertEquals(listOf("own-1"), next.messages.map { it.id })
        assertEquals(local.scrollToken + 1, next.scrollToken)
    }

    // ----- writing -----

    @Test
    fun theMentionQueryIsTheWordAfterAnAtSignUnderTheCursor() {
        assertEquals("", mentionQuery("@", 1))
        assertEquals("an", mentionQuery("Hi @an", 6))
        assertEquals("a", mentionQuery("Hi @an", 5))
        assertEquals("an", mentionQuery("Hi @an there", 6))
        assertNull(mentionQuery("Hi @an there", 12))
        assertNull(mentionQuery("Hi an", 5))
        assertNull("a mail address is not a mention", mentionQuery("lea@example", 11))
        assertNull(mentionQuery("", 0))
        assertEquals("cursor positions outside the text are clamped", "an", mentionQuery("@an", 99))
    }

    @Test
    fun pickingAMentionCompletesTheWordAndMovesTheCursorBehindIt() {
        assertEquals("Hi @Anna " to 9, insertMention("Hi @an", 6, "Anna"))
        assertEquals("Hi @Anna  there" to 9, insertMention("Hi @an there", 6, "Anna"))
        assertEquals("no mention under the cursor leaves the text alone", "Hi" to 2, insertMention("Hi", 2, "Anna"))
    }

    @Test
    fun theCounterAppearsNearTheLimitAndGoesNegativeBeyondIt() {
        val limits = ChatLimits(text = 10u, image = 0u, allowHtml = true)
        assertNull(charactersLeft("12345678", limits))
        assertEquals(1, charactersLeft("123456789", limits))
        assertEquals(-2, charactersLeft("123456789012", limits))
        assertNull("no limit, no counter", charactersLeft("x".repeat(9999), ChatLimits(0u, 0u, true)))
        assertNull(charactersLeft("123456789", null))
    }

    @Test
    fun emptyAndTooLongTextsCannotBeSent() {
        val limits = ChatLimits(text = 5u, image = 0u, allowHtml = true)
        assertFalse(canSend("", limits))
        assertFalse(canSend("  \n ", limits))
        assertTrue(canSend("12345", limits))
        assertFalse(canSend("123456", limits))
        assertTrue(canSend("123456", null))
        assertTrue("characters outside the basic plane count once", canSend("😀😀😀😀😀", limits))
    }

    // ----- the screen -----

    @Test
    fun theChatTabShowsTheChannelTheUserIsIn() {
        val state = checkNotNull(chatUiState(session, ChatLocal(chat = lobby), "Nordlicht", ZoneOffset.UTC))
        assertEquals("Lobby", state.title)
        assertEquals(3, state.people)
        assertEquals("Nordlicht", state.serverName)
        assertEquals("Lea", state.selfName)
        assertTrue(state.empty)
    }

    @Test
    fun aChatOfAnotherChannelIsNotShownAsTheCurrentOne() {
        assertNull(chatUiState(session, ChatLocal(chat = ChatId.Channel(MOCKUP_GAMING)), "Nordlicht", ZoneOffset.UTC))
        assertNull(chatUiState(SessionState(), ChatLocal(chat = lobby), "Nordlicht", ZoneOffset.UTC))
    }

    @Test
    fun hintsForThingsTheAppCannotShowAreRowsOfTheChat() {
        val hinted = session.withHint(UnsupportedHint(1, "Jonas", UnsupportedKind.FileTransfer))
        val state = checkNotNull(chatUiState(hinted, ChatLocal(chat = lobby), "Nordlicht", ZoneOffset.UTC))
        assertEquals(listOf("hint-1"), state.rows.map { it.key })
        assertFalse(state.empty)
    }

    @Test
    fun theLinkWarningComesBeforeAnyOtherSheet() {
        val link = SafeLink("https://evil.example/", "evil.example", LinkKind.WEB, misleading = true)
        val local = ChatLocal(chat = lobby, sheet = ChatSheetKind.Gifs, linkWarning = link)
        val state = checkNotNull(chatUiState(session, local, "Nordlicht", ZoneOffset.UTC))
        assertEquals(ChatSheet.LinkWarning(link), state.sheet)
    }

    @Test
    fun theStartOfTheHistoryIsOnlyNamedAfterReadingBack() {
        fun state(local: ChatLocal) = checkNotNull(chatUiState(session, local, "Nordlicht", ZoneOffset.UTC))
        assertFalse(state(ChatLocal(chat = lobby, messages = listOf(message("a")))).historyStart)
        val paged = ChatLocal(chat = lobby, messages = listOf(message("b")), hasMore = true)
            .prepended(ChatPage(listOf(message("a")), hasMore = false))
        assertTrue(state(paged).historyStart)
    }

    @Test
    fun theChatTabBadgeCountsTheCurrentChannelAndHidesWhileTheTabIsOpen() {
        val unread = mapOf(lobby to 4, ChatId.Channel(MOCKUP_GAMING) to 9, ChatId.Session(7u) to 2)
        assertEquals(4, chatTabBadge(unread, session, chatTabOpen = false))
        assertEquals(0, chatTabBadge(unread, session, chatTabOpen = true))
        assertEquals(0, chatTabBadge(emptyMap(), session, chatTabOpen = false))
        assertEquals(0, chatTabBadge(unread, SessionState(), chatTabOpen = false))
    }

    // ----- pictures -----

    @Test
    fun picturesAreDecodedNoLargerThanTheyAreShown() {
        assertEquals(1, sampleSize(400, 300, 400, 300))
        assertEquals(1, sampleSize(799, 599, 400, 300))
        assertEquals(2, sampleSize(800, 600, 400, 300))
        assertEquals(8, sampleSize(4000, 3000, 400, 300))
        assertEquals("a small picture is never enlarged by sampling", 1, sampleSize(10, 10, 400, 300))
        assertEquals(1, sampleSize(4000, 3000, 0, 0))
    }

    @Test
    fun picturesKeepTheirProportionsInsideTheFrame() {
        fun fitted(width: Int, height: Int) = fittedSize(width, height, 200.dp, 240.dp).let { it.width.value to it.height.value }
        assertEquals(200f, fitted(400, 225).first, 0.01f)
        assertEquals(112.5f, fitted(400, 225).second, 0.01f)
        assertEquals(60f, fitted(100, 400).first, 0.01f)
        assertEquals(240f, fitted(100, 400).second, 0.01f)
        assertEquals("an unknown size gets the 16:9 frame of the mockup", 112.5f, fitted(0, 0).second, 0.01f)
    }
}
