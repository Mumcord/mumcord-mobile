// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The More tab as a value: the badge, and the settings with the rules of their rows.
final class MoreStateTests: XCTestCase {
    private let speaker = AudioOutput(id: OutputId.speaker, kind: .speaker, name: "")
    private let earpiece = AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: "")

    private let appSettings = AppSettings(
        language: nil,
        linkPreviews: true,
        historyMaxAgeDays: 30,
        notifyPrivateMessages: true,
        notifyMentions: false
    )

    private let mobileSettings = MobileSettings(
        talkModeChosen: true,
        defaultOutput: .speaker,
        mobileDataAutoplayStreams: false,
        mobileDataLoadMedia: true,
        mobileDataReducedVoice: false,
        idleDisconnect: false,
        notifySenderOnly: false,
        updateCheck: true
    )

    private func entry(_ id: UInt64, isSelf: Bool = false) -> BridgeEvent {
        .activityAdded(entry: ActivityEntry(
            id: id,
            kind: isSelf ? .connected : .joined,
            session: UInt32(id),
            name: "Person \(id)",
            isSelf: isSelf,
            channelId: Mockup.lobby,
            channelName: "Lobby",
            atMs: Int64(id)
        ))
    }

    private func session(_ more: [BridgeEvent] = []) -> SessionState {
        let events: [BridgeEvent] = [
            .connectionChanged(state: .connected),
            .serverSynced(server: mockupServer()),
            .mobileSettingsChanged(settings: mobileSettings),
            .appSettingsChanged(settings: appSettings),
        ]
        return (events + more).reduce(SessionState()) { state, event in state.fold(event) }
    }

    private func state(
        _ session: SessionState,
        _ local: MoreLocal = MoreLocal(),
        output: OutputState = OutputState(),
        senderOnly: Bool = false
    ) -> MoreUiState? {
        moreUiState(session: session, local: local, serverName: "Nordlicht", output: output, senderOnly: senderOnly)
    }

    func testThereIsNoStateWithoutAServer() {
        XCTAssertNil(state(SessionState()))
    }

    func testTheBadgeCountsNewEntriesByOthersAndIsGoneWhileTheLogIsOpen() throws {
        let session = session([entry(1, isSelf: true), entry(2), entry(3)])
        let main = try XCTUnwrap(state(session))
        XCTAssertEqual(main.page, .main)
        XCTAssertEqual(main.serverName, "Nordlicht")
        XCTAssertEqual(main.activityBadge, 2)
        XCTAssertEqual(main.activity.map(\.id), [3, 2, 1])

        var local = MoreLocal()
        local.seenActivityId = 2
        XCTAssertEqual(try XCTUnwrap(state(session, local)).activityBadge, 1)

        local = MoreLocal()
        local.page = .activity
        local.filter = .streams
        let open = try XCTUnwrap(state(session, local))
        XCTAssertEqual(open.activityBadge, 0)
        XCTAssertEqual(open.filter, .streams)
    }

    func testTheSettingsExistOnlyWhileTheirScreenIsOpenAndTheyWereRead() throws {
        XCTAssertNil(try XCTUnwrap(state(session())).settings, "closed")

        var local = MoreLocal()
        local.page = .settings
        local.sheet = .historyAge
        local.noiseSuppression = true
        let settings = try XCTUnwrap(try XCTUnwrap(state(session(), local, senderOnly: true)).settings)
        XCTAssertEqual(settings.app, appSettings)
        XCTAssertTrue(settings.mobile.mobileDataLoadMedia)
        XCTAssertEqual(settings.talkMode, .voiceActivity)
        XCTAssertEqual(settings.noiseSuppression, true)
        XCTAssertTrue(settings.senderOnly)
        XCTAssertEqual(settings.sheet, .historyAge)

        var unread = session()
        unread.appSettings = nil
        XCTAssertNil(try XCTUnwrap(state(unread, local)).settings, "the shared settings are not known yet")
    }

    func testTheDefaultOutputIsOnlyOfferedWhereAnEarpieceExists() throws {
        var local = MoreLocal()
        local.page = .settings
        func hasEarpiece(_ available: [AudioOutput]) throws -> Bool {
            let output = OutputState(available: available, current: available.first)
            return try XCTUnwrap(try XCTUnwrap(state(session(), local, output: output)).settings).hasEarpiece
        }
        XCTAssertTrue(try hasEarpiece([speaker, earpiece]))
        XCTAssertFalse(try hasEarpiece([speaker]), "a tablet")
        XCTAssertFalse(try hasEarpiece([]), "no voice session")
    }

    func testTheHistoryChoicesAreTheDesktops() {
        XCTAssertEqual(historyAgeDays, [nil, 7, 30, 90, 365])
    }
}
