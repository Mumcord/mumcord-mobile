// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// What the notification question needs from the system and remembers about the answer.
protocol NotificationGateway: AnyObject, Sendable {
    /// Whether the phone lets the app show notifications, as last read from the system.
    func granted() -> Bool

    /// The question was put once, whatever the answer: it is not put again.
    var asked: Bool { get set }

    /// Shows the system's question; `completion` gets the answer, on any thread.
    func request(_ completion: @escaping @Sendable (Bool) -> Void)

    /// Reads from the system again whether notifications are allowed, then calls `completion`
    /// on any thread. The system only answers this later, never at once.
    func refresh(_ completion: @escaping @Sendable () -> Void)
}

/// What the screens know about the notification question.
struct NotificationState: Equatable {
    /// Whether to say why notifications are wanted, before the system asks.
    var explain: Bool
    /// Whether the phone lets the app show notifications; the Messages tab says so when not.
    var granted: Bool
}

/// The notification question as the screens see it.
protocol NotificationAccess: AnyObject, Sendable {
    var state: NotificationState { get }

    /// The state of the moment and every later one, in order.
    func states() -> AsyncStream<NotificationState>

    /// The user put the explanation away without letting the system ask.
    func notNow()

    /// The user read the explanation: the system asks now.
    func ask()

    /// The app is on screen again: notifications may have been allowed in the system settings.
    func recheck()
}

/// Used where the question is never put, such as screens drawn without a phone behind them.
final class NoNotificationQuestion: NotificationAccess {
    let state = NotificationState(explain: false, granted: true)

    func states() -> AsyncStream<NotificationState> {
        let state = self.state
        return AsyncStream { continuation in
            continuation.yield(state)
            continuation.finish()
        }
    }

    func notNow() {}

    func ask() {}

    func recheck() {}
}

/// Asks once for notifications, when the first session shows what they are for. A "no" changes
/// nothing about the session: messages then only show inside the app.
final class NotificationPrompt: NotificationAccess, @unchecked Sendable {
    private let gateway: NotificationGateway
    private let current: Observed<NotificationState>

    init(gateway: NotificationGateway) {
        self.gateway = gateway
        current = Observed(Self.read(gateway))
        recheck()
    }

    var state: NotificationState { current.value }

    func states() -> AsyncStream<NotificationState> { current.states() }

    func notNow() {
        gateway.asked = true
        current.set(Self.read(gateway))
    }

    func ask() {
        gateway.asked = true
        // The explanation goes away at once; the answer follows when the system has it.
        current.update { NotificationState(explain: false, granted: $0.granted) }
        gateway.request { [weak self] _ in self?.recheck() }
    }

    func recheck() {
        gateway.refresh { [weak self] in
            guard let self else { return }
            self.current.set(Self.read(self.gateway))
        }
    }

    private static func read(_ gateway: NotificationGateway) -> NotificationState {
        let granted = gateway.granted()
        return NotificationState(explain: !granted && !gateway.asked, granted: granted)
    }
}
