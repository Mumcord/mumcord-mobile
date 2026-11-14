// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// The real audio unit and its C callbacks in the simulator. A simulator on a build machine has
/// no microphone and often no audio device at all, so these tests prove what holds everywhere:
/// the path into the engine's library works, and opening and closing the unit never crashes and
/// always ends in a clear result. What the unit did on this machine is printed with the prefix
/// `NATIVE-AUDIO`, so the log of a test run tells whether real audio ran.
final class NativeAudioTests: XCTestCase {
    /// 10 ms at the engine's rate.
    private let block: UInt32 = 480

    override func tearDown() {
        AudioProbe.replaceMicrophone(nil)
    }

    /// Handle 0 means "no engine" to the engine's functions: a push is dropped, a pull is
    /// silence. This runs the same C code as the audio callbacks and so proves that the app is
    /// linked against the engine's audio functions.
    func testTheCallbacksPathReachesTheEnginesLibraryWithoutAUnit() {
        let pushedBefore = AudioProbe.frames(.capture)
        let pulledBefore = AudioProbe.frames(.playback)
        for _ in 0..<50 {
            AudioProbe.push(captureHandle: 0, frames: block)
            XCTAssertEqual(AudioProbe.pull(playbackHandle: 0, frames: block), 0)
        }
        XCTAssertEqual(AudioProbe.frames(.capture) - pushedBefore, 50 * UInt64(block))
        XCTAssertEqual(AudioProbe.frames(.playback) - pulledBefore, 50 * UInt64(block))
        XCTAssertEqual(AudioProbe.takePlaybackLevel(), 0)
    }

    func testTheUnitOpensAndClosesRepeatedlyForListening() {
        cycle(capture: false)
    }

    func testTheUnitOpensAndClosesRepeatedlyWithTheMicrophone() {
        AudioProbe.replaceMicrophone(.speech)
        cycle(capture: true)
    }

    func testClosingAUnitThatIsNotOpenDoesNothing() {
        let backend = NativeAudio()
        backend.close()
        backend.close()
    }

    private func cycle(capture: Bool) {
        let backend = NativeAudio()
        let session = SystemVoiceSession(log: { print("NATIVE-AUDIO session: \($0)") })
        let request = StreamRequest(playbackHandle: 0, captureHandle: 0, playback: true, capture: capture)
        for round in 1...5 {
            let began = session.begin()
            let playedBefore = AudioProbe.frames(.playback)
            let recordedBefore = AudioProbe.frames(.capture)
            let result = backend.open(request)
            if case .opened = result {
                // Long enough for a few callbacks where a device runs.
                Thread.sleep(forTimeInterval: 0.3)
            }
            backend.close()
            let played = AudioProbe.frames(.playback) - playedBefore
            let recorded = AudioProbe.frames(.capture) - recordedBefore
            print("NATIVE-AUDIO capture=\(capture) round=\(round) session=\(began) result=\(result) played=\(played) recorded=\(recorded)")

            // Closed means closed: no callback runs any more.
            let afterClose = AudioProbe.frames(.playback)
            Thread.sleep(forTimeInterval: 0.05)
            XCTAssertEqual(AudioProbe.frames(.playback), afterClose)
            if case .failed = result {
                XCTAssertEqual(played, 0)
                XCTAssertEqual(recorded, 0)
            }
            // The engine's side is silence (no engine behind handle 0), whatever was pushed.
            XCTAssertEqual(AudioProbe.takePlaybackLevel(), 0)
            if began { session.end() }
        }
    }
}
