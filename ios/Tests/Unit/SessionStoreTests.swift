// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// The store against a `FakeEngine`. The engine's events are folded on a background task, so the
/// tests wait for the state they expect.
final class SessionStoreTests: XCTestCase {
    private let server = ServerState(
        selfSession: 10,
        channels: [channel(0, "Root")],
        people: [person(10, "Me", channelId: 0)]
    )
    private let prompt = CertificatePrompt(fingerprint: "AA:BB", previousFingerprint: nil)
    private let saved = savedServer(1, "Nordlicht")
    private let box = EngineBox()

    private var idle: SessionState {
        SessionState(servers: [saved], serversLoaded: true)
    }

    private func newStore(prompt: CertificatePrompt? = nil, useDefaultPrompt: Bool = true) async -> SessionStore {
        let store = makeStore(
            box,
            server: server,
            prompt: useDefaultPrompt ? self.prompt : prompt,
            servers: [saved],
            linkParser: { url in
                guard url.hasPrefix("mumble://") else { throw BridgeError.InvalidInput(detail: "not a link: \(url)") }
                return MumbleLink(address: "link.example", port: 64738, username: nil, channelPath: [])
            }
        )
        await store.loadServers()
        return store
    }

    func testConnectShowsConnectingAtOnceThenThePromptThenTheServer() async {
        let store = await newStore()

        await store.connectServer(1, password: nil, channelPath: [])
        XCTAssertEqual(store.state.phase, .connecting)
        XCTAssertEqual(store.state.activeServerId, 1)
        await assertEventually {
            store.state.certificatePrompt == CertificatePromptState(fingerprint: "AA:BB", previousFingerprint: nil)
        }
        XCTAssertEqual(box.engine.connectCalls, [ConnectCall(serverId: 1, password: nil, channelPath: [])])

        await store.answerCertificatePrompt(fingerprint: "AA:BB", trust: true)
        XCTAssertNil(store.state.certificatePrompt)
        await assertEventually { store.state.showsServer }
        XCTAssertEqual(box.engine.certificateAnswers.map(\.fingerprint), ["AA:BB"])
        XCTAssertEqual(box.engine.certificateAnswers.map(\.trust), [true])

        await store.disconnect()
        await assertEventually { store.state == self.idle }
    }

    func testRejectingTheCertificateEndsInTheCertificateRejectedError() async {
        let store = await newStore()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.certificatePrompt != nil }

        await store.answerCertificatePrompt(fingerprint: "AA:BB", trust: false)

        var failed = idle
        failed.phase = .failed(.certificateRejected)
        failed.activeServerId = 1
        await assertEventually { store.state == failed }
    }

    func testTheEngineIsCreatedOnceAndReusedForTheNextConnection() async {
        let store = await newStore(prompt: nil, useDefaultPrompt: false)
        await store.connectServer(1, password: nil, channelPath: [])
        await store.disconnect()
        await store.connectServer(1, password: nil, channelPath: [])

        await assertEventually { store.state.showsServer }
        XCTAssertEqual(box.engines.count, 1)
        XCTAssertEqual(box.engine.connectCalls.count, 2)
    }

    func testEventsAreFoldedInTheOrderTheEngineSentThem() async {
        let store = await newStore(prompt: nil, useDefaultPrompt: false)
        await store.connectServer(1, password: nil, channelPath: [])
        let engine = box.engine
        engine.emit(.personChanged(person: person(11, "Alice", channelId: 0)))
        engine.emit(.personChanged(person: person(11, "Alice renamed", channelId: 0)))
        engine.emit(.personRemoved(session: 10))

        await assertEventually { store.state.server?.root?.people.map(\.name) == ["Alice renamed"] }
    }

    func testAConnectTheEngineRefusesBecomesAFailureWithoutLoggingTheDetail() async {
        let store = await newStore()
        await store.connectServer(1, password: nil, channelPath: [])
        await store.disconnect()
        await assertEventually { store.state == self.idle }
        box.engine.connectFailure = .InvalidInput(detail: "bad address secret.example")

        await store.connectServer(1, password: nil, channelPath: [])

        var failed = idle
        failed.phase = .failed(.invalidInput)
        failed.activeServerId = 1
        await assertEventually { store.state == failed }
        XCTAssertEqual(box.logs, ["connect failed: invalidInput (BridgeError)"])
    }

    func testAFailedEngineStartIsReportedAndTheNextTryStartsItAgain() async {
        box.startFailure = BridgeError.SecureStorageUnavailable(detail: "locked")
        let store = await newStore(prompt: nil, useDefaultPrompt: false)
        XCTAssertEqual(store.state, SessionState(phase: .failed(.secureStorageUnavailable)))

        box.startFailure = EngineStartFailure()
        await store.connectServer(1, password: nil, channelPath: [])
        XCTAssertEqual(store.state.phase, .failed(.internalError))

        box.startFailure = nil
        store.dismissFailure()
        await store.loadServers()
        XCTAssertEqual(store.state, idle)
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer }
    }

    func testTheServerListFollowsAddEditAndRemove() async throws {
        let store = await newStore()
        let input = ServerInput(
            name: "Tidepool",
            address: "voice.tidepool.example",
            port: 64738,
            username: "Me",
            password: .set(value: "secret"),
            identityId: nil
        )

        let addResult = await store.addServer(input)
        let added = try XCTUnwrap(addResult.value)
        await assertEventually { store.state.servers == [self.saved, added] }
        XCTAssertTrue(added.hasPassword)

        var changed = input
        changed.name = "Tide"
        changed.password = .clear
        _ = await store.updateServer(added.id, changed)
        await assertEventually { store.state.servers.map(\.name) == ["Nordlicht", "Tide"] }
        XCTAssertEqual(store.state.servers.map(\.hasPassword), [false, false])

        let removed = await store.removeServer(added.id)
        XCTAssertNotNil(removed.value)
        await assertEventually { store.state.servers == [self.saved] }
        let removedAgain = await store.removeServer(added.id)
        XCTAssertEqual(removedAgain.failure, .notFound)
    }

    func testAFailedSaveIsReturnedToTheFormAndLeavesTheConnectionAlone() async {
        let store = await newStore()
        box.engine.saveFailure = .SecureStorageUnavailable(detail: "locked")

        let result = await store.addServer(
            ServerInput(name: "", address: "a.example", port: 64738, username: "Me", password: .keep, identityId: nil)
        )

        XCTAssertEqual(result.failure, .secureStorageUnavailable)
        XCTAssertEqual(store.state, idle)
    }

    func testAPingAnswersWithPeopleAndLatencyOrNothing() async {
        let store = await newStore()
        box.engine.pings[1] = ServerPing(latencyMs: 24, peopleOnline: 5, maxPeople: 50)

        let answer = await store.pingServer(1, refresh: false)
        XCTAssertEqual(answer, ServerPing(latencyMs: 24, peopleOnline: 5, maxPeople: 50))
        let silence = await store.pingServer(99, refresh: false)
        XCTAssertNil(silence)
    }

    func testThePasswordOfARetryIsPassedOnButNeverLogged() async {
        let store = await newStore(prompt: nil, useDefaultPrompt: false)
        box.engine.requiredPassword = "open sesame"

        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.phase == .failed(.wrongPassword) }
        XCTAssertEqual(store.state.activeServerId, 1)

        await store.connectServer(1, password: "open sesame", channelPath: ["Lobby"])
        await assertEventually { store.state.showsServer }
        XCTAssertEqual(
            box.engine.connectCalls.last,
            ConnectCall(serverId: 1, password: "open sesame", channelPath: ["Lobby"])
        )
        XCTAssertTrue(box.logs.allSatisfy { !$0.contains("sesame") })
    }

    func testTheTalkModeIsSavedInTheEngine() async {
        let store = await newStore()

        let result = await store.setTalkMode(.pushToTalk)

        XCTAssertNotNil(result.value)
        XCTAssertEqual(box.engine.currentTalkMode, .pushToTalk)
    }

    func testALinkIsOnlyParsedAndABrokenOneIsAnError() async {
        let store = await newStore()

        XCTAssertEqual(
            store.parseLink("mumble://link.example").value,
            MumbleLink(address: "link.example", port: 64738, username: nil, channelPath: [])
        )
        XCTAssertEqual(store.parseLink("https://link.example").failure, .invalidInput)
        XCTAssertEqual(store.state, idle)
        XCTAssertTrue(box.engine.connectCalls.isEmpty)
    }

    func testDismissingAFailureGoesBackToIdle() async {
        let store = await newStore()
        box.engine.connectFailure = .Banned(detail: "reason from the server")
        await store.connectServer(1, password: nil, channelPath: [])
        XCTAssertEqual(store.state.phase, .failed(.banned))

        store.dismissFailure()

        XCTAssertEqual(store.state, idle)
    }
}
