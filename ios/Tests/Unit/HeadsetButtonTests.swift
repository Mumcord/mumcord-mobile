// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import Mumcord

/// What a headset's button and the system's mute do to the microphone, and the sounds that
/// say so (FR-5.35).
final class HeadsetButtonTests: XCTestCase {
    private let unmuted = SelfVoice(muted: false, deafened: false)
    private let muted = SelfVoice(muted: true, deafened: false)
    private let deafened = SelfVoice(muted: true, deafened: true)

    func testAPressTogglesTheMicrophoneWithASound() {
        XCTAssertEqual(headsetPress(unmuted), HeadsetMute(muted: true, tone: .muted))
        XCTAssertEqual(headsetPress(muted), HeadsetMute(muted: false, tone: .unmuted))
    }

    func testAPressEndsADeafenWithoutASound() {
        XCTAssertEqual(headsetPress(deafened), HeadsetMute(muted: false, tone: nil))
    }

    func testWithoutASessionAPressDoesNothing() {
        XCTAssertNil(headsetPress(nil))
        XCTAssertNil(systemMute(nil, muted: true))
    }

    func testTheSystemsMuteIsFollowedWithASound() {
        XCTAssertEqual(systemMute(unmuted, muted: true), HeadsetMute(muted: true, tone: .muted))
        XCTAssertEqual(systemMute(muted, muted: false), HeadsetMute(muted: false, tone: .unmuted))
    }

    func testWhatIsAlreadySoChangesNothing() {
        XCTAssertNil(systemMute(unmuted, muted: false))
        XCTAssertNil(systemMute(muted, muted: true))
        XCTAssertNil(systemMute(deafened, muted: true))
    }

    func testADeafenedUserGetsNoSound() {
        XCTAssertEqual(systemMute(deafened, muted: false), HeadsetMute(muted: false, tone: nil))
    }

    // MARK: The sounds

    func testAToneIsTwoShortQuietNotesThatStartAndEndInSilence() {
        for tone in FeedbackTone.allCases {
            let samples = feedbackToneSamples(tone)
            // Two notes of 70 ms at 48 kHz.
            XCTAssertEqual(samples.count, 6720)
            let note = samples.count / 2
            for edge in [0, note - 1, note, samples.count - 1] {
                XCTAssertEqual(samples[edge], 0, "the tone clicks at sample \(edge)")
            }
            let loudest = samples.map { abs(Int($0)) }.max() ?? 0
            XCTAssertGreaterThan(loudest, 3000)
            XCTAssertLessThanOrEqual(loudest, Int(Int16.max) / 10 + 1)
        }
    }

    func testMutingGoesDownAndUnmutingGoesUp() {
        XCTAssertGreaterThan(FeedbackTone.muted.notes.firstHz, FeedbackTone.muted.notes.secondHz)
        XCTAssertLessThan(FeedbackTone.unmuted.notes.firstHz, FeedbackTone.unmuted.notes.secondHz)
        XCTAssertNotEqual(feedbackToneSamples(.muted), feedbackToneSamples(.unmuted))
    }

    func testTheSoundFileIsAMonoSixteenBitWaveFile() {
        let samples = feedbackToneSamples(.muted)
        let file = feedbackToneFile(.muted)
        XCTAssertEqual(file.count, 44 + samples.count * 2)
        XCTAssertEqual(String(decoding: file.prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(String(decoding: file.subdata(in: 8..<16), as: UTF8.self), "WAVEfmt ")
        // One channel, 48 000 samples a second, as little-endian numbers.
        XCTAssertEqual(Array(file.subdata(in: 22..<28)), [1, 0, 0x80, 0xBB, 0, 0])
        XCTAssertEqual(String(decoding: file.subdata(in: 36..<40), as: UTF8.self), "data")
    }
}
