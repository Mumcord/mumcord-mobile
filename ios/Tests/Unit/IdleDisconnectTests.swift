// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import XCTest

@testable import Mumcord

/// "Disconnect when alone" (FR-5.42, AC-5.30): the rule as a value, and the runner against the
/// store with a `FakeEngine` and times shortened to milliseconds.
@MainActor
final class IdleDisconnectTests: XCTestCase {
    private let times = IdleTimes(alone: .seconds(30 * 60), warning: .seconds(60))

    // MARK: The rule

    func testAloneForThirtyMinutesWarnsAndAMinuteLaterDisconnects() {
        var (state, effect) = IdleState.resting.step(alone: true, now: .seconds(0), times: times)
        XCTAssertEqual(state, .alone(since: .seconds(0)))
        XCTAssertNil(effect)
        (state, effect) = state.step(alone: true, now: .seconds(29 * 60), times: times)
        XCTAssertNil(effect)
        XCTAssertEqual(state.wakeAt(times), .seconds(30 * 60))
        (state, effect) = state.step(alone: true, now: .seconds(30 * 60), times: times)
        XCTAssertEqual(state, .warned(until: .seconds(31 * 60)))
        XCTAssertEqual(effect, .warn)
        (state, effect) = state.step(alone: true, now: .seconds(31 * 60), times: times)
        XCTAssertEqual(state, .resting)
        XCTAssertEqual(effect, .disconnect)
    }

    func testStayConnectedStartsTheTimeAloneOver() {
        let (state, effect) = IdleState.warned(until: .seconds(100)).stay(now: .seconds(50))
        XCTAssertEqual(state, .alone(since: .seconds(50)))
        XCTAssertEqual(effect, .unwarn)
        XCTAssertNil(IdleState.alone(since: .seconds(1)).stay(now: .seconds(2)).1)
    }

    func testNotBeingAloneAnyMoreTakesTheWarningBack() {
        XCTAssertEqual(IdleState.warned(until: .seconds(9)).step(alone: false, now: .seconds(1), times: times).1, .unwarn)
        XCTAssertNil(IdleState.alone(since: .seconds(0)).step(alone: false, now: .seconds(1), times: times).1)
        XCTAssertNil(IdleState.resting.wakeAt(times))
    }

    func testAloneMeansConnectedAndTheOnlyOneInTheOwnChannel() {
        var state = SessionState()
        state.phase = .connected
        state.server = ServerModel(
            selfSession: 10,
            channels: [0: channel(0, "Root"), 1: channel(1, "Games", parentId: 0)],
            people: [10: person(10, "Me", channelId: 0), 11: person(11, "Anna", channelId: 1)]
        )
        XCTAssertTrue(aloneInChannel(state))
        state.server = state.server?.with(people: [10: person(10, "Me", channelId: 1), 11: person(11, "Anna", channelId: 1)])
        XCTAssertFalse(aloneInChannel(state))
        state.phase = .reconnecting(Reconnecting(attempt: 1, maxAttempts: 10))
        XCTAssertFalse(aloneInChannel(state))
    }

    // MARK: The runner

    private let box = EngineBox()
    private let short = IdleTimes(alone: .milliseconds(200), warning: .milliseconds(200))

    private func connected(alone: Bool, idleOn: Bool = true) async -> (SessionStore, FakeIdleNotifier, IdleDisconnect) {
        let others = alone ? 1 : 0
        let store = makeStore(
            box,
            server: ServerState(
                selfSession: 10,
                channels: [channel(0, "Root"), channel(1, "Games", parentId: 0)],
                people: [person(10, "Me", channelId: 0), person(11, "Anna", channelId: UInt32(others))]
            ),
            servers: [savedServer(1, "Nordlicht")],
            configure: { $0.storedMobileSettings.idleDisconnect = idleOn }
        )
        await store.loadServers()
        let notifier = FakeIdleNotifier()
        let short = self.short
        let idle = IdleDisconnect(store: store, notifier: notifier, times: { short })
        await store.connectServer(1, password: nil, channelPath: [])
        await assertEventually { store.state.showsServer }
        return (store, notifier, idle)
    }

    func testWithoutAReactionTheAppDisconnectsAndSaysSo() async {
        let (store, notifier, idle) = await connected(alone: true)
        await assertEventually { notifier.calls.contains("warn") }
        await assertEventually { store.state.phase == .idle }
        await assertEventually { idle.notice == IdleNotice(serverId: 1, serverName: "Nordlicht") }
        XCTAssertEqual(notifier.calls, ["warn", "unwarn", "disconnected"])
        idle.dismiss()
        XCTAssertNil(idle.notice)
        XCTAssertEqual(notifier.calls.last, "undisconnected")
    }

    func testStayConnectedKeepsTheSession() async {
        let (store, notifier, idle) = await connected(alone: true)
        await assertEventually { notifier.calls.contains("warn") }
        idle.stayConnected()
        XCTAssertEqual(notifier.calls, ["warn", "unwarn"])
        // The time alone starts over: the next warning comes, and the session is still there.
        await assertEventually { notifier.calls == ["warn", "unwarn", "warn"] }
        XCTAssertTrue(store.state.showsServer)
        XCTAssertNil(idle.notice)
    }

    func testNothingRunsWithSomeoneElseInTheChannelOrWithTheSettingOff() async {
        let (store, notifier, idle) = await connected(alone: false)
        try? await Task.sleep(nanoseconds: 600_000_000)
        XCTAssertTrue(notifier.calls.isEmpty)
        XCTAssertTrue(store.state.showsServer)
        withExtendedLifetime(idle) {}
    }

    func testTheSettingOffDoesNothing() async {
        let (store, notifier, idle) = await connected(alone: true, idleOn: false)
        try? await Task.sleep(nanoseconds: 600_000_000)
        XCTAssertTrue(notifier.calls.isEmpty)
        XCTAssertTrue(store.state.showsServer)
        withExtendedLifetime(idle) {}
    }
}

/// Records what the runner asked of the notifications.
final class FakeIdleNotifier: IdleNotifier {
    private(set) var calls: [String] = []

    func showWarning() { calls.append("warn") }
    func hideWarning() { calls.append("unwarn") }
    func showDisconnected() { calls.append("disconnected") }
    func hideDisconnected() { calls.append("undisconnected") }
}
