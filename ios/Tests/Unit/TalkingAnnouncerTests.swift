// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// When VoiceOver says who starts talking: the rules of Android's `TalkingAnnouncerTest`, the
/// sentences, and the part that feeds the voice view's people to the announcer.
final class TalkingAnnouncerTests: XCTestCase {
    private var announcer = TalkingAnnouncer(gap: 5, repeatTime: 30)

    private let anna = TalkingAnnouncer.Speaker(session: 1, name: "Anna")
    private let ben = TalkingAnnouncer.Speaker(session: 2, name: "Ben")
    private let jonas = TalkingAnnouncer.Speaker(session: 3, name: "Jonas")

    func testTheFirstSpeakerIsAnnouncedAtOnce() {
        XCTAssertEqual(announcer.update(now: 0, others: [anna]), ["Anna"])
        XCTAssertNil(announcer.nextUpdateAt)
    }

    func testStoppingIsNeverAnnounced() {
        _ = announcer.update(now: 0, others: [anna])
        XCTAssertEqual(announcer.update(now: 1, others: []), [])
        XCTAssertNil(announcer.nextUpdateAt)
    }

    func testSpeakersWithinTheGapAreNamedTogetherWhenItEnds() {
        _ = announcer.update(now: 0, others: [anna])
        XCTAssertEqual(announcer.update(now: 1, others: [anna, ben]), [])
        XCTAssertEqual(announcer.nextUpdateAt, 5)
        XCTAssertEqual(announcer.update(now: 2, others: [anna, ben, jonas]), [])
        XCTAssertEqual(announcer.update(now: 5, others: [anna, ben, jonas]), ["Ben", "Jonas"])
        XCTAssertNil(announcer.nextUpdateAt)
    }

    func testSomebodyWhoStoppedBeforeTheirTurnIsDropped() {
        _ = announcer.update(now: 0, others: [anna])
        _ = announcer.update(now: 1, others: [ben])
        XCTAssertEqual(announcer.update(now: 2, others: []), [])
        XCTAssertNil(announcer.nextUpdateAt)
        XCTAssertEqual(announcer.update(now: 5, others: []), [])
    }

    func testTheSamePersonIsNamedAgainOnlyAfterTheRepeatTime() {
        _ = announcer.update(now: 0, others: [anna])
        _ = announcer.update(now: 1, others: [])
        XCTAssertEqual(announcer.update(now: 10, others: [anna]), [])
        XCTAssertNil(announcer.nextUpdateAt)
        _ = announcer.update(now: 11, others: [])
        XCTAssertEqual(announcer.update(now: 30, others: [anna]), ["Anna"])
    }

    func testAFreshSpeakerAfterTheGapIsAnnouncedAtOnce() {
        _ = announcer.update(now: 0, others: [anna])
        XCTAssertEqual(announcer.update(now: 6, others: [anna, ben]), ["Ben"])
    }

    func testTheSentencesNameOneTwoOrTheFirstAndHowManyOthers() {
        XCTAssertNil(talkingAnnouncement([]))
        XCTAssertEqual(talkingAnnouncement(["Anna"]), "Anna is talking")
        XCTAssertEqual(talkingAnnouncement(["Anna", "Ben"]), "Anna and Ben are talking")
        XCTAssertEqual(talkingAnnouncement(["Anna", "Ben", "Jonas"]), "Anna and 2 other people are talking")
        XCTAssertEqual(
            Strings.talkingAnnouncementMore("Anna", 1),
            "Anna and 1 other person are talking",
            "one other person takes the singular"
        )
    }

    @MainActor
    func testOthersWhoStartTalkingAreSaidAndTheUserIsNot() {
        var now: TimeInterval = 0
        var said: [String] = []
        let announcements = TalkingAnnouncements(clock: { now }, post: { said.append($0) })
        let lea = VoiceTile(session: 9, name: "Lea", isSelf: true, talking: true, muted: false)

        XCTAssertNil(announcements.update([lea, tile(1, "Anna", talking: false)]))
        XCTAssertEqual(said, [], "the user's own talking is never said")

        XCTAssertNil(announcements.update([lea, tile(1, "Anna", talking: true), tile(2, "Ben", talking: true)]))
        XCTAssertEqual(said, ["Anna and Ben are talking"])

        now = 2
        let wait = announcements.update([lea, tile(1, "Anna", talking: true), tile(2, "Ben", talking: true), tile(3, "Jonas", talking: true)])
        XCTAssertEqual(wait, 3, "asked again when the gap ends")
        XCTAssertEqual(said.count, 1)

        now = 5
        XCTAssertNil(announcements.update([lea, tile(1, "Anna", talking: true), tile(2, "Ben", talking: true), tile(3, "Jonas", talking: true)]))
        XCTAssertEqual(said, ["Anna and Ben are talking", "Jonas is talking"])
    }

    private func tile(_ session: UInt32, _ name: String, talking: Bool) -> VoiceTile {
        VoiceTile(session: session, name: name, isSelf: false, talking: talking, muted: false)
    }
}
