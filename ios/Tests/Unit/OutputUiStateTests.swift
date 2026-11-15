// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest
@testable import Mumcord

private let speaker = AudioOutput(id: OutputId.speaker, kind: .speaker, name: "")
private let earpiece = AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: "")
private let buds = AudioOutput(id: "buds", kind: .bluetooth, name: "Buds")
private let wired = AudioOutput(id: "wired", kind: .wiredHeadset, name: "")

/// The audio output on the connected screens: the chip, the sheet and the question.
@MainActor
final class OutputUiStateTests: XCTestCase {
    private let box = EngineBox()

    func testWithoutACurrentOutputThereIsNothingToShow() {
        XCTAssertNil(outputUiState(OutputState()))
        XCTAssertNil(outputUiState(OutputState(available: [speaker], current: nil)))
    }

    func testTheSheetListsSpeakerEarpieceBluetoothAndTheWiredHeadsetAsNotConnected() {
        let state = OutputState(available: [buds, earpiece, speaker], current: speaker)
        XCTAssertEqual(outputUiState(state)?.rows, [
            OutputRow(id: OutputId.speaker, kind: .speaker, name: "", selected: true),
            OutputRow(id: OutputId.earpiece, kind: .earpiece, name: "", selected: false),
            OutputRow(id: "buds", kind: .bluetooth, name: "Buds", selected: false),
            OutputRow(id: nil, kind: .wiredHeadset, name: "", selected: false),
        ])
    }

    func testAConnectedWiredHeadsetCanBePicked() {
        let state = OutputState(available: [speaker, wired], current: wired)
        XCTAssertEqual(outputUiState(state)?.rows, [
            OutputRow(id: OutputId.speaker, kind: .speaker, name: "", selected: false),
            OutputRow(id: "wired", kind: .wiredHeadset, name: "", selected: true),
        ])
    }

    // MARK: On the model

    private func makeConnected(_ saved: DefaultOutput, _ device: FakeOutputDevice) async -> AppModel {
        let store = makeStore(
            box,
            server: mockupVoiceServer(),
            servers: [savedServer(1, "Nordlicht")],
            configure: { $0.storedMobileSettings.defaultOutput = saved }
        )
        let model = PhoneFakes().makeModel(
            store: store,
            setupStore: InMemorySetupStore(firstStartDone: true),
            outputs: VoiceOutput(store: store, outputs: SessionOutputs(device: device))
        )
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected != nil }
        return model
    }

    func testTheVoiceViewNamesTheOutputAndTheSheetSwitchesIt() async {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece, buds], current: earpiece))
        let model = await makeConnected(.speaker, device)
        await assertEventually { model.uiState.connected?.voice?.output == buds }

        model.openVoice()
        model.openOutput()
        guard case .audioOutput(let rows) = model.uiState.connected?.sheet else {
            return XCTFail("the output sheet is not open")
        }
        XCTAssertEqual(rows.map(\.selected), [false, false, true, false])

        model.selectOutput(OutputId.speaker)
        XCTAssertNil(model.uiState.connected?.sheet)
        await assertEventually { model.uiState.connected?.voice?.output == speaker }
    }

    func testClosingTheVoiceViewClosesTheOutputSheet() async {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let model = await makeConnected(.speaker, device)
        await assertEventually { model.uiState.connected?.voice?.output == speaker }
        model.openVoice()
        model.openOutput()
        XCTAssertNotNil(model.uiState.connected?.sheet)
        model.dismissSheet()
        XCTAssertNil(model.uiState.connected?.sheet)
        model.openOutput()
        model.closeVoice()
        XCTAssertNil(model.uiState.connected?.sheet)
    }

    func testTheFirstConnectionAsksAndTheAnswerClosesTheQuestion() async {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let model = await makeConnected(.ask, device)
        await assertEventually { model.uiState.connected?.sheet == .outputQuestion }

        model.answerOutputQuestion(.speaker)
        await assertEventually { model.uiState.connected?.sheet == nil }
        await assertEventually { model.uiState.connected?.voice?.output == speaker }
    }

    func testTheQuestionCanBePutAway() async {
        let device = FakeOutputDevice(OutputState(available: [speaker, earpiece], current: earpiece))
        let model = await makeConnected(.ask, device)
        await assertEventually { model.uiState.connected?.sheet == .outputQuestion }
        model.dismissOutputQuestion()
        await assertEventually { model.uiState.connected?.sheet == nil }
        XCTAssertEqual(model.uiState.connected?.voice?.output, earpiece)
    }

    func testWithoutOutputsTheVoiceViewHasNoChip() async {
        let store = makeStore(box, server: mockupVoiceServer(), servers: [savedServer(1, "Nordlicht")])
        let model = PhoneFakes().makeModel(store: store, setupStore: InMemorySetupStore(firstStartDone: true))
        await assertEventually { model.uiState.session.serversLoaded }
        model.connect(1)
        await assertEventually { model.uiState.connected?.voice != nil }
        XCTAssertNil(model.uiState.connected?.voice?.output)
        model.openOutput()
        XCTAssertNil(model.uiState.connected?.sheet)
    }
}
