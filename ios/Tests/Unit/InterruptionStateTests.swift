// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// The rules for phone calls and other interruptions during a voice session (FR-5.33, FR-5.36).
final class InterruptionStateTests: XCTestCase {
    private let unmuted = SelfVoice(muted: false, deafened: false)
    private let muted = SelfVoice(muted: true, deafened: false)

    /// Runs the events one after the other; `voice` is what the user is set to before each.
    private func run(
        _ events: [(InterruptionEvent, SelfVoice?)],
        from start: InterruptionState = InterruptionState()
    ) -> (state: InterruptionState, effects: [InterruptionEffect]) {
        var state = start
        var all: [InterruptionEffect] = []
        for (event, voice) in events {
            let (next, effects) = state.step(event, current: voice)
            state = next
            all += effects
        }
        return (state, all)
    }

    func testAnAcceptedPhoneCallMutesAndDeafensAndPutsThePreviousStateBack() {
        let result = run([(.callHeld(true), unmuted), (.callHeld(false), .silenced)])
        XCTAssertEqual(result.effects, [.setVoice(.silenced), .setVoice(unmuted)])
        XCTAssertEqual(result.state, InterruptionState())
    }

    func testAMutedUserIsMutedAgainAfterThePhoneCall() {
        let result = run([(.callHeld(true), muted), (.callHeld(false), .silenced)])
        XCTAssertEqual(result.effects, [.setVoice(.silenced), .setVoice(muted)])
    }

    func testAUserWhoWasDeafenedAlreadyIsLeftAlone() {
        let result = run([(.callHeld(true), .silenced), (.callHeld(false), .silenced)])
        XCTAssertEqual(result.effects, [])
    }

    func testAChangeByTheUserDuringThePhoneCallStays() {
        // The user ended the deafen on the call screen of the app while the phone call went on.
        let result = run([(.callHeld(true), unmuted), (.callHeld(false), muted)])
        XCTAssertEqual(result.effects, [.setVoice(.silenced)])
        XCTAssertNil(result.state.saved)
    }

    func testTheSameHoldReportedTwiceChangesNothingMore() {
        let result = run([(.callHeld(true), unmuted), (.callHeld(true), .silenced)])
        XCTAssertEqual(result.effects, [.setVoice(.silenced)])
        XCTAssertEqual(result.state.saved, unmuted)
    }

    func testWithoutAKnownStateNothingIsSetOrRestored() {
        let result = run([(.callHeld(true), nil), (.callHeld(false), .silenced)])
        XCTAssertEqual(result.effects, [])
    }

    func testAnInterruptionPausesPlaybackUntilItIsOver() {
        let result = run([(.interruptionBegan, unmuted), (.interruptionEnded(shouldResume: true), unmuted)])
        XCTAssertEqual(result.effects, [.pausePlayback(true), .pausePlayback(false)])
        XCTAssertFalse(result.state.playbackPaused)
    }

    func testTheSessionGoesOnAlsoWhenTheSystemDoesNotAdviseToResume() {
        let result = run([(.interruptionBegan, unmuted), (.interruptionEnded(shouldResume: false), unmuted)])
        XCTAssertEqual(result.effects, [.pausePlayback(true), .pausePlayback(false)])
    }

    func testAnInterruptionNeverTouchesTheStateOnTheServer() {
        let result = run([(.interruptionBegan, unmuted), (.interruptionBegan, unmuted)])
        XCTAssertEqual(result.effects, [.pausePlayback(true)])
    }

    func testAnEndWithoutABeginningDoesNothing() {
        XCTAssertEqual(run([(.interruptionEnded(shouldResume: true), unmuted)]).effects, [])
    }

    func testDuringAPhoneCallAnInterruptionDoesNotPauseAsWell() {
        let result = run([(.callHeld(true), unmuted), (.interruptionBegan, .silenced)])
        XCTAssertEqual(result.effects, [.setVoice(.silenced)])
        XCTAssertFalse(result.state.playbackPaused)
    }

    func testTheEndOfThePhoneCallEndsAPauseThatBeganWithIt() {
        // The interruption came first and its end is never reported.
        let result = run([(.interruptionBegan, unmuted), (.callHeld(true), unmuted), (.callHeld(false), .silenced)])
        XCTAssertEqual(
            result.effects,
            [.pausePlayback(true), .setVoice(.silenced), .pausePlayback(false), .setVoice(unmuted)]
        )
        XCTAssertEqual(result.state, InterruptionState())
    }

    func testAChangeOfTheAudioRouteChangesNothing() {
        XCTAssertEqual(run([(.routeChanged, unmuted)]).effects, [])
        let paused = run([(.interruptionBegan, unmuted), (.routeChanged, unmuted)])
        XCTAssertEqual(paused.effects, [.pausePlayback(true)])
        XCTAssertTrue(paused.state.playbackPaused)
    }

    func testARestartOfTheAudioServiceEndsAPauseAndKeepsThePhoneCall() {
        let result = run([(.interruptionBegan, unmuted), (.callHeld(true), unmuted), (.servicesReset, .silenced)])
        XCTAssertEqual(result.effects, [.pausePlayback(true), .setVoice(.silenced), .pausePlayback(false)])
        XCTAssertTrue(result.state.held)
        XCTAssertEqual(result.state.saved, unmuted)
    }

    func testAfterTheSessionEndedNothingIsRestored() {
        let result = run([(.callHeld(true), unmuted), (.sessionEnded, nil), (.callHeld(false), nil)])
        XCTAssertEqual(result.effects, [.setVoice(.silenced)])
        XCTAssertEqual(result.state, InterruptionState())
    }

    func testTheEndOfTheSessionEndsAPause() {
        let result = run([(.interruptionBegan, unmuted), (.sessionEnded, nil)])
        XCTAssertEqual(result.effects, [.pausePlayback(true), .pausePlayback(false)])
        XCTAssertEqual(result.state, InterruptionState())
    }
}
