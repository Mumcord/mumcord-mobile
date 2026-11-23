// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest

@testable import Mumcord

/// The chat screen as values: rows, paging, arriving messages, writing and the screen state.
final class ChatStateTests: XCTestCase {
    private let lobby = ChatId.channel(channelId: Mockup.lobby)
    private let gaming = ChatId.channel(channelId: Mockup.gaming)
    private let day = ChatDay(year: 2026, month: 5, day: 4)
    private let dayBefore = ChatDay(year: 2026, month: 5, day: 3)

    /// A fixed zone, so the days of the messages do not depend on where the tests run.
    private let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
        return calendar
    }()

    private func at(_ hour: Int, _ minute: Int, on other: ChatDay? = nil) -> Int64 {
        let chosen = other ?? day
        let parts = DateComponents(year: chosen.year, month: chosen.month, day: chosen.day, hour: hour, minute: minute)
        guard let moment = utc.date(from: parts) else {
            XCTFail("the calendar could not build the time of a message")
            return 0
        }
        return Int64(moment.timeIntervalSince1970 * 1000)
    }

    private func message(_ id: String, name: String = "Anna", sentAtMs: Int64? = nil, own: Bool = false) -> ChatMessage {
        chatMessage(id: id, chat: lobby, senderName: name, own: own, sentAtMs: sentAtMs ?? at(18, 0))
    }

    private func rows(_ messages: ChatMessage..., pending: [PendingSend] = []) -> [ChatRow] {
        chatRows(messages: messages, pending: pending, hints: [], calendar: utc)
    }

    private func messageHeaders(_ rows: [ChatRow]) -> [Bool] {
        rows.compactMap { row -> Bool? in
            if case .message(_, let showHeader) = row { return showHeader }
            return nil
        }
    }

    private func pendingHeaders(_ rows: [ChatRow]) -> [Bool] {
        rows.compactMap { row -> Bool? in
            if case .pending(_, let showHeader) = row { return showHeader }
            return nil
        }
    }

    /// Connected to the mockup server; the user is Lea in the Lobby.
    private var session: SessionState {
        let events: [BridgeEvent] = [
            .connectionChanged(state: .connecting),
            .connectionChanged(state: .connected),
            .serverSynced(server: mockupServer()),
        ]
        return events.reduce(SessionState()) { state, event in state.fold(event) }
    }

    private func uiState(_ local: ChatLocal, session other: SessionState? = nil) throws -> ChatUiState {
        try XCTUnwrap(chatUiState(session: other ?? session, local: local, serverName: "Nordlicht", calendar: utc))
    }

    // MARK: Rows

    func testTheFirstMessageOfEachDayGetsASeparator() {
        let shown = rows(
            message("a", sentAtMs: at(23, 59, on: dayBefore)),
            message("b", sentAtMs: at(0, 1)),
            message("c", sentAtMs: at(0, 2))
        )
        XCTAssertEqual(shown.map(\.id), ["day-2026-5-3", "message-a", "day-2026-5-4", "message-b", "message-c"])
        XCTAssertEqual(shown.first, .day(dayBefore))
    }

    func testTheDayOfAMessageIsTheOneInTheCalendarsTimeZone() {
        var eastern = Calendar(identifier: .gregorian)
        eastern.timeZone = TimeZone(secondsFromGMT: 2 * 60 * 60) ?? .gmt
        let late = message("a", sentAtMs: at(23, 30, on: dayBefore))
        XCTAssertEqual(chatRows(messages: [late], pending: [], hints: [], calendar: utc).first, .day(dayBefore))
        XCTAssertEqual(chatRows(messages: [late], pending: [], hints: [], calendar: eastern).first, .day(day))
    }

    func testConsecutiveMessagesOfOneSenderWithinFiveMinutesShareOneHeader() {
        let shown = rows(
            message("a", sentAtMs: at(18, 0)),
            message("b", sentAtMs: at(18, 5)),
            message("c", sentAtMs: at(18, 11)),
            message("d", name: "Ben", sentAtMs: at(18, 11)),
            message("e", sentAtMs: at(18, 12))
        )
        XCTAssertEqual(messageHeaders(shown), [true, false, true, true, true])
    }

    func testANewDayStartsANewGroupEvenWithinFiveMinutes() {
        let shown = rows(
            message("a", sentAtMs: at(23, 58, on: dayBefore)),
            message("b", sentAtMs: at(0, 1))
        )
        XCTAssertEqual(messageHeaders(shown), [true, true])
    }

    func testAnOwnMessageAndASameNamedStrangerAreNotGrouped() {
        let shown = rows(message("a", name: "Lea", own: true), message("b", name: "Lea"))
        XCTAssertEqual(messageHeaders(shown), [true, true])
    }

    func testMessagesOfBlockedPeopleAreLeftOut() {
        var blocked = message("b", name: "Troll")
        blocked.blocked = true
        XCTAssertEqual(rows(message("a"), blocked).map(\.id), ["day-2026-5-4", "message-a"])
        XCTAssertTrue(rows(blocked).isEmpty)
    }

    func testABlockedMessageDoesNotBreakTheGroupAroundIt() {
        var blocked = message("b", name: "Troll", sentAtMs: at(18, 1))
        blocked.blocked = true
        let shown = rows(message("a", sentAtMs: at(18, 0)), blocked, message("c", sentAtMs: at(18, 2)))
        XCTAssertEqual(messageHeaders(shown), [true, false])
    }

    func testPendingMessagesAndHintsComeLast() {
        let rows = chatRows(
            messages: [message("a")],
            pending: [PendingSend(id: 7, content: .text("hi", reply: nil))],
            hints: [UnsupportedHint(id: 3, personName: "Jonas", kind: .fileTransfer)],
            calendar: utc
        )
        XCTAssertEqual(rows.map(\.id), ["day-2026-5-4", "message-a", "pending-7", "hint-3"])
    }

    func testOnlyTheFirstPendingMessageAfterSomebodyElseGetsAHeader() {
        let first = PendingSend(id: 1, content: .text("one", reply: nil))
        let second = PendingSend(id: 2, content: .gif(url: "https://gifs.example/a.gif"))
        XCTAssertEqual(pendingHeaders(rows(message("a"), pending: [first, second])), [true, false])
        XCTAssertEqual(pendingHeaders(rows(message("a", name: "Lea", own: true), pending: [first, second])), [false, false])
        XCTAssertEqual(pendingHeaders(rows(pending: [first])), [true])
    }

    func testRowIdsStayTheSameWhenOlderMessagesAreAddedInFront() {
        let before = rows(message("b", sentAtMs: at(18, 2)))
        let after = rows(message("a", sentAtMs: at(18, 1)), message("b", sentAtMs: at(18, 2)))
        XCTAssertTrue(Set(after.map(\.id)).isSuperset(of: before.map(\.id)))
    }

    func testDaysAreNamedTodayYesterdayOrByDate() {
        let twoBefore = ChatDay(year: 2026, month: 5, day: 2)
        XCTAssertEqual(dayLabel(day, today: day, calendar: utc), .today)
        XCTAssertEqual(dayLabel(dayBefore, today: day, calendar: utc), .yesterday)
        XCTAssertEqual(dayLabel(twoBefore, today: day, calendar: utc), .date(twoBefore))
    }

    func testYesterdayCrossesTheEndOfAMonthAndOfAYear() {
        let firstOfMay = ChatDay(year: 2026, month: 5, day: 1)
        XCTAssertEqual(dayLabel(ChatDay(year: 2026, month: 4, day: 30), today: firstOfMay, calendar: utc), .yesterday)
        let newYear = ChatDay(year: 2026, month: 1, day: 1)
        XCTAssertEqual(dayLabel(ChatDay(year: 2025, month: 12, day: 31), today: newYear, calendar: utc), .yesterday)
        XCTAssertEqual(newYear.previous(calendar: utc), ChatDay(year: 2025, month: 12, day: 31))
    }

    // MARK: Paging and arriving messages

    func testTheFirstPageIsShownAndTheListJumpsToTheNewestMessage() {
        var local = ChatLocal(chat: lobby)
        local.loading = true
        local.loadFailed = true
        let next = local.loaded(ChatPage(messages: [message("a"), message("b")], hasMore: true))
        XCTAssertEqual(next.messages.map(\.id), ["a", "b"])
        XCTAssertTrue(next.hasMore)
        XCTAssertFalse(next.loading)
        XCTAssertFalse(next.loadFailed)
        XCTAssertFalse(next.pagedOlder)
        XCTAssertEqual(next.scrollToken, local.scrollToken + 1)
    }

    func testAnOlderPageGoesInFrontWithoutDuplicates() {
        let local = ChatLocal(chat: lobby, messages: [message("c"), message("d")], hasMore: true, loadingOlder: true)
        let next = local.prepended(ChatPage(messages: [message("a"), message("b"), message("c")], hasMore: false))
        XCTAssertEqual(next.messages.map(\.id), ["a", "b", "c", "d"])
        XCTAssertFalse(next.hasMore)
        XCTAssertFalse(next.loadingOlder)
        XCTAssertTrue(next.pagedOlder)
        XCTAssertEqual(next.scrollToken, local.scrollToken, "reading older messages never jumps to the newest")
    }

    func testAMessageArrivingAtTheBottomIsFollowed() {
        let local = ChatLocal(chat: lobby, atBottom: true, visible: true)
        let next = local.received(message("a"))
        XCTAssertEqual(next.messages.map(\.id), ["a"])
        XCTAssertEqual(next.scrollToken, local.scrollToken + 1)
        XCTAssertEqual(next.newBelow, 0)
    }

    func testAMessageArrivingWhileReadingFurtherUpIsCountedAndNotFollowed() {
        let local = ChatLocal(chat: lobby, atBottom: false, visible: true)
        let next = local.received(message("a")).received(message("b"))
        XCTAssertEqual(next.scrollToken, local.scrollToken)
        XCTAssertEqual(next.newBelow, 2)
        let scrolled = next.scrolled(atBottom: true)
        XCTAssertEqual(scrolled.newBelow, 0)
        XCTAssertTrue(scrolled.atBottom)
        XCTAssertEqual(next.scrolled(atBottom: false).newBelow, 2)
    }

    func testAMessageArrivingWhileTheChatIsNotOnScreenIsNotFollowed() {
        let next = ChatLocal(chat: lobby, atBottom: true, visible: false).received(message("a"))
        XCTAssertEqual(next.scrollToken, 0)
        XCTAssertEqual(next.newBelow, 1)
    }

    func testMessagesOfOtherChatsAndRepeatsAreIgnored() {
        let local = ChatLocal(chat: lobby, messages: [message("a")])
        XCTAssertEqual(local.received(message("a")), local)
        XCTAssertEqual(local.received(chatMessage(id: "x", chat: gaming)), local)
        XCTAssertEqual(ChatLocal().received(message("a")), ChatLocal())
    }

    func testABlockedSendersMessageIsNotAnnouncedByThePill() {
        var blocked = message("a")
        blocked.blocked = true
        let next = ChatLocal(chat: lobby, atBottom: false, visible: true).received(blocked)
        XCTAssertEqual(next.newBelow, 0)
    }

    func testAnOwnMessageReplacesItsPendingRowAndIsFollowed() {
        let send = PendingSend(id: 1, content: .text("hi", reply: nil))
        let local = ChatLocal(chat: lobby, atBottom: false, newBelow: 3).sending(send)
        XCTAssertEqual(local.pending, [send])
        XCTAssertEqual(local.newBelow, 0)
        XCTAssertEqual(local.scrollToken, 1)
        let next = local.sent(1, message("own-1", own: true))
        XCTAssertTrue(next.pending.isEmpty)
        XCTAssertEqual(next.messages.map(\.id), ["own-1"])
        XCTAssertEqual(next.scrollToken, local.scrollToken + 1)
    }

    func testAFailureMarksOnlyTheMessageThatDidNotGetOut() {
        let first = PendingSend(id: 1, content: .text("one", reply: nil))
        let second = PendingSend(id: 2, content: .text("two", reply: nil))
        let local = ChatLocal(chat: lobby).sending(first).sending(second)
        let failed = local.sendFailed(2, .refused)
        XCTAssertEqual(failed.pending.map(\.failure), [nil, .refused])
        XCTAssertEqual(failed.pending.map(\.id), [1, 2])
        XCTAssertEqual(failed.sendFailed(9, .notSent), failed)
    }

    func testUnreadCountsLeaveOutChatsWithoutUnreadMessages() {
        let counts: [ChatId: Int] = [lobby: 2]
        XCTAssertEqual(counts.withUnread(gaming, 3), [lobby: 2, gaming: 3])
        XCTAssertEqual(counts.withUnread(lobby, 5), [lobby: 5])
        XCTAssertEqual(counts.withUnread(lobby, 0), [:])
        XCTAssertEqual(counts.withUnread(gaming, 0), [lobby: 2])
    }

    // MARK: Writing

    func testTheMentionQueryIsTheWordAfterAnAtSignUnderTheCursor() {
        XCTAssertEqual(mentionQuery("@", cursor: 1), "")
        XCTAssertEqual(mentionQuery("Hi @an", cursor: 6), "an")
        XCTAssertEqual(mentionQuery("Hi @an", cursor: 5), "a")
        XCTAssertEqual(mentionQuery("Hi @an there", cursor: 6), "an")
        XCTAssertNil(mentionQuery("Hi @an there", cursor: 12))
        XCTAssertNil(mentionQuery("Hi an", cursor: 5))
        XCTAssertNil(mentionQuery("lea@example", cursor: 11), "a mail address is not a mention")
        XCTAssertNil(mentionQuery("", cursor: 0))
        XCTAssertEqual(mentionQuery("@an", cursor: 99), "an", "cursor positions outside the text are clamped")
        XCTAssertNil(mentionQuery("@an", cursor: -5))
    }

    func testTheMentionQueryCountsTheCursorInUtf16Units() {
        // The emoji takes two units, so the cursor behind "an" is at 6, not at 5.
        XCTAssertEqual(mentionQuery("😀 @an", cursor: 6), "an")
        XCTAssertEqual(mentionQuery("😀 @an", cursor: 5), "a")
    }

    func testPickingAMentionCompletesTheWordAndMovesTheCursorBehindIt() {
        let atEnd = insertMention("Hi @an", cursor: 6, name: "Anna")
        XCTAssertEqual(atEnd.text, "Hi @Anna ")
        XCTAssertEqual(atEnd.cursor, 9)

        let inside = insertMention("Hi @an there", cursor: 6, name: "Anna")
        XCTAssertEqual(inside.text, "Hi @Anna  there")
        XCTAssertEqual(inside.cursor, 9)

        let none = insertMention("Hi", cursor: 2, name: "Anna")
        XCTAssertEqual(none.text, "Hi", "no mention under the cursor leaves the text alone")
        XCTAssertEqual(none.cursor, 2)

        let afterEmoji = insertMention("😀 @an", cursor: 6, name: "Anna")
        XCTAssertEqual(afterEmoji.text, "😀 @Anna ")
        XCTAssertEqual(afterEmoji.cursor, 9)
    }

    func testTheCursorIsBehindWhatWasTypedAtTheEnd() {
        XCTAssertEqual(cursorAfterEdit(old: "", new: "H"), 1)
        XCTAssertEqual(cursorAfterEdit(old: "Hel", new: "Hell"), 4)
    }

    func testTheCursorIsBehindWhatWasTypedInTheMiddle() {
        XCTAssertEqual(cursorAfterEdit(old: "Hllo", new: "Hello"), 2)
        XCTAssertEqual(cursorAfterEdit(old: "ello", new: "Hello"), 1)
    }

    func testTheCursorIsWhereSomethingWasDeleted() {
        XCTAssertEqual(cursorAfterEdit(old: "Hello", new: "Hell"), 4)
        XCTAssertEqual(cursorAfterEdit(old: "Hello", new: "Hllo"), 1)
        XCTAssertEqual(cursorAfterEdit(old: "Hello", new: "ello"), 0)
        XCTAssertEqual(cursorAfterEdit(old: "Hello", new: ""), 0)
    }

    func testTheCursorIsBehindWhatWasPastedOrReplaced() {
        XCTAssertEqual(cursorAfterEdit(old: "Hi there", new: "Hi big blue there"), 12)
        XCTAssertEqual(cursorAfterEdit(old: "Hello world", new: "Hello you"), 9)
        XCTAssertEqual(cursorAfterEdit(old: "", new: "pasted"), 6)
    }

    func testTheCursorCountsEmojiAsTwoUnits() {
        XCTAssertEqual(cursorAfterEdit(old: "Hi", new: "Hi😀"), 4)
        XCTAssertEqual(cursorAfterEdit(old: "ab", new: "a😀b"), 3)
        XCTAssertEqual(cursorAfterEdit(old: "a😀", new: "a"), 1)
        // Both emoji start with the same unit; the cursor still ends behind the whole character.
        XCTAssertEqual(cursorAfterEdit(old: "😀", new: "😁"), 2)
    }

    func testTheCursorIsAtTheEndWhenNothingChanged() {
        XCTAssertEqual(cursorAfterEdit(old: "Hello", new: "Hello"), 5)
        XCTAssertEqual(cursorAfterEdit(old: "", new: ""), 0)
        XCTAssertEqual(cursorAfterEdit(old: "😀", new: "😀"), 2)
    }

    func testTheCounterAppearsNearTheLimitAndGoesNegativeBeyondIt() {
        let limits = ChatLimits(text: 10, image: 0, allowHtml: true)
        XCTAssertNil(charactersLeft("12345678", limits: limits))
        XCTAssertEqual(charactersLeft("123456789", limits: limits), 1)
        XCTAssertEqual(charactersLeft("1234567890", limits: limits), 0)
        XCTAssertEqual(charactersLeft("123456789012", limits: limits), -2)
        let unlimited = ChatLimits(text: 0, image: 0, allowHtml: true)
        XCTAssertNil(charactersLeft(String(repeating: "x", count: 9999), limits: unlimited), "no limit, no counter")
        XCTAssertNil(charactersLeft("123456789", limits: nil))
    }

    func testEmptyAndTooLongTextsCannotBeSent() {
        let limits = ChatLimits(text: 5, image: 0, allowHtml: true)
        XCTAssertFalse(canSend("", limits: limits))
        XCTAssertFalse(canSend("  \n ", limits: limits))
        XCTAssertTrue(canSend("12345", limits: limits))
        XCTAssertFalse(canSend("123456", limits: limits))
        XCTAssertTrue(canSend("123456", limits: nil))
        XCTAssertTrue(canSend("123456", limits: ChatLimits(text: 0, image: 0, allowHtml: true)))
        XCTAssertTrue(canSend("😀😀😀😀😀", limits: limits), "characters outside the basic plane count once")
    }

    // MARK: The screen

    func testTheChatTabShowsTheChannelTheUserIsIn() throws {
        let state = try uiState(ChatLocal(chat: lobby))
        XCTAssertEqual(state.chat, lobby)
        XCTAssertEqual(state.title, "Lobby")
        XCTAssertEqual(state.people, 3)
        XCTAssertEqual(state.serverName, "Nordlicht")
        XCTAssertEqual(state.selfName, "Lea")
        XCTAssertTrue(state.empty)
        XCTAssertFalse(state.canSend)
        XCTAssertNil(state.sheet)
    }

    func testTheCurrentChatIsTheOneOfTheUsersChannel() {
        XCTAssertEqual(currentChannelChat(session), lobby)
        XCTAssertNil(currentChannelChat(SessionState()))
        XCTAssertNil(currentChannelChat(session.fold(.connectionChanged(state: .disconnected(error: nil)))))
    }

    func testAChatOfAnotherChannelIsNotShownAsTheCurrentOne() {
        XCTAssertNil(chatUiState(session: session, local: ChatLocal(chat: gaming), serverName: "Nordlicht", calendar: utc))
        XCTAssertNil(chatUiState(session: session, local: ChatLocal(), serverName: "Nordlicht", calendar: utc))
        XCTAssertNil(chatUiState(session: SessionState(), local: ChatLocal(chat: lobby), serverName: "Nordlicht", calendar: utc))
    }

    func testALoadingOrFailedChatIsNotEmpty() throws {
        XCTAssertFalse(try uiState(ChatLocal(chat: lobby, loading: true)).empty)
        XCTAssertFalse(try uiState(ChatLocal(chat: lobby, loadFailed: true)).empty)
        XCTAssertFalse(try uiState(ChatLocal(chat: lobby, messages: [message("a")])).empty)
    }

    func testTheDraftDecidesTheCounterAndTheSendButton() throws {
        let limits = ChatLimits(text: 10, image: 0, allowHtml: true)
        let fits = try uiState(ChatLocal(chat: lobby, draft: "123456789", limits: limits))
        XCTAssertEqual(fits.draft, "123456789")
        XCTAssertEqual(fits.charactersLeft, 1)
        XCTAssertTrue(fits.canSend)
        let tooLong = try uiState(ChatLocal(chat: lobby, draft: "12345678901", limits: limits))
        XCTAssertEqual(tooLong.charactersLeft, -1)
        XCTAssertFalse(tooLong.canSend)
    }

    func testHintsForThingsTheAppCannotShowAreRowsOfTheChat() throws {
        let hinted = session.withHint(UnsupportedHint(id: 1, personName: "Jonas", kind: .fileTransfer))
        let state = try uiState(ChatLocal(chat: lobby), session: hinted)
        XCTAssertEqual(state.rows.map(\.id), ["hint-1"])
        XCTAssertFalse(state.empty)
    }

    func testTheLinkWarningComesBeforeAnyOtherSheet() throws {
        let link = SafeLink(url: "https://evil.example/", host: "evil.example", kind: .web, misleading: true)
        let local = ChatLocal(chat: lobby, sheet: .gifs, linkWarning: link)
        XCTAssertEqual(try uiState(local).sheet, .linkWarning(link))
    }

    func testTheOpenSheetIsShownWithItsContents() throws {
        let favourite = GifFavourite(url: "https://gifs.example/a.gif", addedAtMs: 0, lastUsedAtMs: 0)
        XCTAssertEqual(try uiState(ChatLocal(chat: lobby, sheet: .gifs, gifs: [favourite])).sheet, .gifs([favourite]))
        XCTAssertEqual(try uiState(ChatLocal(chat: lobby, sheet: .imageSource)).sheet, .imageSource)
        XCTAssertNil(try uiState(ChatLocal(chat: lobby, gifs: [favourite])).sheet)
    }

    func testTheStartOfTheHistoryIsOnlyNamedAfterReadingBack() throws {
        XCTAssertFalse(try uiState(ChatLocal(chat: lobby, messages: [message("a")])).historyStart)
        let local = ChatLocal(chat: lobby, messages: [message("c")], hasMore: true)
        let more = local.prepended(ChatPage(messages: [message("b")], hasMore: true))
        XCTAssertFalse(try uiState(more).historyStart)
        let paged = more.prepended(ChatPage(messages: [message("a")], hasMore: false))
        XCTAssertTrue(try uiState(paged).historyStart)
    }

    func testThePillCountAndTheJumpTokenReachTheScreen() throws {
        let local = ChatLocal(chat: lobby, atBottom: false, visible: true).received(message("a"))
        let state = try uiState(local)
        XCTAssertEqual(state.newMessages, 1)
        XCTAssertEqual(state.scrollToken, local.scrollToken)
    }

    func testTheChatTabBadgeCountsTheCurrentChannelAndHidesWhileTheTabIsOpen() {
        let unread: [ChatId: Int] = [lobby: 4, gaming: 9, .session(session: 7): 2]
        XCTAssertEqual(chatTabBadge(unread: unread, session: session, chatTabOpen: false), 4)
        XCTAssertEqual(chatTabBadge(unread: unread, session: session, chatTabOpen: true), 0)
        XCTAssertEqual(chatTabBadge(unread: [:], session: session, chatTabOpen: false), 0)
        XCTAssertEqual(chatTabBadge(unread: unread, session: SessionState(), chatTabOpen: false), 0)
    }
}
