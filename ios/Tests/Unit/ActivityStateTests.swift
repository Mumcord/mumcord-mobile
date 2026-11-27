// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest

@testable import Mumcord

/// The activity log as the screen lists it: filters, days, the badge and the sentences.
final class ActivityStateTests: XCTestCase {
    private let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
        return calendar
    }()
    private let today = ChatDay(year: 2026, month: 11, day: 15)

    private func at(daysBefore: Int, hour: Int, minute: Int = 0) -> Int64 {
        let parts = DateComponents(year: today.year, month: today.month, day: today.day - daysBefore, hour: hour, minute: minute)
        return Int64((utc.date(from: parts)?.timeIntervalSince1970 ?? 0) * 1000)
    }

    private func entry(_ id: UInt64, _ kind: ActivityKind, _ atMs: Int64, isSelf: Bool = false) -> ActivityEntry {
        ActivityEntry(
            id: id,
            kind: kind,
            session: UInt32(id),
            name: "Person \(id)",
            isSelf: isSelf,
            channelId: 1,
            channelName: "Lobby",
            atMs: atMs
        )
    }

    /// Newest first, as the bridge and the state keep them.
    private var log: [ActivityEntry] {
        [
            entry(7, .streamStarted, at(daysBefore: 0, hour: 19)),
            entry(6, .moved, at(daysBefore: 0, hour: 18)),
            entry(5, .left, at(daysBefore: 0, hour: 17)),
            entry(4, .streamStopped, at(daysBefore: 1, hour: 22)),
            entry(3, .joined, at(daysBefore: 1, hour: 21)),
            entry(2, .joined, at(daysBefore: 3, hour: 20)),
            entry(1, .connected, at(daysBefore: 3, hour: 19), isSelf: true),
        ]
    }

    private func ids(_ days: [ActivityDay]) -> [[UInt64]] {
        days.map { $0.entries.map(\.id) }
    }

    private func days(_ entries: [ActivityEntry], _ filter: ActivityFilter, calendar: Calendar? = nil) -> [ActivityDay] {
        activityDays(entries, filter: filter, today: today, calendar: calendar ?? utc)
    }

    func testAllEntriesAreGroupedByDayNewestFirst() {
        let all = days(log, .all)
        XCTAssertEqual(all.map(\.label), [.today, .yesterday, .date(ChatDay(year: 2026, month: 11, day: 12))])
        XCTAssertEqual(ids(all), [[7, 6, 5], [4, 3], [2, 1]])
    }

    func testJoinsAreComingGoingAndMovingAndStreamsAreScreenShares() {
        XCTAssertEqual(ids(days(log, .joins)), [[6, 5], [3], [2, 1]])
        XCTAssertEqual(ids(days(log, .streams)), [[7], [4]])
    }

    func testTheDayFollowsThePhonesTimeZone() {
        // 23:30 UTC yesterday is already today half an hour east of it.
        let late = [entry(1, .joined, at(daysBefore: 1, hour: 23, minute: 30))]
        XCTAssertEqual(days(late, .all).map(\.label), [.yesterday])
        var east = utc
        east.timeZone = TimeZone(secondsFromGMT: 3600) ?? .gmt
        XCTAssertEqual(days(late, .all, calendar: east).map(\.label), [.today])
    }

    func testAFilterWithoutEntriesHasNoDays() {
        let joinsOnly = log.filter { $0.kind == .joined }
        XCTAssertTrue(days(joinsOnly, .streams).isEmpty)
        XCTAssertTrue(days([], .all).isEmpty)
    }

    func testTheBadgeCountsWhatOthersDidSinceTheLogWasLookedAt() {
        XCTAssertEqual(unseenActivity(log, seenId: nil), 6, "everything by others before the first look")
        XCTAssertEqual(unseenActivity(log, seenId: 5), 2)
        XCTAssertEqual(unseenActivity(log, seenId: 7), 0)
        let ownMove = [entry(8, .moved, at(daysBefore: 0, hour: 20), isSelf: true)] + log
        XCTAssertEqual(unseenActivity(ownMove, seenId: 7), 0, "the user's own doings are not news")
    }

    /// The parts of a sentence that are drawn bold.
    private func bold(_ sentence: AttributedString) -> [String] {
        sentence.runs
            .filter { $0.inlinePresentationIntent == .stronglyEmphasized }
            .map { String(sentence[$0.range].characters) }
    }

    func testTheNameIsBoldAndThePlaceIsPlainWhereverTheTemplatePutsThem() {
        let sentence = activitySentence(template: "%1$@ moved to %2$@", name: "Jonas", place: "Gaming")
        XCTAssertEqual(String(sentence.characters), "Jonas moved to Gaming")
        XCTAssertEqual(bold(sentence), ["Jonas"])

        let placeFirst = activitySentence(template: "In %2$@: %1$@", name: "Mira", place: "Music")
        XCTAssertEqual(String(placeFirst.characters), "In Music: Mira")
        XCTAssertEqual(bold(placeFirst), ["Mira"])

        let nameOnly = activitySentence(template: "%1$@ left the server", name: "Ben", place: "")
        XCTAssertEqual(String(nameOnly.characters), "Ben left the server")
    }

    func testNamesFromTheServerStayPlainCharacters() {
        // A name that looks like a placeholder or markup is not read as one.
        let sentence = activitySentence(template: "%1$@ joined %2$@", name: "%2$@ <b>x</b> **y**", place: "Lobby")
        XCTAssertEqual(String(sentence.characters), "%2$@ <b>x</b> **y** joined Lobby")
    }
}
