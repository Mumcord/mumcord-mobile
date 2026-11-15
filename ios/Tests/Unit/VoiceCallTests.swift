// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import UIKit
import XCTest

@testable import Mumcord

/// Notes the sounds it was asked to play.
final class RecordingTones: FeedbackTones, @unchecked Sendable {
    private let lock = NSLock()
    private var _played: [FeedbackTone] = []

    var played: [FeedbackTone] { lock.withLock { _played } }

    func play(_ tone: FeedbackTone) {
        lock.withLock { _played.append(tone) }
    }
}

/// The voice session and the phone, against the fake engine and a faked call service: the
/// call, its buttons, a phone call during the session, and the note about a cut-off session.
@MainActor
final class VoiceCallTests: XCTestCase {
    private let box = EngineBox()
    private let system = FakeCallSystem()
    private let fakes = PhoneFakes()
    private let suite = "VoiceCallTests-\(UUID().uuidString)"
    private var kept: [VoiceCall] = []
    private let tones = RecordingTones()

    private var engine: FakeEngine { box.engine }

    private var markers: SessionMarkerStore {
        SessionMarkerStore(defaults: UserDefaults(suiteName: suite) ?? .standard)
    }

    override func tearDown() async throws {
        UserDefaults.standard.removePersistentDomain(forName: suite)
        try await super.tearDown()
    }

    private func makeVoiceStore(
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) -> SessionStore {
        makeStore(
            box,
            server: mockupVoiceServer(),
            servers: [savedServer(1, "Nordlicht")],
            configure: configure
        )
    }

    /// A call on `store`; it is kept alive for the length of the test, as the app keeps it.
    private func makeCall(_ store: SessionStore, callSystem: Bool = true) -> VoiceCall {
        let audio = VoiceAudio(
            store: store,
            gateway: fakes.microphone,
            backend: fakes.backend,
            session: fakes.session,
            scheduler: QueueAudioScheduler(),
            log: { _ in }
        )
        let call = VoiceCall(
            store: store,
            audio: audio,
            callSystem: callSystem ? system : nil,
            prepareAudio: {},
            markers: markers,
            tones: tones,
            log: { _ in }
        )
        kept.append(call)
        return call
    }

    private func connect(_ store: SessionStore) async {
        await store.loadServers()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer }
    }

    /// A connected session whose call the system has taken and given the audio to.
    private func makeConnected(
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) async -> SessionStore {
        let store = makeVoiceStore(configure: configure)
        _ = makeCall(store)
        await connect(store)
        await assertEventually { self.system.requests.first == .start("Nordlicht · Lobby") }
        system.delegate?.callAudioChanged(active: true)
        return store
    }

    private func own(_ store: SessionStore) -> SelfVoice? {
        selfVoice(store.state)
    }

    // MARK: The call

    func testAConnectionIsShownToThePhoneAsACallAndItsEndEndsTheCall() async {
        let store = await makeConnected()
        await assertEventually { self.fakes.backend.running != nil }
        // The audio session of a call is the system's to start.
        XCTAssertTrue(fakes.session.systemActivates)

        await store.disconnect()
        await assertEventually { self.system.requests.last == .end }
    }

    func testMovingToAnotherChannelRenamesTheCall() async {
        let store = await makeConnected()
        await store.joinChannel(Mockup.gaming, password: nil)
        await assertEventually { self.system.requests.contains(.rename("Nordlicht · Gaming")) }
    }

    func testEndingTheCallOutsideTheAppLeavesTheServer() async {
        let store = await makeConnected()
        system.delegate?.callEndedBySystem(system.call)
        await assertEventually { !store.state.showsServer }
        XCTAssertEqual(engine.disconnects, 1)
    }

    func testTheSystemsMuteButtonAndTheAppsStayInStep() async {
        let store = await makeConnected()
        system.delegate?.callMutedBySystem(system.call, muted: true)
        await assertEventually { self.own(store) == SelfVoice(muted: true, deafened: false) }
        // The call follows the app's state, which is what the next press is compared with.
        await assertEventually { self.system.requests.last == .setMuted(true) }

        system.delegate?.callMutedBySystem(system.call, muted: false)
        await assertEventually { self.own(store) == SelfVoice(muted: false, deafened: false) }
        await assertEventually { self.system.requests.last == .setMuted(false) }

        // Muting in the app is told to the system.
        await store.setSelfMute(true)
        await assertEventually { self.system.requests.last == .setMuted(true) }
    }

    func testAHeadsetsMuteIsAnsweredWithASoundAndMutingOnScreenIsNot() async {
        let store = await makeConnected()
        system.delegate?.callMutedBySystem(system.call, muted: true)
        await assertEventually { self.own(store)?.muted == true }
        await assertEventually { self.system.requests.last == .setMuted(true) }
        system.delegate?.callMutedBySystem(system.call, muted: false)
        await assertEventually { self.own(store)?.muted == false }
        XCTAssertEqual(tones.played, [.muted, .unmuted])

        // The system reports back what the app told it; that is no press of a button.
        await store.setSelfMute(true)
        await assertEventually { self.system.requests.last == .setMuted(true) }
        system.delegate?.callMutedBySystem(system.call, muted: true)
        XCTAssertEqual(tones.played, [.muted, .unmuted])
    }

    func testInPushToTalkModeTheSystemsMuteButtonNeverOpensTheMicrophone() async {
        let store = await makeConnected { $0.savedTalkMode = .pushToTalk }
        await assertEventually { store.state.talkMode == .pushToTalk }
        system.delegate?.callMutedBySystem(system.call, muted: true)
        await assertEventually { self.own(store)?.muted == true }
        await assertEventually { self.system.requests.last == .setMuted(true) }
        system.delegate?.callMutedBySystem(system.call, muted: false)
        await assertEventually { self.own(store)?.muted == false }
        // Talking takes the hold button on screen; nothing here presses it.
        XCTAssertEqual(engine.pushToTalkCalls, [])
    }

    // MARK: Phone calls and interruptions

    func testAnAcceptedPhoneCallMutesAndDeafensAndThePreviousStateComesBack() async {
        let store = await makeConnected()
        system.delegate?.callHeldBySystem(system.call, held: true)
        system.delegate?.callAudioChanged(active: false)
        await assertEventually { self.own(store) == .silenced }
        await assertEventually { self.fakes.backend.running == nil }
        // The user stays in the channel.
        XCTAssertTrue(store.state.showsServer)

        system.delegate?.callHeldBySystem(system.call, held: false)
        system.delegate?.callAudioChanged(active: true)
        await assertEventually { self.own(store) == SelfVoice(muted: false, deafened: false) }
        await assertEventually { self.fakes.backend.running != nil }
    }

    func testAnInterruptionPausesTheAudioOnThisPhoneOnly() async {
        let store = makeVoiceStore()
        let call = makeCall(store, callSystem: false)
        await connect(store)
        await assertEventually { self.fakes.backend.running != nil }

        call.onAudioSignal(.interruptionBegan)
        await assertEventually { self.fakes.backend.running == nil }
        XCTAssertEqual(own(store), SelfVoice(muted: false, deafened: false))

        call.onAudioSignal(.interruptionEnded(shouldResume: false))
        await assertEventually { self.fakes.backend.running != nil }
    }

    func testAnInterruptionWhoseEndIsNeverReportedIsOverWhenTheAppIsBackOnScreen() async {
        let store = makeVoiceStore()
        let call = makeCall(store, callSystem: false)
        await connect(store)
        await assertEventually { self.fakes.backend.running != nil }
        call.onAudioSignal(.interruptionBegan)
        await assertEventually { self.fakes.backend.running == nil }

        call.onAppActive()
        await assertEventually { self.fakes.backend.running != nil }
    }

    func testWithoutACallServiceVoiceRunsOnTheAppsOwnAudioSession() async {
        let store = makeVoiceStore()
        _ = makeCall(store, callSystem: false)
        await connect(store)
        await assertEventually { self.fakes.backend.running != nil }
        XCTAssertFalse(fakes.session.systemActivates)
        XCTAssertEqual(system.requests, [])
    }

    func testACallTheSystemRefusesLeavesVoiceRunning() async {
        system.accepts = false
        let store = makeVoiceStore()
        _ = makeCall(store)
        await connect(store)
        await assertEventually { self.system.requests == [.start("Nordlicht · Lobby")] }
        await assertEventually { self.fakes.backend.running != nil }
        XCTAssertFalse(fakes.session.systemActivates)
    }

    // MARK: A session that was cut off (FR-5.37)

    func testARunningSessionIsNotedAndAnOrderlyEndRemovesTheNote() async {
        let store = makeVoiceStore()
        _ = makeCall(store, callSystem: false)
        await connect(store)
        await assertEventually { self.markers.read() == SessionMarker(serverId: 1, serverName: "Nordlicht") }

        await store.disconnect()
        await assertEventually { self.markers.read() == nil }
    }

    func testTheNextStartAfterACutOffSessionGivesTheNoticeOnce() {
        markers.mark(SessionMarker(serverId: 1, serverName: "Nordlicht"))
        let call = makeCall(makeVoiceStore(), callSystem: false)
        XCTAssertEqual(call.takeSessionEndedNotice(), SessionEndedNotice(serverId: 1, serverName: "Nordlicht"))
        XCTAssertNil(call.takeSessionEndedNotice())
        // Nothing is left for the start after this one.
        XCTAssertNil(markers.read())
    }

    func testAStartAfterAnOrderlyEndGivesNoNotice() {
        let call = makeCall(makeVoiceStore(), callSystem: false)
        XCTAssertNil(call.takeSessionEndedNotice())
    }

    func testClosingTheAppByHandRemovesTheNote() async {
        let store = makeVoiceStore()
        _ = makeCall(store, callSystem: false)
        await connect(store)
        await assertEventually { self.markers.read() != nil }

        // What the system sends to a running app that the user removes from the app switcher.
        NotificationCenter.default.post(name: UIApplication.willTerminateNotification, object: nil)
        XCTAssertNil(markers.read())
    }

    func testWhileAConnectionIsBeingMadeTheNoteIsKept() {
        var state = SessionState()
        state.phase = .connecting
        XCTAssertEqual(markerStep(state), .keep)
        state.phase = .idle
        XCTAssertEqual(markerStep(state), .clear)
        state.phase = .failed(.serverUnreachable)
        XCTAssertEqual(markerStep(state), .clear)
    }
}
