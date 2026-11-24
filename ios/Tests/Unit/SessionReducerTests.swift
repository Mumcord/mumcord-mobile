// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

final class SessionReducerTests: XCTestCase {
    private let server = ServerState(
        selfSession: 10,
        channels: [
            channel(0, "Root"),
            channel(1, "Lobby", parentId: 0),
        ],
        people: [person(10, "Me", channelId: 0)]
    )

    private func fold(_ events: [BridgeEvent]) -> SessionState {
        events.reduce(SessionState()) { state, event in state.fold(event) }
    }

    private var connected: [BridgeEvent] {
        [
            .connectionChanged(state: .connecting),
            .connectionChanged(state: .connected),
            .serverSynced(server: server),
        ]
    }

    private func names(_ state: SessionState) -> [String] {
        func collect(_ node: ChannelNode) -> [String] {
            [node.channel.name] + node.people.map(\.name) + node.children.flatMap(collect)
        }
        return state.server?.root.map(collect) ?? []
    }

    func testAChannelPasswordQuestionIsAskedMarkedWrongAndEnds() {
        let asked = fold(connected + [.channelPasswordChanged(channelId: 1, state: .required)])
        XCTAssertEqual(asked.channelPassword, ChannelPasswordPrompt(channelId: 1, wrong: false))
        let wrong = asked.fold(.channelPasswordChanged(channelId: 1, state: .wrong))
        XCTAssertEqual(wrong.channelPassword, ChannelPasswordPrompt(channelId: 1, wrong: true))
        XCTAssertNil(wrong.fold(.channelPasswordChanged(channelId: 1, state: .ended)).channelPassword)
    }

    func testARefusalIsKeptAndTheEndOfTheConnectionForgetsQuestionsRefusalsAndHints() {
        let state = fold(connected + [
            .channelPasswordChanged(channelId: 1, state: .required),
            .actionDenied(channelId: 1, detail: "Permission denied."),
        ]).withHint(UnsupportedHint(id: 1, personName: "Jonas", kind: .fileTransfer))
        XCTAssertEqual(state.denied, DeniedAction(channelId: 1, detail: "Permission denied."))

        let ended = state.fold(.connectionChanged(state: .disconnected(error: nil)))
        XCTAssertNil(ended.channelPassword)
        XCTAssertNil(ended.denied)
        XCTAssertEqual(ended.hints, [])
    }

    func testHintsKeepOnlyTheNewestFew() {
        let state = (1...5).reduce(SessionState()) { state, id in
            state.withHint(UnsupportedHint(id: Int64(id), personName: "Jonas", kind: .fileTransfer))
        }
        XCTAssertEqual(state.hints.map(\.id), [3, 4, 5])
    }

    func testAWhisperTheEngineEndedIsCounted() {
        let once = SessionState().fold(.whisperEnded)
        XCTAssertEqual(once.whispersEnded, 1)
        XCTAssertEqual(once.fold(.whisperEnded).whispersEnded, 2)
    }

    func testTheIdentitiesAreFollowedAndThePhoneSettingsAreKept() {
        let state = fold(connected)
        XCTAssertNil(state.identities)
        XCTAssertEqual(state.fold(.identitiesChanged(identities: [])).identities, [])
        let settings = MobileSettings(
            talkModeChosen: true,
            defaultOutput: .speaker,
            mobileDataAutoplayStreams: false,
            mobileDataLoadMedia: true,
            mobileDataReducedVoice: false,
            idleDisconnect: false,
            notifySenderOnly: true,
            updateCheck: true
        )
        XCTAssertEqual(state.fold(.mobileSettingsChanged(settings: settings)).mobileSettings, settings)
    }

    private func activity(_ id: UInt64, _ kind: ActivityKind = .joined) -> BridgeEvent {
        .activityAdded(entry: ActivityEntry(
            id: id,
            kind: kind,
            session: 20,
            name: "Anna",
            isSelf: false,
            channelId: 1,
            channelName: "Lobby",
            atMs: Int64(id)
        ))
    }

    func testTheActivityLogIsKeptNewestFirstForTheConnectionOnly() {
        let state = fold(connected + [activity(1, .connected), activity(2), activity(3, .moved)])
        XCTAssertEqual(state.activity.map(\.id), [3, 2, 1])

        // As the bridge: a sync after a reconnect starts the log again, the end of a
        // connection forgets it.
        XCTAssertEqual(state.fold(.serverSynced(server: server)).activity, [])
        XCTAssertEqual(state.fold(.connectionChanged(state: .disconnected(error: nil))).activity, [])
        // While the engine tries to get back in, the log stays.
        let reconnecting = state.fold(.connectionChanged(state: .reconnecting(attempt: 1, maxAttempts: 5, nextRetrySecs: 1)))
        XCTAssertEqual(reconnecting.activity.map(\.id), [3, 2, 1])
    }

    func testTheActivityLogKeepsAsManyEntriesAsTheBridge() {
        let state = (1...UInt64(maxActivity + 5)).reduce(fold(connected)) { state, id in state.fold(activity(id)) }
        XCTAssertEqual(state.activity.count, maxActivity)
        XCTAssertEqual(state.activity.first?.id, UInt64(maxActivity + 5))
        XCTAssertEqual(state.activity.last?.id, 6)
    }

    func testTheSharedSettingsFollowTheEngine() {
        let settings = AppSettings(
            language: nil,
            linkPreviews: true,
            historyMaxAgeDays: 30,
            notifyPrivateMessages: false,
            notifyMentions: true
        )
        XCTAssertNil(SessionState().appSettings, "not known before they were read")
        XCTAssertEqual(SessionState().fold(.appSettingsChanged(settings: settings)).appSettings, settings)
    }

    func testTheStreamsAndTheDirectConnectionQuestionAreFollowedUntilTheConnectionEnds() {
        let stream = mockupStream(watch: .watching(path: .relay))
        let shown = fold(connected + [.streamsChanged(streams: [stream]), .directConnectionsQuestion])
        XCTAssertEqual(shown.streams, [stream])
        XCTAssertTrue(shown.directConnectionsQuestion)
        XCTAssertEqual(shown.fold(.streamsChanged(streams: [])).streams, [])
        let ended = shown.fold(.connectionChanged(state: .disconnected(error: nil)))
        XCTAssertEqual(ended.streams, [])
        XCTAssertFalse(ended.directConnectionsQuestion)
    }

    func testANewSessionIsIdleAndShowsNoServer() {
        let state = SessionState()
        XCTAssertEqual(state.phase, .idle)
        XCTAssertFalse(state.isBusy)
        XCTAssertFalse(state.showsServer)
    }

    func testConnectingIsBusyButShowsNoServerYet() {
        let state = fold([.connectionChanged(state: .connecting)])
        XCTAssertEqual(state.phase, .connecting)
        XCTAssertTrue(state.isBusy)
        XCTAssertFalse(state.showsServer)
    }

    func testAnUnknownCertificateBecomesAPromptWithoutPreviousFingerprint() throws {
        let state = fold([
            .connectionChanged(state: .connecting),
            .certificatePromptRequired(prompt: CertificatePrompt(fingerprint: "AA:BB", previousFingerprint: nil)),
        ])
        XCTAssertEqual(state.certificatePrompt, CertificatePromptState(fingerprint: "AA:BB", previousFingerprint: nil))
        XCTAssertFalse(try XCTUnwrap(state.certificatePrompt).isChanged)
    }

    func testAChangedCertificateKeepsThePreviousFingerprint() throws {
        let state = fold([
            .certificatePromptRequired(prompt: CertificatePrompt(fingerprint: "CC:DD", previousFingerprint: "AA:BB")),
        ])
        XCTAssertEqual(state.certificatePrompt, CertificatePromptState(fingerprint: "CC:DD", previousFingerprint: "AA:BB"))
        XCTAssertTrue(try XCTUnwrap(state.certificatePrompt).isChanged)
    }

    func testConnectedClearsThePromptAndSyncShowsTheServer() {
        let state = fold([
            .connectionChanged(state: .connecting),
            .certificatePromptRequired(prompt: CertificatePrompt(fingerprint: "AA:BB", previousFingerprint: nil)),
            .connectionChanged(state: .connected),
            .serverSynced(server: server),
        ])
        XCTAssertNil(state.certificatePrompt)
        XCTAssertTrue(state.showsServer)
        XCTAssertEqual(names(state), ["Root", "Me", "Lobby"])
    }

    func testConnectedWithoutSyncDoesNotShowTheServer() {
        let state = fold([.connectionChanged(state: .connected)])
        XCTAssertTrue(state.isBusy)
        XCTAssertFalse(state.showsServer)
    }

    func testChannelsChangedAddsAndRenamesChannels() {
        let state = fold(connected + [
            .channelsChanged(channels: [
                channel(1, "Lounge", parentId: 0),
                channel(2, "Games", parentId: 0),
            ]),
        ])
        XCTAssertEqual(names(state), ["Root", "Me", "Games", "Lounge"])
    }

    func testChannelRemovedDropsTheChannelAndItsSubChannels() {
        let state = fold(connected + [
            .channelsChanged(channels: [channel(2, "Sub", parentId: 1)]),
            .channelRemoved(id: 1),
        ])
        XCTAssertEqual(names(state), ["Root", "Me"])
    }

    func testPersonChangedAddsAPersonAndMovesThemBetweenChannels() {
        let joined = fold(connected + [.personChanged(person: person(11, "Alice", channelId: 0))])
        XCTAssertEqual(names(joined), ["Root", "Alice", "Me", "Lobby"])

        let moved = joined.fold(.personChanged(person: person(11, "Alice", channelId: 1)))
        XCTAssertEqual(names(moved), ["Root", "Me", "Lobby", "Alice"])
    }

    func testPersonRemovedDropsThePerson() {
        let state = fold(connected + [
            .personChanged(person: person(11, "Alice", channelId: 1)),
            .personRemoved(session: 11),
        ])
        XCTAssertEqual(names(state), ["Root", "Me", "Lobby"])
    }

    func testChangesBeforeTheServerIsSyncedAreIgnored() {
        let state = fold([
            .personChanged(person: person(11, "Alice", channelId: 1)),
            .channelRemoved(id: 1),
        ])
        XCTAssertNil(state.server)
    }

    func testReconnectingKeepsTheLastKnownServerVisible() {
        let state = fold(connected + [.connectionChanged(state: .reconnecting(attempt: 2, maxAttempts: 5, nextRetrySecs: 4))])
        XCTAssertEqual(state.phase, .reconnecting(Reconnecting(attempt: 2, maxAttempts: 5)))
        XCTAssertTrue(state.showsServer)
        XCTAssertTrue(state.isBusy)
    }

    func testDisconnectingOnRequestGoesBackToIdleAndForgetsTheServer() {
        let state = fold(connected + [.connectionChanged(state: .disconnected(error: nil))])
        XCTAssertEqual(state, SessionState())
    }

    func testDisconnectingWithAnErrorFailsAndForgetsServerAndPrompt() {
        let state = fold(connected + [
            .certificatePromptRequired(prompt: CertificatePrompt(fingerprint: "AA:BB", previousFingerprint: nil)),
            .connectionChanged(state: .disconnected(error: .ServerUnreachable(detail: "refused"))),
        ])
        XCTAssertEqual(state, SessionState(phase: .failed(.serverUnreachable)))
        XCTAssertFalse(state.isBusy)
    }

    func testTheServerListSurvivesTheEndOfAConnection() {
        let servers = [savedServer(1, "Nordlicht"), savedServer(2, "Tidepool")]
        let state = fold([.serversChanged(servers: servers)] + connected + [
            .connectionChanged(state: .disconnected(error: nil)),
        ])
        XCTAssertEqual(state, SessionState(servers: servers, serversLoaded: true))
    }

    func testTheFailedServerIsRememberedForTheRetryAndForgottenAfterAPlainDisconnect() {
        let connecting = SessionState(phase: .connecting, activeServerId: 2)

        let failed = connecting.fold(.connectionChanged(state: .disconnected(error: .NameInUse(detail: "taken"))))
        XCTAssertEqual(failed.activeServerId, 2)
        XCTAssertEqual(failed.phase, .failed(.nameInUse))

        let ended = connecting.fold(.connectionChanged(state: .disconnected(error: nil)))
        XCTAssertNil(ended.activeServerId)
    }

    func testEveryEngineErrorMapsToItsOwnConnectionError() {
        let mapping: [(BridgeError, ConnectionError)] = [
            (.NotConnected(detail: "d"), .notConnected),
            (.ServerUnreachable(detail: "d"), .serverUnreachable),
            (.WrongPassword(detail: "d"), .wrongPassword),
            (.NameInUse(detail: "d"), .nameInUse),
            (.CertificateRejected(detail: "d"), .certificateRejected),
            (.CertificateChanged(detail: "d"), .certificateChanged),
            (.ServerTooOld(detail: "d"), .serverTooOld),
            (.Banned(detail: "d"), .banned),
            (.NotFound(detail: "d"), .notFound),
            (.PermissionDenied(detail: "d"), .permissionDenied),
            (.InvalidInput(detail: "d"), .invalidInput),
            (.SecureStorageUnavailable(detail: "d"), .secureStorageUnavailable),
            (.Io(detail: "d"), .io),
            (.DuplicateIdentity(detail: "d"), .duplicateIdentity),
            (.LastIdentity(detail: "d"), .lastIdentity),
            (.NoIdentity(detail: "d"), .noIdentity),
            (.Internal(detail: "d"), .internalError),
        ]
        XCTAssertEqual(Set(ConnectionError.allCases), Set(mapping.map(\.1)))
        for (error, expected) in mapping {
            XCTAssertEqual(error.connectionError, expected)
        }
    }
}
