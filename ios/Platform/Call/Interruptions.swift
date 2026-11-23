// SPDX-License-Identifier: AGPL-3.0-only

/// Runs the interruption rules (`InterruptionState`): takes what the phone reports, one event
/// after the other, and has the app carry out what follows. Events may be sent from any thread.
final class Interruptions: @unchecked Sendable {
    private let events: AsyncStream<InterruptionEvent>.Continuation

    /// - Parameters:
    ///   - current: what the user is set to on the server right now, or nil without a session.
    ///   - apply: carries out one effect; the next event waits until it is done.
    init(
        current: @escaping @Sendable () -> SelfVoice?,
        apply: @escaping @Sendable (InterruptionEffect) async -> Void
    ) {
        let (stream, continuation) = AsyncStream.makeStream(of: InterruptionEvent.self)
        events = continuation
        Task.detached {
            var state = InterruptionState()
            for await event in stream {
                let (next, effects) = state.step(event, current: current())
                state = next
                for effect in effects {
                    await apply(effect)
                }
            }
        }
    }

    deinit {
        events.finish()
    }

    func send(_ event: InterruptionEvent) {
        events.yield(event)
    }
}
