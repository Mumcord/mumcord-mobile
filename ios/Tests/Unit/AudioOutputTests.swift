// SPDX-License-Identifier: AGPL-3.0-only

import XCTest
@testable import Mumcord

private let speaker = AudioOutput(id: OutputId.speaker, kind: .speaker, name: "")
private let earpiece = AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: "")
private let buds = AudioOutput(id: "buds", kind: .bluetooth, name: "Buds")

private let microphone = AudioPort(uid: "mic", kind: .builtInMicrophone, name: "iPhone Microphone")
private let receiver = AudioPort(uid: "receiver", kind: .builtInReceiver, name: "Receiver")
private let loudspeaker = AudioPort(uid: "speaker", kind: .builtInSpeaker, name: "Speaker")

/// The outputs of a session from what the audio session says, the rule for a call the system
/// ends, and the choice that survives a pause of the audio (FR-5.34, FR-5.35).
@MainActor
final class AudioOutputTests: XCTestCase {
    // MARK: What the phone offers

    func testAPhoneOffersItsLoudspeakerAndItsEarpieceAndPlaysOnTheEarpiece() {
        let state = outputState(inputs: [microphone], route: [receiver], hasEarpiece: true)
        XCTAssertEqual(state.available, [speaker, earpiece])
        XCTAssertEqual(state.current, earpiece)
    }

    func testATabletHasNoEarpiece() {
        let state = outputState(inputs: [microphone], route: [loudspeaker], hasEarpiece: false)
        XCTAssertEqual(state.available, [speaker])
        XCTAssertEqual(state.current, speaker)
    }

    func testABluetoothHeadsetIsFoundAmongTheMicrophonesAndNamedAsThePhoneNamesIt() {
        let port = AudioPort(uid: "buds", kind: .bluetooth, name: "Buds")
        let state = outputState(inputs: [microphone, port], route: [port], hasEarpiece: true)
        XCTAssertEqual(state.available, [speaker, earpiece, buds])
        XCTAssertEqual(state.current, buds)
    }

    func testABluetoothHeadsetWhoseSoundHasAnotherIdIsRecognisedByItsName() {
        let input = AudioPort(uid: "buds", kind: .bluetooth, name: "Buds")
        let output = AudioPort(uid: "buds-sound", kind: .bluetooth, name: "Buds")
        let state = outputState(inputs: [microphone, input], route: [output], hasEarpiece: true)
        XCTAssertEqual(state.available, [speaker, earpiece, buds])
        XCTAssertEqual(state.current, buds)
    }

    func testAWiredHeadsetTakesThePlaceOfTheEarpiece() {
        let headset = AudioPort(uid: "wired", kind: .wiredHeadset, name: "Headset Microphone")
        let headphones = AudioPort(uid: "wired-sound", kind: .headphones, name: "Headphones")
        let state = outputState(inputs: [microphone, headset], route: [headphones], hasEarpiece: true)
        let wired = AudioOutput(id: "wired", kind: .wiredHeadset, name: "")
        XCTAssertEqual(state.available, [speaker, wired])
        XCTAssertEqual(state.current, wired)
    }

    func testHeadphonesWithoutAMicrophoneShowWhileTheyPlay() {
        let headphones = AudioPort(uid: "wired-sound", kind: .headphones, name: "Headphones")
        let state = outputState(inputs: [microphone], route: [headphones], hasEarpiece: true)
        let wired = AudioOutput(id: OutputId.headphones, kind: .wiredHeadset, name: "")
        XCTAssertEqual(state.available, [speaker, wired])
        XCTAssertEqual(state.current, wired)
    }

    func testAnOutputTheAppDoesNotOfferIsNoCurrentOutput() {
        let car = AudioPort(uid: "car", kind: .other, name: "Car")
        let state = outputState(inputs: [microphone], route: [car], hasEarpiece: true)
        XCTAssertEqual(state.available, [speaker, earpiece])
        XCTAssertNil(state.current)
    }

    // MARK: A call the system ends

    func testTheSystemsEndRequestLeavesTheSessionAlsoOnAHeadset() {
        XCTAssertEqual(endRequestMeans(current: nil), .leave)
        XCTAssertEqual(endRequestMeans(current: speaker), .leave)
        XCTAssertEqual(endRequestMeans(current: buds), .leave)
    }

    // MARK: The outputs of a session

    private func makeOutputs(_ device: FakeOutputDevice) -> SessionOutputs {
        SessionOutputs(device: device)
    }

    func testWithoutASessionThereAreNoOutputs() {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let outputs = makeOutputs(device)
        XCTAssertEqual(outputs.state, OutputState())
        outputs.setSession(true)
        XCTAssertEqual(outputs.state, OutputState(available: [speaker, earpiece], current: earpiece))
        outputs.setSession(false)
        XCTAssertEqual(outputs.state, OutputState())
    }

    func testSelectingMovesTheVoiceAndAnUnknownOutputDoesNothing() {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let outputs = makeOutputs(device)
        outputs.setSession(true)
        outputs.select(OutputId.speaker)
        XCTAssertEqual(outputs.state.current, speaker)
        outputs.select("gone")
        XCTAssertEqual(device.selections, [OutputId.speaker])
    }

    func testNothingIsSelectedWithoutASession() {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let outputs = makeOutputs(device)
        outputs.select(OutputId.speaker)
        XCTAssertEqual(device.selections, [])
    }

    func testAHeadsetThatIsPluggedInIsFollowed() async {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let outputs = makeOutputs(device)
        outputs.setSession(true)
        device.set(OutputState(available: [speaker, earpiece, buds], current: buds))
        await assertEventually { outputs.state.current == buds }
    }

    func testTheChosenOutputIsChosenAgainWhenTheAudioComesBack() async {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let outputs = makeOutputs(device)
        outputs.setSession(true)
        outputs.select(OutputId.speaker)

        // A phone call stops the audio; the last known outputs stay on screen.
        device.setStopped(true)
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(outputs.state.current, speaker)

        // The phone starts again on its earpiece and has forgotten the loudspeaker.
        device.setStopped(false, restartsOn: earpiece)
        await assertEventually { device.selections == [OutputId.speaker, OutputId.speaker] }
        XCTAssertEqual(outputs.state.current, speaker)
    }
}
