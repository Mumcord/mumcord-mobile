// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The notice after the app was closed in the middle of a session (FR-5.37), on the model.
@MainActor
final class SessionEndedNoticeTests: XCTestCase {
    private let box = EngineBox()
    private let notice = SessionEndedNotice(serverId: 1, serverName: "Nordlicht")

    private func makeModel(sessionEnded: SessionEndedNotice?) async -> AppModel {
        let store = makeStore(box, server: mockupVoiceServer(), servers: [savedServer(1, "Nordlicht")])
        let model = PhoneFakes().makeModel(
            store: store,
            setupStore: InMemorySetupStore(firstStartDone: true),
            sessionEnded: sessionEnded
        )
        await assertEventually { model.uiState.session.serversLoaded }
        return model
    }

    func testACutOffSessionIsSaidAtTheStartAndNothingConnectsByItself() async {
        let model = await makeModel(sessionEnded: notice)
        XCTAssertEqual(model.uiState.overlay, .sessionEnded(serverId: 1, serverName: "Nordlicht"))
        XCTAssertEqual(box.engine.connectCalls.count, 0)
        XCTAssertNil(model.uiState.connected)
    }

    func testReconnectConnectsToTheServerOfTheSession() async {
        let model = await makeModel(sessionEnded: notice)
        model.reconnectAfterSessionEnded()
        XCTAssertNil(model.uiState.overlay)
        await assertEventually { model.uiState.connected != nil }
        XCTAssertEqual(box.engine.connectCalls.map(\.serverId), [1])
    }

    func testCloseOnlyPutsTheNoticeAway() async {
        let model = await makeModel(sessionEnded: notice)
        model.dismissOverlay()
        XCTAssertNil(model.uiState.overlay)
        XCTAssertEqual(box.engine.connectCalls.count, 0)
    }

    func testWithoutACutOffSessionNothingIsShown() async {
        let model = await makeModel(sessionEnded: nil)
        XCTAssertNil(model.uiState.overlay)
    }
}
