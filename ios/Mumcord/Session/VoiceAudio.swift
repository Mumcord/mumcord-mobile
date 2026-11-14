// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// Makes voice audible: runs the phone's speaker and microphone exactly while the engine wants
/// them, and keeps track of the microphone question. It lives as long as the process, so audio
/// goes on whatever the screens do.
final class VoiceAudio: MicrophoneAccess, @unchecked Sendable {
    private let gateway: MicrophoneGateway
    private let lock = NSLock()
    private var controller: AudioController?
    private var captureMissing = false
    private var current = MicrophoneNotice.none
    private var observers: [UUID: AsyncStream<MicrophoneNotice>.Continuation] = [:]

    /// - Parameters:
    ///   - backend, session, scheduler: the phone's audio; seams for tests.
    ///   - log: receives short technical messages without personal data.
    init(
        store: SessionStore,
        gateway: MicrophoneGateway,
        backend: StreamBackend,
        session: VoiceSession,
        scheduler: AudioScheduler,
        log: @escaping @Sendable (String) -> Void
    ) {
        self.gateway = gateway
        let controller = AudioController(
            backend: backend,
            session: session,
            microphoneGranted: { gateway.permission() == .granted },
            scheduler: scheduler,
            log: log,
            onState: { [weak self] state in
                self?.update { $0.captureMissing = state.capture == .noPermission }
            }
        )
        self.controller = controller
        let states = store.states()
        Task.detached {
            var endpointsKnown = false
            var last: AudioWanted?
            for await state in states where state.audio != last {
                let wanted = state.audio
                last = wanted
                if !endpointsKnown, wanted.playback || wanted.capture, let endpoints = store.audioEndpoints() {
                    // The engine exists by now; its handles stay valid for the whole process.
                    controller.setEndpoints(capture: endpoints.capture, playback: endpoints.playback)
                    endpointsKnown = true
                }
                controller.setWanted(playback: wanted.playback, capture: wanted.capture)
            }
        }
    }

    func notices() -> AsyncStream<MicrophoneNotice> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            observers[id] = continuation
            continuation.yield(current)
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.observers[id] = nil
                self.lock.unlock()
            }
        }
    }

    func notNow() {
        update { $0.gateway.declined = true }
    }

    func ask() {
        gateway.request { [weak self] granted in
            guard let self else { return }
            // A "no" to the system is remembered by the system itself; the app's own "Not now"
            // is over either way.
            self.update { $0.gateway.declined = !granted }
            self.controller?.refresh()
        }
    }

    func recheck() {
        // Allowed in the system settings: nothing was declined any more.
        if gateway.permission() == .granted { update { $0.gateway.declined = false } }
        controller?.refresh()
    }

    /// Applies a change and tells the observers when the notice is a different one.
    private func update(_ change: (VoiceAudio) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        change(self)
        let next = microphoneNotice(
            missing: captureMissing,
            declined: gateway.declined,
            permission: gateway.permission()
        )
        guard next != current else { return }
        current = next
        for observer in observers.values {
            observer.yield(next)
        }
    }
}
