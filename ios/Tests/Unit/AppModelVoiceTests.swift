// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Voice on the model, against the fake engine: the voice view, mute and deafen, the talk mode,
/// hold to talk, whisper and shout, the microphone question and the phone's audio.
@MainActor
final class AppModelVoiceTests: XCTestCase {
    private let box = EngineBox()

    private var engine: FakeEngine { box.engine }

    /// A model that is already connected to the server of the voice mockup.
    private func makeConnected(
        _ fakes: PhoneFakes = PhoneFakes(),
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) async -> AppModel {
        let store = makeStore(
            box,
            server: mockupVoiceServer(),
            servers: [savedServer(1, "Nordlicht")],
            configure: configure
        )
        let model = fakes.makeModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        return model
    }

    private func voice(_ model: AppModel) -> VoiceUiState? {
        model.uiState.connected?.voice
    }

    private func own(_ model: AppModel) -> Person? {
        model.uiState.session.server?.people[Mockup.lea]
    }

    // MARK: Voice view, mute, deafen, talk mode

    func testTheSavedTalkModeDrivesTheVoiceView() async {
        let model = await makeConnected { $0.savedTalkMode = .pushToTalk }
        await assertEventually { self.voice(model)?.talkMode == .pushToTalk }
    }

    func testTheVoiceViewOpensAndCloses() async {
        let model = await makeConnected()
        XCTAssertEqual(voice(model)?.open, false)
        model.openVoice()
        XCTAssertEqual(voice(model)?.open, true)
        model.closeVoice()
        XCTAssertEqual(voice(model)?.open, false)
    }

    func testMuteAndDeafenAreSentToTheEngineAndComeBackAsTheOwnState() async {
        let model = await makeConnected()
        model.toggleMute()
        await assertEventually { self.voice(model)?.mute == .muted }
        model.toggleMute()
        await assertEventually { self.voice(model)?.mute == .unmuted }

        model.toggleDeafen()
        await assertEventually { self.voice(model)?.deafened == true }
        XCTAssertEqual(voice(model)?.mute, .muted)
        // Unmuting also ends the deafen.
        model.toggleMute()
        await assertEventually { self.voice(model)?.deafened == false }
        XCTAssertEqual(voice(model)?.mute, .unmuted)
    }

    func testSwitchingTheTalkModeIsSavedAtOnceAndLetsGoOfTheButton() async {
        let model = await makeConnected { $0.savedTalkMode = .pushToTalk }
        await assertEventually { self.voice(model)?.talkMode == .pushToTalk }
        model.setTalkHeld(true)
        model.switchTalkMode(.voiceActivity)
        XCTAssertEqual(engine.pushToTalkCalls, [true, false])
        await assertEventually { self.voice(model)?.talkMode == .voiceActivity }
        XCTAssertEqual(engine.currentTalkMode, .voiceActivity)
        XCTAssertEqual(voice(model)?.talkHeld, false)
    }

    func testATalkModeThatCannotBeSavedStaysAsItWasAndIsReported() async {
        let model = await makeConnected()
        engine.talkModeFailure = .Io(detail: "disk full")
        model.switchTalkMode(.pushToTalk)
        await assertEventually { model.uiState.connected?.denied != nil }
        XCTAssertEqual(voice(model)?.talkMode, .voiceActivity)
    }

    // MARK: Hold to talk

    func testHoldingTheButtonTalksAndLettingGoStops() async {
        let model = await makeConnected { $0.savedTalkMode = .pushToTalk }
        await assertEventually { self.voice(model)?.talkMode == .pushToTalk }
        model.setTalkHeld(true)
        XCTAssertEqual(voice(model)?.talkHeld, true)
        // A second "down" without an "up" in between is not sent twice.
        model.setTalkHeld(true)
        model.setTalkHeld(false)
        XCTAssertEqual(voice(model)?.talkHeld, false)
        XCTAssertEqual(engine.pushToTalkCalls, [true, false])
    }

    func testInVoiceActivityModeTheButtonDoesNothing() async {
        let model = await makeConnected()
        model.setTalkHeld(true)
        XCTAssertEqual(voice(model)?.talkHeld, false)
        XCTAssertEqual(engine.pushToTalkCalls, [])
    }

    func testLettingGoWithoutHoldingSendsNothing() async {
        let model = await makeConnected { $0.savedTalkMode = .pushToTalk }
        model.setTalkHeld(false)
        model.releaseHolds()
        XCTAssertEqual(engine.pushToTalkCalls, [])
    }

    func testTheButtonIsLetGoWhenTheAppLeavesTheScreenTheViewClosesOrTheConnectionEnds() async {
        let model = await makeConnected { $0.savedTalkMode = .pushToTalk }
        await assertEventually { self.voice(model)?.talkMode == .pushToTalk }

        model.setTalkHeld(true)
        model.releaseHolds()
        XCTAssertEqual(engine.pushToTalkCalls, [true, false])

        model.openVoice()
        model.setTalkHeld(true)
        model.closeVoice()
        XCTAssertEqual(engine.pushToTalkCalls, [true, false, true, false])

        model.setTalkHeld(true)
        model.disconnect()
        await assertEventually { model.uiState.connected == nil }
        XCTAssertEqual(engine.pushToTalkCalls, [true, false, true, false, true, false])
    }

    // MARK: Whisper and shout

    func testWhisperOpensTheBarClosesTheSheetAndOnlySendsWhileHeld() async {
        let model = await makeConnected()
        model.openPerson(Mockup.jonas)
        model.openWhisper(.person(session: Mockup.jonas))
        XCTAssertNil(model.uiState.connected?.sheet)
        XCTAssertEqual(voice(model)?.whisper, WhisperBarState(name: "Jonas", shout: false, held: false))
        XCTAssertEqual(engine.whisperStarts, [])

        model.setWhisperHeld(true)
        XCTAssertEqual(voice(model)?.whisper?.held, true)
        await assertEventually { self.engine.whisperStarts == [.person(session: Mockup.jonas)] }
        model.setWhisperHeld(false)
        XCTAssertEqual(voice(model)?.whisper?.held, false)
        XCTAssertEqual(engine.whisperStops, 1)
        // The bar stays for the next whisper.
        XCTAssertNotNil(voice(model)?.whisper)
    }

    func testShoutGoesToTheChannel() async {
        let model = await makeConnected()
        model.openChannelMenu(Mockup.gaming)
        model.openWhisper(.channel(channelId: Mockup.gaming))
        XCTAssertNil(model.uiState.connected?.sheet)
        XCTAssertEqual(voice(model)?.whisper, WhisperBarState(name: "Gaming", shout: true, held: false))
        model.setWhisperHeld(true)
        await assertEventually { self.engine.whisperStarts == [.channel(channelId: Mockup.gaming)] }
    }

    func testAReleaseThatComesBeforeTheEngineStartedStillEndsTheWhisper() async {
        let model = await makeConnected()
        model.openWhisper(.person(session: Mockup.jonas))
        model.setWhisperHeld(true)
        // Let go at once: the engine has not been asked yet.
        model.setWhisperHeld(false)
        await assertEventually { self.engine.whisperStarts.count == 1 }
        await assertEventually { self.engine.whisperStops == 2 }
        XCTAssertEqual(voice(model)?.whisper?.held, false)
    }

    func testClosingTheBarEndsTheWhisper() async {
        let model = await makeConnected()
        model.openWhisper(.person(session: Mockup.jonas))
        model.setWhisperHeld(true)
        await assertEventually { self.engine.whisperStarts.count == 1 }
        model.closeWhisper()
        XCTAssertNil(voice(model)?.whisper)
        XCTAssertEqual(engine.whisperStops, 1)
    }

    func testARefusedWhisperIsShownInTheServersWordsAndTheButtonIsNotHeld() async {
        let model = await makeConnected()
        engine.whisperFailure = .PermissionDenied(detail: "Whispering is not allowed here.")
        model.openWhisper(.person(session: Mockup.jonas))
        model.setWhisperHeld(true)
        await assertEventually {
            model.uiState.connected?.denied == DeniedBanner(channelName: nil, detail: "Whispering is not allowed here.")
        }
        await assertEventually { self.voice(model)?.whisper?.held == false }
        XCTAssertEqual(engine.whisperStarts, [])
    }

    func testTheBarClosesAndTheWhisperEndsWhenThePersonLeavesTheServer() async {
        let model = await makeConnected()
        model.openWhisper(.person(session: Mockup.jonas))
        model.setWhisperHeld(true)
        await assertEventually { self.engine.whisperStarts.count == 1 }
        engine.emit(.personRemoved(session: Mockup.jonas))
        await assertEventually { model.connectedLocal.whisperTarget == nil }
        XCTAssertNil(voice(model)?.whisper)
        XCTAssertEqual(engine.whisperStops, 1)
    }

    func testTheWhisperIsLetGoWhenTheAppLeavesTheScreenButTheBarStays() async {
        let model = await makeConnected()
        model.openWhisper(.person(session: Mockup.jonas))
        model.setWhisperHeld(true)
        await assertEventually { self.engine.whisperStarts.count == 1 }
        model.releaseHolds()
        XCTAssertEqual(engine.whisperStops, 1)
        XCTAssertEqual(voice(model)?.whisper, WhisperBarState(name: "Jonas", shout: false, held: false))
    }

    func testTheMicrophoneLevelHasItsOwnPathAndDoesNotTouchTheScreenState() async {
        let model = await makeConnected()
        let session = model.uiState.session
        let connected = model.uiState.connected
        engine.emit(.inputLevelChanged(percent: 60))
        await assertEventually { model.inputLevel.percent == 60 }
        XCTAssertEqual(model.uiState.session, session)
        XCTAssertEqual(model.uiState.connected, connected)
    }

    // MARK: The phone's audio and the microphone question

    func testTheUnitFollowsWhatTheEngineWants() async {
        let fakes = PhoneFakes()
        let model = await makeConnected(fakes)
        await assertEventually {
            fakes.backend.running == StreamRequest(playbackHandle: 0, captureHandle: 0, playback: true, capture: true)
        }
        XCTAssertEqual(model.uiState.connected?.microphone, MicrophoneNotice.none)

        engine.emit(.audioWantedChanged(audio: AudioWanted(capture: false, playback: true)))
        await assertEventually { fakes.backend.running?.capture == false }

        model.disconnect()
        await assertEventually { fakes.backend.running == nil && fakes.session.ends == 1 }
    }

    func testWithoutPermissionItIsExplainedWhenTalkingIsFirstPossibleAndListeningWorks() async {
        let fakes = PhoneFakes(microphone: FakeMicrophoneGateway(permission: .undetermined))
        let model = await makeConnected(fakes)
        await assertEventually { model.uiState.connected?.sheet == .microphone }
        await assertEventually { fakes.backend.running?.playback == true }
        XCTAssertEqual(fakes.backend.running?.capture, false)
        XCTAssertEqual(fakes.microphone.requests, 0)
    }

    func testAllowingItStartsTheMicrophone() async {
        let fakes = PhoneFakes(microphone: FakeMicrophoneGateway(permission: .undetermined))
        let model = await makeConnected(fakes)
        await assertEventually { model.uiState.connected?.sheet == .microphone }
        model.requestMicrophone()
        await assertEventually { fakes.backend.running?.capture == true }
        await assertEventually { model.uiState.connected?.sheet == nil }
        XCTAssertEqual(fakes.microphone.requests, 1)
        XCTAssertEqual(model.uiState.connected?.microphone, MicrophoneNotice.none)
    }

    func testNotNowKeepsListeningAndLeavesACalmNoticeThatAsksWhenTapped() async {
        let fakes = PhoneFakes(microphone: FakeMicrophoneGateway(permission: .undetermined))
        let model = await makeConnected(fakes)
        await assertEventually { model.uiState.connected?.sheet == .microphone }
        model.microphoneNotNow()
        await assertEventually { model.uiState.connected?.microphone == .denied }
        XCTAssertNil(model.uiState.connected?.sheet)
        XCTAssertEqual(voice(model)?.mute, .noMicrophone)
        XCTAssertTrue(fakes.microphone.declined)
        XCTAssertEqual(fakes.backend.running?.capture, false)

        // The notice and the mute button let the system ask.
        model.requestMicrophone()
        await assertEventually { fakes.backend.running?.capture == true }
        await assertEventually { model.uiState.connected?.microphone == MicrophoneNotice.none }
        XCTAssertFalse(fakes.microphone.declined)
    }

    func testRefusingTheSystemsQuestionLeavesTheNoticeThatLeadsToTheSettings() async {
        let fakes = PhoneFakes(microphone: FakeMicrophoneGateway(permission: .undetermined))
        fakes.microphone.answer = false
        let model = await makeConnected(fakes)
        await assertEventually { model.uiState.connected?.sheet == .microphone }
        model.requestMicrophone()
        await assertEventually { model.uiState.connected?.microphone == .blocked }
        XCTAssertNil(model.uiState.connected?.sheet)
        XCTAssertEqual(fakes.settingsOpened, 0)

        // iOS does not ask a second time: the notice opens the system settings instead.
        model.requestMicrophone()
        XCTAssertEqual(fakes.settingsOpened, 1)
        XCTAssertEqual(fakes.microphone.requests, 1)
    }

    func testAllowingItInTheSystemSettingsStartsTheMicrophoneOnReturn() async {
        let fakes = PhoneFakes(microphone: FakeMicrophoneGateway(permission: .denied))
        let model = await makeConnected(fakes)
        await assertEventually { model.uiState.connected?.microphone == .blocked }
        fakes.microphone.set(.granted)
        model.recheckMicrophone()
        await assertEventually { fakes.backend.running?.capture == true }
        await assertEventually { model.uiState.connected?.microphone == MicrophoneNotice.none }
    }

    func testTheNoticeGoesAwayWhileTheMicrophoneIsNotWanted() async {
        let fakes = PhoneFakes(microphone: FakeMicrophoneGateway(permission: .denied))
        let model = await makeConnected(fakes)
        await assertEventually { model.uiState.connected?.microphone == .blocked }
        engine.emit(.audioWantedChanged(audio: AudioWanted(capture: false, playback: true)))
        await assertEventually { model.uiState.connected?.microphone == MicrophoneNotice.none }
    }
}
