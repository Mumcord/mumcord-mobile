// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// How long the user may be alone in a channel before the warning, and how long the warning
/// gives them (FR-5.42). The product's times are fixed; only a test shortens them.
struct IdleTimes: Equatable, Sendable {
    var alone: Duration = .seconds(30 * 60)
    var warning: Duration = .seconds(60)
}

/// Where the "disconnect when alone" rule stands. All times are on the clock of the caller.
enum IdleState: Equatable {
    /// Not alone, not connected, or the setting is off.
    case resting
    case alone(since: Duration)
    /// The warning is shown; at `until` the app disconnects.
    case warned(until: Duration)
}

enum IdleEffect: Equatable {
    case warn
    case unwarn
    case disconnect
}

extension IdleState {
    /// The rule as a value (FR-5.42): alone for `IdleTimes.alone` warns, another
    /// `IdleTimes.warning` without a reaction disconnects, and anything that ends being alone
    /// (someone joins, the user leaves, the setting is switched off) starts over.
    ///
    /// - Parameter alone: the setting is on and the user is connected and alone in their channel.
    func step(alone: Bool, now: Duration, times: IdleTimes) -> (IdleState, IdleEffect?) {
        guard alone else {
            if case .warned = self { return (.resting, .unwarn) }
            return (.resting, nil)
        }
        switch self {
        case .resting:
            return (.alone(since: now), nil)
        case .alone(let since) where now >= since + times.alone:
            return (.warned(until: now + times.warning), .warn)
        case .warned(let until) where now >= until:
            return (.resting, .disconnect)
        default:
            return (self, nil)
        }
    }

    /// "Stay connected": the warning goes away and the time alone starts over.
    func stay(now: Duration) -> (IdleState, IdleEffect?) {
        if case .warned = self { return (.alone(since: now), .unwarn) }
        return (self, nil)
    }

    /// When the rule has to be looked at again, or nil while there is nothing to wait for.
    func wakeAt(_ times: IdleTimes) -> Duration? {
        switch self {
        case .resting: nil
        case .alone(let since): since + times.alone
        case .warned(let until): until
        }
    }
}

/// Whether the user is connected and the only one in their channel.
func aloneInChannel(_ state: SessionState) -> Bool {
    guard state.phase == .connected, let server = state.server, let channel = server.selfChannelId else {
        return false
    }
    return server.people.values.filter { $0.channelId == channel }.count == 1
}

/// The session that "disconnect when alone" ended, for the notice in the app. `serverName` is
/// the name the user gave the server.
struct IdleNotice: Equatable {
    let serverId: Int64
    let serverName: String
}

/// The two notifications of "disconnect when alone" (FR-5.42).
protocol IdleNotifier: AnyObject {
    /// "Disconnecting in 1 minute", with the action "Stay connected".
    func showWarning()
    func hideWarning()
    /// Says that the app disconnected because the user was alone.
    func showDisconnected()
    func hideDisconnected()
}

/// What the screens need of "disconnect when alone".
@MainActor
protocol IdleAccess: AnyObject {
    /// Set after the app disconnected by itself, until `dismiss`.
    var notice: IdleNotice? { get }

    /// The current notice and every later one.
    func notices() -> AsyncStream<IdleNotice?>

    /// The user has seen the notice; the notification about it goes too.
    func dismiss()
}

/// Runs the rule of `IdleState.step` for the session: warns with a notification, disconnects
/// when nobody reacts and says so (FR-5.42). It follows the session by itself once it has been
/// created, and lives as long as the process.
///
/// The timer is a wait in the app's own process on the continuous clock, which goes on while
/// the phone sleeps. That is enough: a session only exists while the call (or the audio
/// background mode) keeps the app running, and its connection needs the app awake for a
/// keep-alive every few seconds anyway, so a phone on which this wait could not run has lost
/// the session already. A notification scheduled with the system instead could warn, but
/// could not disconnect when its minute is over.
///
/// Nothing runs while the user is not alone or the setting is off (FR-5.43).
@MainActor
final class IdleDisconnect: IdleAccess {
    private let store: SessionStore
    private let notifier: IdleNotifier
    private let times: () -> IdleTimes
    private let now: () -> Duration
    private var state = IdleState.resting
    private var alone = false
    private var wait: Task<Void, Never>?
    private var observers: [UUID: AsyncStream<IdleNotice?>.Continuation] = [:]

    private(set) var notice: IdleNotice? {
        didSet {
            for observer in observers.values { observer.yield(notice) }
        }
    }

    /// - Parameters:
    ///   - times: read whenever a wait begins.
    ///   - now: the time on a clock that goes on while the phone sleeps.
    init(
        store: SessionStore,
        notifier: IdleNotifier,
        times: @escaping () -> IdleTimes = { IdleTimes() },
        now: @escaping () -> Duration = IdleDisconnect.continuousNow
    ) {
        self.store = store
        self.notifier = notifier
        self.times = times
        self.now = now
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                let alone = state.mobileSettings?.idleDisconnect == true && aloneInChannel(state)
                if alone != self.alone {
                    self.alone = alone
                    self.run()
                }
            }
        }
    }

    private nonisolated static let origin = ContinuousClock.now

    /// The time since the start of the process on the continuous clock.
    nonisolated static func continuousNow() -> Duration {
        ContinuousClock.now - origin
    }

    func notices() -> AsyncStream<IdleNotice?> {
        let id = UUID()
        return AsyncStream { continuation in
            observers[id] = continuation
            continuation.yield(notice)
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.observers[id] = nil }
            }
        }
    }

    /// The "Stay connected" button of the warning.
    func stayConnected() {
        apply(state.stay(now: now()))
        run()
    }

    func dismiss() {
        notice = nil
        notifier.hideDisconnected()
    }

    /// Steps the rule and waits until it has to be looked at again.
    private func run() {
        wait?.cancel()
        wait = nil
        apply(state.step(alone: alone, now: now(), times: times()))
        guard let wakeAt = state.wakeAt(times()) else { return }
        let delay = max(wakeAt - now(), .zero)
        wait = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.run()
        }
    }

    private func apply(_ step: (IdleState, IdleEffect?)) {
        state = step.0
        switch step.1 {
        case .warn: notifier.showWarning()
        case .unwarn: notifier.hideWarning()
        case .disconnect: Task { await disconnect() }
        case nil: break
        }
    }

    private func disconnect() async {
        let session = store.state
        notifier.hideWarning()
        await store.disconnect()
        notifier.showDisconnected()
        if let serverId = session.activeServerId {
            notice = IdleNotice(
                serverId: serverId,
                serverName: session.servers.first { $0.id == serverId }?.name ?? ""
            )
        }
    }
}
