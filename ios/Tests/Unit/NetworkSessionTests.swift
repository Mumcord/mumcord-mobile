// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// Getting back in after a network change and the voice bitrate on mobile data (FR-5.26,
/// FR-5.40), with the store against a `FakeEngine` and a network set by hand.
@MainActor
final class NetworkSessionTests: XCTestCase {
    private let server = ServerState(
        selfSession: 10,
        channels: [channel(0, "Root"), channel(1, "Games", parentId: 0), channel(2, "Chess", parentId: 1)],
        people: [person(10, "Me", channelId: 2), person(11, "Anna", channelId: 2)]
    )
    private let box = EngineBox()

    private func connected(password: String? = nil, network: NetworkStatus = fakeWifi) async -> (SessionStore, FakeNetworkMonitor, NetworkSession) {
        let store = makeStore(box, server: server, servers: [savedServer(1, "Nordlicht")])
        await store.loadServers()
        let monitor = FakeNetworkMonitor(network)
        let session = NetworkSession(store: store, monitor: monitor)
        await store.connectServer(1, password: password, channelPath: [])
        await assertEventually { store.state.showsServer }
        return (store, monitor, session)
    }

    private func lose(_ attempt: UInt32) {
        box.engine.emit(.connectionChanged(state: .reconnecting(attempt: attempt, maxAttempts: 10, nextRetrySecs: 30)))
    }

    func testANewNetworkAtTheThirdAttemptGetsBackIntoTheSameChannelWithTheSameMute() async {
        let (store, monitor, session) = await connected(password: "typed once")
        await store.setSelfMute(true)
        await assertEventually { selfVoice(store.state)?.muted == true }
        lose(3)
        await assertEventually { isReconnecting(store) }

        monitor.status = fakeMobileData

        await assertEventually { self.box.engine.connectCalls.count == 2 }
        let again = box.engine.connectCalls[1]
        XCTAssertEqual(again.channelPath, ["Games", "Chess"])
        XCTAssertEqual(again.password, "typed once")
        await assertEventually { store.state.phase == .connected && selfVoice(store.state)?.muted == true }
        withExtendedLifetime(session) {}
    }

    func testTheStateStaysReconnectingWhileTheAppGetsBackIn() async {
        let (store, _, _) = await connected()
        lose(4)
        await assertEventually { isReconnecting(store) }
        let states = store.states()
        let watcher = Task {
            var phases: [ConnectionPhase] = []
            for await state in states {
                phases.append(state.phase)
                if state.phase == .connected { return phases }
            }
            return phases
        }
        await store.reconnectNow()
        await assertEventually { store.state.phase == .connected }
        let seen = await watcher.value
        XCTAssertFalse(seen.contains(.idle))
        XCTAssertFalse(seen.contains(.connecting))
        XCTAssertEqual(seen.last, .connected)
    }

    func testAnEarlierAttemptIsLeftToTheEngine() async {
        let (store, monitor, session) = await connected()
        lose(2)
        await assertEventually { isReconnecting(store) }
        monitor.status = fakeMobileData
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(box.engine.connectCalls.count, 1)
        withExtendedLifetime(session) {}
    }

    func testNothingHappensWithoutAReconnectAndNeverTwiceAtATime() async {
        let (store, _, _) = await connected()
        await store.reconnectNow()
        XCTAssertEqual(box.engine.connectCalls.count, 1)
        lose(3)
        await assertEventually { isReconnecting(store) }
        async let first: Void = store.reconnectNow()
        async let second: Void = store.reconnectNow()
        _ = await (first, second)
        XCTAssertEqual(box.engine.connectCalls.count, 2)
    }

    func testWhenTheServerCannotBeReachedTheConnectionEndsAsLost() async {
        let (store, _, _) = await connected()
        lose(3)
        await assertEventually { isReconnecting(store) }
        box.engine.connectFailure = .ServerUnreachable(detail: "gone")
        await store.reconnectNow()
        XCTAssertEqual(store.state.phase, .failed(.serverUnreachable))
        XCTAssertNil(store.state.server)
    }

    func testReducedVoiceLimitsTheBitrateOnMobileDataAndIsSetAgainAfterEveryStart() async {
        let (store, monitor, session) = await connected(network: fakeMobileData)
        _ = await store.updateMobileSettings { $0.mobileDataReducedVoice = true }
        await assertEventually { self.box.engine.bitrateLimits.last == .some(24) }
        monitor.status = fakeWifi
        await assertEventually { self.box.engine.bitrateLimits.last == .some(nil) }
        monitor.status = fakeMobileData
        await assertEventually { self.box.engine.bitrateLimits.last == .some(24) }
        let before = box.engine.bitrateLimits.count
        await store.disconnect()
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer && self.box.engine.bitrateLimits.count > before }
        XCTAssertEqual(box.engine.bitrateLimits.last, .some(24))
        withExtendedLifetime(session) {}
    }

    func testTheLogNamesNoPassword() async {
        let (store, _, _) = await connected(password: "typed once")
        lose(3)
        await assertEventually { isReconnecting(store) }
        box.engine.connectFailure = .WrongPassword(detail: "typed once")
        await store.reconnectNow()
        XCTAssertFalse(box.logs.contains { $0.contains("typed once") })
    }
}

private func isReconnecting(_ store: SessionStore) -> Bool {
    if case .reconnecting = store.state.phase { return true }
    return false
}
