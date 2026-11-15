// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// What the connected screens show about the microphone.
final class MicrophoneNoticeTests: XCTestCase {
    func testNothingIsShownWhileTheMicrophoneIsNotMissing() {
        XCTAssertEqual(microphoneNotice(missing: false, declined: false, permission: .granted), .none)
        XCTAssertEqual(microphoneNotice(missing: false, declined: true, permission: .undetermined), .none)
        XCTAssertEqual(microphoneNotice(missing: false, declined: true, permission: .denied), .none)
    }

    func testTheFirstTimeItIsExplainedAndAfterNotNowOnlyTheNoticeStays() {
        XCTAssertEqual(microphoneNotice(missing: true, declined: false, permission: .undetermined), .explain)
        XCTAssertEqual(microphoneNotice(missing: true, declined: true, permission: .undetermined), .denied)
    }

    func testARefusalToTheSystemPointsToTheSettingsBecauseIosDoesNotAskAgain() {
        XCTAssertEqual(microphoneNotice(missing: true, declined: false, permission: .denied), .blocked)
        XCTAssertEqual(microphoneNotice(missing: true, declined: true, permission: .denied), .blocked)
    }

    private func connected(_ microphone: MicrophoneNotice, _ change: (inout SessionState) -> Void = { _ in }) -> ConnectedUiState? {
        var session = SessionState()
            .fold(.connectionChanged(state: .connected))
            .fold(.serverSynced(server: mockupServer()))
        change(&session)
        var local = ConnectedLocal()
        local.personSession = Mockup.jonas
        return connectedUiState(session: session, local: local, serverName: "Nordlicht", microphone: microphone)
    }

    func testTheExplanationIsASheetAndTheRefusalABanner() {
        let explain = connected(.explain)
        XCTAssertEqual(explain?.sheet, .microphone)
        XCTAssertEqual(explain?.microphone, MicrophoneNotice.none)

        for notice in [MicrophoneNotice.denied, .blocked] {
            let refused = connected(notice)
            XCTAssertEqual(refused?.microphone, notice)
            // The sheet the user had open stays.
            guard case .person = refused?.sheet else {
                XCTFail("expected the person sheet")
                return
            }
        }
    }

    func testOnlyOneSheetAtATimeTheServersPasswordQuestionFirst() {
        let state = connected(.explain) { $0.channelPassword = ChannelPasswordPrompt(channelId: Mockup.music, wrong: false) }
        guard case .channelPassword = state?.sheet else {
            XCTFail("expected the channel password question")
            return
        }
    }
}
