// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The model against a `FakeEngine`. Everything it starts runs in tasks, so the tests wait for
/// the state they expect.
@MainActor
final class AppModelTests: XCTestCase {
    private let server = ServerState(
        selfSession: 10,
        channels: [channel(0, "Root")],
        people: [person(10, "Me", channelId: 0)]
    )
    private let nordlicht = savedServer(1, "Nordlicht")
    private let tidepool = savedServer(2, "Tidepool", username: "Anna")
    private let box = EngineBox()
    private var setupStore = InMemorySetupStore(firstStartDone: true)

    /// A model whose engine is created and whose server list is loaded.
    private func makeModel(
        setupDone: Bool = true,
        servers: [SavedServer]? = nil,
        prompt: CertificatePrompt? = nil,
        configure: @escaping @Sendable (FakeEngine) -> Void = { _ in }
    ) async -> AppModel {
        setupStore = InMemorySetupStore(firstStartDone: setupDone)
        let store = makeStore(
            box,
            server: server,
            prompt: prompt,
            servers: servers ?? [nordlicht, tidepool],
            configure: configure,
            linkParser: parseTestLink
        )
        let model = AppModel(store: store, setupStore: setupStore)
        await assertEventually { model.uiState.session.serversLoaded }
        return model
    }

    private var engine: FakeEngine { box.engine }

    private func linkState(_ model: AppModel) -> LinkConfirmState? {
        if case .link(let state) = model.uiState.overlay { return state }
        return nil
    }

    // MARK: First-start setup

    func testFirstStartShowsTheTalkModeChoiceWithNothingPicked() async {
        let model = await makeModel(setupDone: false, servers: [])

        XCTAssertEqual(model.uiState.screen, .talkModeChoice)
        XCTAssertNil(model.uiState.talkModeChoice)

        // Without a choice "Continue" does nothing.
        model.confirmTalkMode()
        XCTAssertEqual(model.uiState.screen, .talkModeChoice)
        XCTAssertEqual(engine.currentTalkMode, .voiceActivity)
    }

    func testTheChosenTalkModeIsSavedAndTheSetupIsDoneForGood() async {
        let model = await makeModel(setupDone: false, servers: [])

        model.selectTalkMode(.pushToTalk)
        model.confirmTalkMode()

        await assertEventually { model.uiState.screen == .serverList }
        XCTAssertEqual(engine.currentTalkMode, .pushToTalk)
        XCTAssertTrue(setupStore.firstStartDone)
    }

    func testATalkModeThatCouldNotBeSavedKeepsTheSetupOpenAndSaysWhy() async {
        let model = await makeModel(setupDone: false, servers: []) { $0.talkModeFailure = .Io(detail: "disk full") }

        model.selectTalkMode(.voiceActivity)
        model.confirmTalkMode()

        await assertEventually { model.uiState.setupError == .io }
        XCTAssertFalse(setupStore.firstStartDone)
        XCTAssertEqual(model.uiState.screen, .talkModeChoice)
    }

    // MARK: Server list

    func testTheListShowsTheSavedServersWithTheirPingAnswers() async {
        let model = await makeModel { $0.pings[1] = ServerPing(latencyMs: 24, peopleOnline: 5, maxPeople: 50) }

        XCTAssertEqual(model.uiState.screen, .serverList)
        XCTAssertEqual(model.uiState.serverRows.map(\.name), ["Nordlicht", "Tidepool"])
        let expected: [PingState?] = [.online(peopleOnline: 5, latencyMs: 24), .offline]
        await assertEventually { model.uiState.serverRows.map(\.ping) == expected }
    }

    func testComingBackToTheListAsksTheServersAgain() async {
        let model = await makeModel()
        await assertEventually { model.uiState.serverRows.allSatisfy { $0.ping != nil } }
        engine.pings[2] = ServerPing(latencyMs: 31, peopleOnline: 12, maxPeople: 50)

        model.refreshPings()

        await assertEventually { model.uiState.serverRows[1].ping == .online(peopleOnline: 12, latencyMs: 31) }
        // The two answers of each round may arrive in either order.
        XCTAssertEqual(engine.pingRequests.sorted(), [1, 1, 2, 2])
    }

    func testAddingAServerValidatesFirstAndThenSavesAndClosesTheForm() async {
        let model = await makeModel()
        model.openAddServer()
        XCTAssertEqual(model.uiState.screen, .serverForm)

        model.saveForm()
        XCTAssertTrue(model.uiState.form?.showErrors == true)
        XCTAssertTrue(engine.serverInputs.isEmpty)

        model.changeForm {
            $0.address = "quietharbor.example"
            $0.username = "Me"
            $0.password = "secret"
        }
        model.saveForm()

        await assertEventually {
            model.uiState.screen == .serverList
                && model.uiState.serverRows.map(\.name) == ["Nordlicht", "Tidepool", "quietharbor.example"]
        }
        XCTAssertEqual(
            engine.serverInputs,
            [ServerInput(
                name: "",
                address: "quietharbor.example",
                port: 64738,
                username: "Me",
                password: .set(value: "secret"),
                identityId: nil
            )]
        )
        // The new server is asked for its people online, too.
        await assertEventually { self.engine.pingRequests.contains(3) }
    }

    func testEditingAServerSavesTheChangeUnderTheSameEntry() async {
        let model = await makeModel()
        model.toggleEditing()
        XCTAssertTrue(model.uiState.editing)

        model.openEditServer(2)
        model.changeForm { $0.name = "Tide" }
        model.saveForm()

        await assertEventually { model.uiState.serverRows.map(\.name) == ["Nordlicht", "Tide"] }
        XCTAssertEqual(model.uiState.serverRows.map(\.id), [1, 2])
    }

    func testASaveTheEngineRefusesStaysInTheFormWithTheError() async {
        let model = await makeModel()
        engine.saveFailure = .SecureStorageUnavailable(detail: "locked")
        model.openAddServer()
        model.changeForm {
            $0.address = "a.example"
            $0.username = "Me"
        }

        model.saveForm()

        await assertEventually { model.uiState.form?.saveError == .secureStorageUnavailable }
        XCTAssertEqual(model.uiState.screen, .serverForm)
        XCTAssertFalse(model.uiState.form?.saving == true)
        XCTAssertEqual(model.uiState.session.phase, .idle)
    }

    func testRemovingAServerAsksFirst() async {
        let model = await makeModel()

        model.requestRemove(2)
        XCTAssertEqual(model.uiState.overlay, .confirmRemove(server: tidepool))
        XCTAssertEqual(model.uiState.serverRows.count, 2)

        model.dismissOverlay()
        XCTAssertEqual(model.uiState.serverRows.count, 2)

        model.requestRemove(2)
        model.confirmRemove()
        XCTAssertNil(model.uiState.overlay)
        await assertEventually { model.uiState.serverRows.map(\.name) == ["Nordlicht"] }
    }

    // MARK: Connecting

    func testConnectingAsksTheCertificateQuestionAndThenShowsTheChannels() async {
        let model = await makeModel(prompt: CertificatePrompt(fingerprint: "AA:BB", previousFingerprint: nil))

        model.connect(1)
        await assertEventually {
            model.uiState.serverRows.map(\.connecting) == [true, false]
                && model.uiState.session.certificatePrompt?.fingerprint == "AA:BB"
        }

        model.answerCertificatePrompt(fingerprint: "AA:BB", trust: true)
        await assertEventually { model.uiState.screen == .channels }
        XCTAssertEqual(engine.connectCalls, [ConnectCall(serverId: 1, password: nil, channelPath: [])])

        model.disconnect()
        await assertEventually { model.uiState.screen == .serverList }
    }

    func testConnectingElsewhereAsksFirstAndDisconnectsBeforeItConnects() async {
        let model = await makeModel(prompt: CertificatePrompt(fingerprint: "AA:BB", previousFingerprint: nil))
        model.connect(1)
        await assertEventually { model.uiState.session.certificatePrompt != nil }

        // Nordlicht is still connecting (it waits for the certificate answer).
        model.connect(2)
        XCTAssertEqual(
            model.uiState.overlay,
            .confirmSwitch(target: ConnectTarget(serverId: 2), currentName: "Nordlicht", targetName: "Tidepool")
        )
        XCTAssertEqual(engine.connectCalls.count, 1)
        XCTAssertEqual(engine.disconnects, 0)

        model.confirmSwitch()
        XCTAssertNil(model.uiState.overlay)
        await assertEventually { self.engine.connectCalls.count == 2 }
        XCTAssertEqual(engine.disconnects, 1)
        XCTAssertEqual(engine.connectCalls.map(\.serverId), [1, 2])
        await assertEventually { model.uiState.session.activeServerId == 2 }
    }

    func testDecliningTheSwitchKeepsTheCurrentConnection() async {
        let model = await makeModel()
        model.connect(1)
        await assertEventually { model.uiState.screen == .channels }

        model.connect(2)
        model.dismissOverlay()

        XCTAssertEqual(model.uiState.screen, .channels)
        XCTAssertEqual(engine.disconnects, 0)
        XCTAssertEqual(engine.connectCalls.count, 1)
    }

    func testAFailedConnectionNamesTheServerAndTryAgainConnectsToItAgain() async {
        let model = await makeModel()
        engine.connectFailure = .ServerUnreachable(detail: "timeout")

        model.connect(2)
        await assertEventually { model.uiState.session.phase == .failed(.serverUnreachable) }
        XCTAssertEqual(model.uiState.activeServerName, "Tidepool")
        XCTAssertEqual(model.uiState.screen, .serverList)

        model.retry()
        await assertEventually { model.uiState.screen == .channels }
        XCTAssertEqual(engine.connectCalls, [ConnectCall(serverId: 2, password: nil, channelPath: [])])
    }

    func testAWrongPasswordOffersToEnterItAndConnectsWithIt() async {
        let model = await makeModel { $0.requiredPassword = "open sesame" }
        model.connect(1)
        await assertEventually { model.uiState.session.phase == .failed(.wrongPassword) }

        model.retry()
        XCTAssertEqual(
            model.uiState.overlay,
            .enterPassword(target: ConnectTarget(serverId: 1), serverName: "Nordlicht", password: "")
        )

        // Nothing is sent without a password.
        model.submitPassword()
        XCTAssertEqual(engine.connectCalls.count, 1)

        model.changePassword("open sesame")
        model.submitPassword()
        await assertEventually { model.uiState.screen == .channels }
        XCTAssertNil(model.uiState.overlay)
        XCTAssertEqual(
            engine.connectCalls.last,
            ConnectCall(serverId: 1, password: "open sesame", channelPath: [])
        )
    }

    func testDismissingAnErrorClearsIt() async {
        let model = await makeModel()
        engine.connectFailure = .Banned(detail: "reason")
        model.connect(1)
        await assertEventually { model.uiState.session.phase == .failed(.banned) }

        model.dismissError()

        await assertEventually { model.uiState.session.phase == .idle }
        XCTAssertNil(model.uiState.activeServerName)
    }

    // MARK: mumble:// links

    func testALinkOnlyShowsItsTargetUntilTheUserConfirms() async throws {
        let model = await makeModel()

        model.openLink("mumble://Ben@new.example:40000/Games/Chess")
        await assertEventually { self.linkState(model) != nil }

        let state = try XCTUnwrap(linkState(model))
        XCTAssertEqual(
            state.link,
            MumbleLink(address: "new.example", port: 40000, username: "Ben", channelPath: ["Games", "Chess"])
        )
        XCTAssertTrue(state.willAddServer)
        XCTAssertTrue(engine.serverInputs.isEmpty)
        XCTAssertTrue(engine.connectCalls.isEmpty)

        model.dismissOverlay()
        XCTAssertTrue(engine.serverInputs.isEmpty)
        XCTAssertTrue(engine.connectCalls.isEmpty)
        XCTAssertEqual(model.uiState.serverRows.count, 2)
    }

    func testConfirmingALinkToANewServerAddsItAndJoinsTheChannel() async {
        let model = await makeModel()
        model.openLink("mumble://Ben@new.example:40000/Games/Chess")
        await assertEventually { self.linkState(model) != nil }

        model.confirmLink()

        await assertEventually { model.uiState.screen == .channels }
        XCTAssertEqual(
            engine.serverInputs,
            [ServerInput(
                name: "",
                address: "new.example",
                port: 40000,
                username: "Ben",
                password: .keep,
                identityId: nil
            )]
        )
        XCTAssertEqual(engine.connectCalls, [ConnectCall(serverId: 3, password: nil, channelPath: ["Games", "Chess"])])
        XCTAssertNil(model.uiState.overlay)
    }

    func testALinkToASavedServerConnectsThroughItsEntryWithoutAddingAnything() async {
        let model = await makeModel()
        model.openLink("mumble://Someone@tidepool.example/Lobby")
        await assertEventually { self.linkState(model) != nil }
        XCTAssertEqual(linkState(model)?.savedServer, tidepool)

        model.confirmLink()

        await assertEventually { self.engine.connectCalls.count == 1 }
        XCTAssertTrue(engine.serverInputs.isEmpty)
        XCTAssertEqual(engine.connectCalls, [ConnectCall(serverId: 2, password: nil, channelPath: ["Lobby"])])
    }

    func testALinkWithoutUserNameNeedsOneTyped() async {
        let model = await makeModel()
        model.openLink("mumble://new.example")
        await assertEventually { self.linkState(model) != nil }

        model.confirmLink()
        XCTAssertTrue(engine.serverInputs.isEmpty)

        model.changeLinkUsername("Ben")
        model.confirmLink()
        await assertEventually { self.engine.connectCalls.count == 1 }
        XCTAssertEqual(engine.serverInputs.map(\.username), ["Ben"])
    }

    func testALinkWhileConnectedElsewhereAsksBeforeSwitching() async {
        let model = await makeModel()
        model.connect(1)
        await assertEventually { model.uiState.screen == .channels }

        model.openLink("mumble://tidepool.example/Lobby")
        await assertEventually { self.linkState(model) != nil }
        model.confirmLink()

        XCTAssertEqual(
            model.uiState.overlay,
            .confirmSwitch(
                target: ConnectTarget(serverId: 2, channelPath: ["Lobby"]),
                currentName: "Nordlicht",
                targetName: "Tidepool"
            )
        )
        XCTAssertEqual(engine.connectCalls.count, 1)

        model.confirmSwitch()
        await assertEventually { self.engine.connectCalls.count == 2 }
        XCTAssertEqual(engine.connectCalls.last, ConnectCall(serverId: 2, password: nil, channelPath: ["Lobby"]))
        XCTAssertEqual(engine.disconnects, 1)
    }

    func testSomethingThatIsNotAMumbleLinkIsReportedAndDoesNothing() async {
        let model = await makeModel()

        model.openLink("https://example.org")

        await assertEventually { model.uiState.overlay == .invalidLink }
        XCTAssertTrue(engine.connectCalls.isEmpty)
    }
}
