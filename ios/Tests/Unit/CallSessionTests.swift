// SPDX-License-Identifier: AGPL-3.0-only

import XCTest

@testable import Mumcord

/// A call service that only notes what it was asked to do.
final class FakeCallSystem: CallSystem {
    enum Request: Equatable {
        case start(String)
        case rename(String)
        case setMuted(Bool)
        case unhold
        case end
    }

    weak var delegate: CallSystemDelegate?
    private(set) var requests: [Request] = []
    /// The calls that were asked for, in order.
    private(set) var calls: [UUID] = []
    /// Whether the system takes a call.
    var accepts = true
    /// The system answers a request to start only when the test calls `answer`.
    var answersLater = false
    private var pending: ((Bool) -> Void)?

    var call: UUID {
        guard let call = calls.last else { preconditionFailure("no call was asked for yet") }
        return call
    }

    func answer(_ accepted: Bool) {
        let completion = pending
        pending = nil
        completion?(accepted)
    }

    func start(_ call: UUID, name: String, completion: @escaping (Bool) -> Void) {
        requests.append(.start(name))
        calls.append(call)
        if answersLater {
            pending = completion
        } else {
            completion(accepts)
        }
    }

    func rename(_ call: UUID, name: String) {
        requests.append(.rename(name))
    }

    func setMuted(_ call: UUID, muted: Bool) {
        requests.append(.setMuted(muted))
    }

    func unhold(_ call: UUID) {
        requests.append(.unhold)
    }

    func end(_ call: UUID) {
        requests.append(.end)
    }
}

/// A voice session as a call of the phone (FR-5.31, FR-5.32), against a faked call service.
final class CallSessionTests: XCTestCase {
    private let system = FakeCallSystem()
    /// The sessions of a test; the call service does not keep its session alive.
    private var sessions: [CallSession] = []
    private var prepared = 0
    private var muted: [Bool] = []
    private var leaves = 0
    private var interruptions: [InterruptionEvent] = []
    private var activations: [AudioActivation] = []
    private var timers: [(seconds: TimeInterval, work: () -> Void)] = []
    private var log: [String] = []

    private let lobby = CallInfo(serverName: "Nordlicht", channelName: "Lobby", muted: false, deafened: false)

    private func makeSession(callSystem: Bool = true) -> CallSession {
        let session = CallSession(
            system: callSystem ? system : nil,
            prepareAudio: { [unowned self] in self.prepared += 1 },
            onMuted: { [unowned self] in self.muted.append($0) },
            onLeave: { [unowned self] in self.leaves += 1 },
            onInterruption: { [unowned self] in self.interruptions.append($0) },
            onActivation: { [unowned self] in self.activations.append($0) },
            after: { [unowned self] seconds, work in self.timers.append((seconds, work)) },
            log: { [unowned self] in self.log.append($0) }
        )
        sessions.append(session)
        return session
    }

    /// A session whose call the system has taken and given the audio to.
    private func makeRunning() -> CallSession {
        let session = makeSession()
        session.follow(lobby)
        system.delegate?.callAudioChanged(active: true)
        return session
    }

    func testASessionBecomesAnOutgoingCallNamedAfterServerAndChannel() {
        let session = makeSession()
        session.follow(lobby)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby")])
        // The audio session is set up but left for the system to start.
        XCTAssertEqual(prepared, 1)
        XCTAssertEqual(activations, [.callWaiting])

        system.delegate?.callAudioChanged(active: true)
        XCTAssertEqual(activations, [.callWaiting, .callActive])
    }

    func testWithoutAChannelTheCallIsNamedAfterTheServer() {
        var info = lobby
        info.channelName = nil
        XCTAssertEqual(info.title, "Nordlicht")
        info.channelName = ""
        XCTAssertEqual(info.title, "Nordlicht")
    }

    func testAChannelChangeRenamesTheCall() {
        let session = makeRunning()
        var moved = lobby
        moved.channelName = "Gaming"
        session.follow(moved)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby"), .rename("Nordlicht · Gaming")])
    }

    func testTheEndOfTheSessionEndsTheCall() {
        let session = makeRunning()
        session.follow(nil)
        XCTAssertEqual(system.requests.last, .end)
        XCTAssertEqual(activations.last, .own)
        XCTAssertEqual(interruptions, [.sessionEnded])
        XCTAssertEqual(leaves, 0)
    }

    func testEverySessionIsACallOfItsOwn() {
        let session = makeRunning()
        session.follow(nil)
        session.follow(lobby)
        XCTAssertEqual(system.calls.count, 2)
        XCTAssertNotEqual(system.calls[0], system.calls[1])
    }

    func testEndingTheCallOnTheSystemsCallScreenLeavesTheServer() {
        let session = makeRunning()
        system.delegate?.callEndedBySystem(system.call)
        XCTAssertEqual(leaves, 1)
        XCTAssertEqual(activations.last, .own)

        // The session ends as asked; its call is gone already.
        session.follow(nil)
        XCTAssertFalse(system.requests.contains(.end))
    }

    func testTheEndOfAnotherCallIsNotTheSessionsEnd() {
        _ = makeRunning()
        system.delegate?.callEndedBySystem(UUID())
        XCTAssertEqual(leaves, 0)
    }

    func testTheSystemsMuteButtonMutesAndUnmutesInTheApp() {
        let session = makeRunning()
        system.delegate?.callMutedBySystem(system.call, muted: true)
        XCTAssertEqual(muted, [true])

        var info = lobby
        info.muted = true
        session.follow(info)
        system.delegate?.callMutedBySystem(system.call, muted: false)
        XCTAssertEqual(muted, [true, false])
    }

    func testMutingInTheAppIsToldToTheSystemAndItsEchoIsIgnored() {
        let session = makeRunning()
        var info = lobby
        info.muted = true
        session.follow(info)
        XCTAssertEqual(system.requests.last, .setMuted(true))
        // The system reports the change back; the app is in that state already.
        system.delegate?.callMutedBySystem(system.call, muted: true)
        XCTAssertEqual(muted, [])

        session.follow(lobby)
        XCTAssertEqual(system.requests.last, .setMuted(false))
    }

    func testASessionThatStartsMutedTellsTheSystem() {
        let session = makeSession()
        var info = lobby
        info.muted = true
        session.follow(info)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby"), .setMuted(true)])
    }

    func testAHeldCallIsReportedAsAPhoneCallAndLosesTheAudio() {
        _ = makeRunning()
        system.delegate?.callHeldBySystem(system.call, held: true)
        system.delegate?.callAudioChanged(active: false)
        XCTAssertEqual(interruptions, [.callHeld(true)])
        XCTAssertEqual(activations.last, .callWaiting)

        system.delegate?.callHeldBySystem(system.call, held: false)
        system.delegate?.callAudioChanged(active: true)
        XCTAssertEqual(interruptions, [.callHeld(true), .callHeld(false)])
        XCTAssertEqual(activations.last, .callActive)
    }

    func testACallLeftOnHoldIsResumedWhenThePhoneCallIsOver() {
        _ = makeRunning()
        system.delegate?.otherCallsEnded()
        XCTAssertFalse(system.requests.contains(.unhold))

        system.delegate?.callHeldBySystem(system.call, held: true)
        system.delegate?.otherCallsEnded()
        XCTAssertEqual(system.requests.last, .unhold)
    }

    func testACallTheSystemRefusesLeavesTheSessionWithItsOwnAudio() {
        system.accepts = false
        let session = makeSession()
        session.follow(lobby)
        XCTAssertEqual(activations, [.callWaiting, .own])
        XCTAssertEqual(log.count, 1)

        // The session goes on without a call: nothing more is asked of the system.
        var moved = lobby
        moved.channelName = "Gaming"
        moved.muted = true
        session.follow(moved)
        session.follow(nil)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby")])
        XCTAssertEqual(interruptions, [.sessionEnded])
        XCTAssertEqual(activations, [.callWaiting, .own])
    }

    func testWithoutACallServiceTheSessionKeepsItsOwnAudio() {
        let session = makeSession(callSystem: false)
        session.follow(lobby)
        session.follow(nil)
        XCTAssertEqual(system.requests, [])
        XCTAssertEqual(prepared, 0)
        XCTAssertEqual(activations, [])
        XCTAssertEqual(interruptions, [.sessionEnded])
    }

    func testWhatChangedWhileTheSystemWasThinkingIsToldOnceItTookTheCall() {
        system.answersLater = true
        let session = makeSession()
        session.follow(lobby)
        var moved = lobby
        moved.channelName = "Gaming"
        moved.muted = true
        session.follow(moved)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby")])

        system.answer(true)
        XCTAssertEqual(
            system.requests,
            [.start("Nordlicht · Lobby"), .rename("Nordlicht · Gaming"), .setMuted(true)]
        )
    }

    func testASessionThatEndsWhileTheSystemIsThinkingLeavesNoCallBehind() {
        system.answersLater = true
        let session = makeSession()
        session.follow(lobby)
        session.follow(nil)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby")])
        XCTAssertEqual(activations.last, .own)

        system.answer(true)
        XCTAssertEqual(system.requests, [.start("Nordlicht · Lobby"), .end])
        XCTAssertEqual(activations.last, .own)
    }

    func testACallWhoseAudioTheSystemNeverStartsGetsTheAppsOwnAudio() {
        let session = makeSession()
        session.follow(lobby)
        XCTAssertEqual(timers.map(\.seconds), [CallSession.activationTimeout])
        timers[0].work()
        XCTAssertEqual(activations, [.callWaiting, .own])
        XCTAssertEqual(log.count, 1)
    }

    func testACallWhoseAudioTheSystemStartedIsLeftAlone() {
        _ = makeRunning()
        // Held meanwhile: the audio is away on purpose.
        system.delegate?.callHeldBySystem(system.call, held: true)
        system.delegate?.callAudioChanged(active: false)
        timers[0].work()
        XCTAssertEqual(activations, [.callWaiting, .callActive, .callWaiting])
        XCTAssertEqual(log, [])
    }

    func testWhenTheSystemDropsItsCallsTheSessionGoesOnWithItsOwnAudio() {
        let session = makeRunning()
        system.delegate?.callHeldBySystem(system.call, held: true)
        system.delegate?.callSystemReset()
        XCTAssertEqual(activations.last, .own)
        // What the phone call had done is undone.
        XCTAssertEqual(interruptions, [.callHeld(true), .callHeld(false)])
        XCTAssertEqual(leaves, 0)

        session.follow(nil)
        XCTAssertFalse(system.requests.contains(.end))
    }
}
