// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest
@testable import Mumcord

private let speaker = AudioOutput(id: OutputId.speaker, kind: .speaker, name: "")
private let earpiece = AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: "")
private let buds = AudioOutput(id: "buds", kind: .bluetooth, name: "Buds")
private let wired = AudioOutput(id: "wired", kind: .wiredHeadset, name: "")

private func phone(_ current: AudioOutput, _ more: AudioOutput...) -> OutputState {
    OutputState(available: [speaker, earpiece] + more, current: current)
}

/// The rules for the audio output and the first-connect question (FR-5.34, AC-5.11).
@MainActor
final class VoiceOutputTests: XCTestCase {
    private let box = EngineBox()
    private var engine: FakeEngine { box.engine }

    // MARK: The rule

    func testWithoutOutputsNothingHappensAndTheMemoryStartsOver() {
        let memory = OutputMemory(headsets: ["buds"], settled: true)
        let (next, step) = outputStep(OutputState(), default: .ask, memory: memory)
        XCTAssertEqual(next, OutputMemory())
        XCTAssertEqual(step, .nothing)
    }

    func testTheFirstConnectionWithoutAHeadsetAsks() {
        let (next, step) = outputStep(phone(earpiece), default: .ask, memory: OutputMemory())
        XCTAssertEqual(step, .ask)
        XCTAssertFalse(next.settled)
    }

    func testADeviceWithoutAnEarpieceNeverAsks() {
        let tablet = OutputState(available: [speaker], current: speaker)
        let (next, step) = outputStep(tablet, default: .ask, memory: OutputMemory())
        XCTAssertEqual(step, .nothing)
        XCTAssertTrue(next.settled)
    }

    func testTheSavedDefaultIsAppliedOnce() {
        let (next, step) = outputStep(phone(earpiece), default: .speaker, memory: OutputMemory())
        XCTAssertEqual(step, .select(OutputId.speaker))
        XCTAssertTrue(next.settled)
        // What the user picks afterwards is never undone.
        let (_, later) = outputStep(phone(earpiece), default: .speaker, memory: next)
        XCTAssertEqual(later, .nothing)
    }

    func testTheSavedEarpieceNeedsNoChangeWhereThePhoneAlreadyPlaysThere() {
        let (next, step) = outputStep(phone(earpiece), default: .earpiece, memory: OutputMemory())
        XCTAssertEqual(step, .nothing)
        XCTAssertTrue(next.settled)
    }

    func testAHeadsetThatAppearsIsUsedAtOnceAndNothingIsAsked() {
        let (next, step) = outputStep(phone(earpiece, buds), default: .ask, memory: OutputMemory())
        XCTAssertEqual(step, .select("buds"))
        XCTAssertEqual(next, OutputMemory(headsets: ["buds"], settled: false))
        // The phone has moved there; the user may then pick the loudspeaker and keeps it.
        let (_, later) = outputStep(phone(speaker, buds), default: .ask, memory: next)
        XCTAssertEqual(later, .nothing)
    }

    func testTheNewestHeadsetWins() {
        let memory = OutputMemory(headsets: ["wired"], settled: false)
        let (_, step) = outputStep(phone(wired, wired, buds), default: .speaker, memory: memory)
        XCTAssertEqual(step, .select("buds"))
    }

    func testAfterAHeadsetWentAwayTheDefaultIsAppliedAgain() {
        let memory = OutputMemory(headsets: ["buds"], settled: false)
        let (next, step) = outputStep(phone(earpiece), default: .speaker, memory: memory)
        XCTAssertEqual(step, .select(OutputId.speaker))
        XCTAssertEqual(next, OutputMemory(headsets: [], settled: true))
    }

    // MARK: Against the session

    private func makeVoiceStore(_ saved: DefaultOutput) -> SessionStore {
        makeStore(
            box,
            server: mockupVoiceServer(),
            servers: [savedServer(1, "Nordlicht")],
            configure: { $0.storedMobileSettings.defaultOutput = saved }
        )
    }

    private func connect(_ store: SessionStore) async {
        await store.loadServers()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer }
    }

    func testTheSavedLoudspeakerIsChosenWithTheSession() async {
        let device = FakeOutputDevice(phone(earpiece))
        let store = makeVoiceStore(.speaker)
        let output = VoiceOutput(store: store, outputs: SessionOutputs(device: device))
        XCTAssertEqual(output.notice, OutputNotice())
        await connect(store)
        await assertEventually { output.notice.state.current == speaker }
        XCTAssertFalse(output.notice.asking)
        XCTAssertEqual(device.selections, [OutputId.speaker])
    }

    func testTheFirstConnectionAsksAndTheAnswerIsUsedAndRemembered() async {
        let device = FakeOutputDevice(phone(earpiece))
        let store = makeVoiceStore(.ask)
        let output = VoiceOutput(store: store, outputs: SessionOutputs(device: device))
        await connect(store)
        await assertEventually { output.notice.asking }
        XCTAssertEqual(device.selections, [])

        output.answer(.speaker)
        await assertEventually { output.notice == OutputNotice(state: phone(speaker), asking: false) }
        await assertEventually { self.engine.storedMobileSettings.defaultOutput == .speaker }

        // The next session does not ask again.
        await store.disconnect()
        await assertEventually { output.notice == OutputNotice() }
        device.set(phone(earpiece))
        await connect(store)
        await assertEventually { output.notice.state.current == speaker }
        XCTAssertFalse(output.notice.asking)
    }

    func testAQuestionPutAwayKeepsThePhonesChoiceAndIsAskedAgainWithTheNextSession() async {
        let device = FakeOutputDevice(phone(earpiece))
        let store = makeVoiceStore(.ask)
        let output = VoiceOutput(store: store, outputs: SessionOutputs(device: device))
        await connect(store)
        await assertEventually { output.notice.asking }

        output.dismissQuestion()
        XCTAssertEqual(output.notice, OutputNotice(state: phone(earpiece), asking: false))
        XCTAssertEqual(engine.storedMobileSettings.defaultOutput, .ask)

        await store.disconnect()
        await assertEventually { output.notice == OutputNotice() }
        await connect(store)
        await assertEventually { output.notice.asking }
    }

    func testAHeadsetThatIsConnectedDuringTheSessionTakesTheVoice() async {
        let device = FakeOutputDevice(phone(earpiece))
        let store = makeVoiceStore(.speaker)
        let output = VoiceOutput(store: store, outputs: SessionOutputs(device: device))
        await connect(store)
        await assertEventually { output.notice.state.current == speaker }

        // The phone lists the headset but still plays on the loudspeaker.
        device.set(phone(speaker, buds))
        await assertEventually { output.notice.state.current == buds }
        XCTAssertEqual(device.selections, [OutputId.speaker, "buds"])

        output.select(OutputId.earpiece)
        await assertEventually { output.notice.state.current == earpiece }
    }
}
