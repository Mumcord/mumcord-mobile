// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// Runs work at once and keeps delayed work until the test lets the time pass.
private final class ManualScheduler: AudioScheduler {
    private final class Timer: AudioTimer {
        let seconds: TimeInterval
        let work: () -> Void
        var cancelled = false

        init(seconds: TimeInterval, work: @escaping () -> Void) {
            self.seconds = seconds
            self.work = work
        }

        func cancel() {
            cancelled = true
        }
    }

    private var timers: [Timer] = []

    /// The waits that are still pending, in the order they were asked for.
    var pending: [TimeInterval] { timers.filter { !$0.cancelled }.map(\.seconds) }

    func run(_ work: @escaping () -> Void) {
        work()
    }

    func after(_ seconds: TimeInterval, _ work: @escaping () -> Void) -> AudioTimer {
        let timer = Timer(seconds: seconds, work: work)
        timers.append(timer)
        return timer
    }

    /// Lets the oldest pending wait be over.
    func advance() {
        timers.removeAll(where: \.cancelled)
        guard !timers.isEmpty else { return }
        timers.removeFirst().work()
    }
}

/// The speaker and the microphone follow what the engine wants, the permission and the phone.
final class AudioControllerTests: XCTestCase {
    private let backend = FakeStreamBackend()
    private let session = FakeVoiceSession()
    private let scheduler = ManualScheduler()
    private var granted = true
    private var states: [AudioState] = []
    private var log: [String] = []

    private var state: AudioState { states.last ?? AudioState() }

    private func makeController(endpoints: Bool = true) -> AudioController {
        let controller = AudioController(
            backend: backend,
            session: session,
            microphoneGranted: { [unowned self] in self.granted },
            scheduler: scheduler,
            log: { [unowned self] in self.log.append($0) },
            onState: { [unowned self] in self.states.append($0) }
        )
        if endpoints { controller.setEndpoints(capture: 7, playback: 9) }
        return controller
    }

    private func request(playback: Bool, capture: Bool) -> StreamRequest {
        StreamRequest(playbackHandle: 9, captureHandle: 7, playback: playback, capture: capture)
    }

    func testNothingRunsUntilTheEngineWantsIt() {
        _ = makeController()
        XCTAssertEqual(backend.opens, [])
        XCTAssertEqual(session.begins, 0)
        XCTAssertEqual(state, AudioState())
    }

    func testWhatIsWantedOpensWithTheEnginesHandlesAfterTheSessionBegan() {
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        XCTAssertEqual(session.begins, 1)
        XCTAssertEqual(backend.opens, [request(playback: true, capture: true)])
        XCTAssertEqual(state, AudioState(playback: .running, capture: .running))
    }

    func testNothingOpensWithoutTheEnginesHandles() {
        let controller = makeController(endpoints: false)
        controller.setWanted(playback: true, capture: true)
        XCTAssertEqual(backend.opens, [])
        XCTAssertEqual(session.begins, 0)

        controller.setEndpoints(capture: 7, playback: 9)
        XCTAssertEqual(backend.opens, [request(playback: true, capture: true)])
    }

    func testListeningOnlyOpensTheUnitWithoutItsMicrophone() {
        let controller = makeController()
        controller.setWanted(playback: true, capture: false)
        XCTAssertEqual(backend.running, request(playback: true, capture: false))
        XCTAssertEqual(state, AudioState(playback: .running, capture: .stopped))
    }

    func testTheDirectionsFollowTheEngineIndependently() {
        let controller = makeController()
        controller.setWanted(playback: true, capture: false)
        controller.setWanted(playback: true, capture: true)
        XCTAssertEqual(backend.running, request(playback: true, capture: true))
        XCTAssertEqual(state, AudioState(playback: .running, capture: .running))

        // Muted: the microphone is given back, listening goes on.
        controller.setWanted(playback: true, capture: false)
        XCTAssertEqual(backend.running, request(playback: true, capture: false))
        XCTAssertEqual(state, AudioState(playback: .running, capture: .stopped))
        // One session for all of it.
        XCTAssertEqual(session.begins, 1)
        XCTAssertEqual(session.ends, 0)
    }

    func testTheSessionEndsAfterTheUnitClosed() {
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        controller.setWanted(playback: false, capture: false)
        XCTAssertNil(backend.running)
        XCTAssertEqual(session.ends, 1)
        XCTAssertEqual(state, AudioState())

        controller.setWanted(playback: true, capture: false)
        XCTAssertEqual(session.begins, 2)
    }

    func testWithoutPermissionTheMicrophoneStaysClosedAndListeningWorks() {
        granted = false
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        XCTAssertEqual(backend.opens, [request(playback: true, capture: false)])
        XCTAssertEqual(state, AudioState(playback: .running, capture: .noPermission))
    }

    func testWithoutPermissionAndNothingToPlayNothingRuns() {
        granted = false
        let controller = makeController()
        controller.setWanted(playback: false, capture: true)
        XCTAssertEqual(backend.opens, [])
        XCTAssertEqual(session.begins, 0)
        XCTAssertEqual(state, AudioState(playback: .stopped, capture: .noPermission))
    }

    func testTheMicrophoneStartsOnceThePermissionIsGiven() {
        granted = false
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        granted = true
        controller.refresh()
        XCTAssertEqual(backend.running, request(playback: true, capture: true))
        XCTAssertEqual(state, AudioState(playback: .running, capture: .running))
    }

    func testAMicrophoneThatDoesNotWorkFailsAfterAFewTriesWithoutStoppingListening() {
        backend.microphoneFails = true
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        XCTAssertEqual(scheduler.pending, [0.25])
        scheduler.advance()
        XCTAssertEqual(scheduler.pending, [1])
        scheduler.advance()
        XCTAssertEqual(scheduler.pending, [2])
        scheduler.advance()
        XCTAssertEqual(scheduler.pending, [])
        // Four tries with the microphone, then the unit without it.
        XCTAssertEqual(backend.opens.filter(\.capture).count, 4)
        XCTAssertEqual(backend.running, request(playback: true, capture: false))
        XCTAssertEqual(state, AudioState(playback: .running, capture: .failed(.microphoneUnavailable)))
        XCTAssertEqual(log.count, 4)
    }

    func testAUnitThatFailsAtFirstRunsWhenTheDeviceIsBack() {
        backend.failures = 2
        let controller = makeController()
        controller.setWanted(playback: true, capture: false)
        XCTAssertEqual(state, AudioState())
        scheduler.advance()
        scheduler.advance()
        XCTAssertEqual(backend.opens.count, 3)
        XCTAssertEqual(scheduler.pending, [])
        XCTAssertEqual(state, AudioState(playback: .running, capture: .stopped))
    }

    func testAUnitThatNeverOpensCountsAsFailedAndGivesTheSessionBack() {
        backend.failures = 10
        let controller = makeController()
        controller.setWanted(playback: true, capture: false)
        scheduler.advance()
        scheduler.advance()
        scheduler.advance()
        XCTAssertEqual(backend.opens.count, 4)
        XCTAssertEqual(scheduler.pending, [])
        XCTAssertEqual(state, AudioState(playback: .failed(.speakerUnavailable), capture: .stopped))
        XCTAssertEqual(session.ends, 1)

        // Wanted anew, it is tried again.
        backend.failures = 0
        controller.setWanted(playback: false, capture: false)
        controller.setWanted(playback: true, capture: false)
        XCTAssertEqual(state, AudioState(playback: .running, capture: .stopped))
    }

    func testStoppingCancelsAPendingRetry() {
        backend.failures = 1
        let controller = makeController()
        controller.setWanted(playback: true, capture: false)
        XCTAssertEqual(scheduler.pending, [0.25])
        controller.setWanted(playback: false, capture: false)
        XCTAssertEqual(scheduler.pending, [])
        scheduler.advance()
        XCTAssertEqual(backend.opens.count, 1)
    }

    func testAnInterruptionStopsTheUnitAndItsEndStartsItAgain() throws {
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        let phone = try XCTUnwrap(session.onEvent)

        phone(.interruptionBegan)
        XCTAssertNil(backend.running)
        XCTAssertEqual(state, AudioState())
        // The phone took the session itself; it is not given back a second time.
        XCTAssertEqual(session.ends, 0)

        phone(.interruptionEnded)
        XCTAssertEqual(session.begins, 2)
        XCTAssertEqual(backend.running, request(playback: true, capture: true))
        XCTAssertEqual(state, AudioState(playback: .running, capture: .running))
    }

    func testAnInterruptionWhoseEndIsNeverReportedIsOverWhenTheAppIsBackOnScreen() throws {
        let controller = makeController()
        controller.setWanted(playback: true, capture: false)
        try XCTUnwrap(session.onEvent)(.interruptionBegan)
        controller.refresh()
        XCTAssertEqual(backend.running, request(playback: true, capture: false))
    }

    func testAfterAResetOfTheAudioServiceEverythingIsSetUpAgain() throws {
        let controller = makeController()
        controller.setWanted(playback: true, capture: true)
        try XCTUnwrap(session.onEvent)(.servicesReset)
        XCTAssertEqual(session.begins, 2)
        XCTAssertEqual(backend.opens.count, 2)
        XCTAssertEqual(state, AudioState(playback: .running, capture: .running))
    }
}
