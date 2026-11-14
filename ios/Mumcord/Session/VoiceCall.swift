// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import UIKit

/// Connects the voice session to the phone: the call the system shows for it (`CallSession`),
/// what phone calls and other interruptions do to the session, and the note on disk that
/// tells a later start that a session was cut off. It follows the session by itself once it
/// has been created, and lives as long as the process.
@MainActor
final class VoiceCall {
    private let markers: SessionMarkerStore
    private let interruptions: Interruptions
    private let session: CallSession
    private var sessionEnded: SessionEndedNotice?
    private var shownCall: CallInfo?
    private var lastMarkerStep = MarkerStep.keep

    /// - Parameters:
    ///   - callSystem: the phone's call service, or nil when sessions are not shown to the
    ///     phone as calls (`CallSystems.make`).
    ///   - prepareAudio: sets the audio session up for voice without starting it.
    ///   - log: receives short technical messages without personal data.
    init(
        store: SessionStore,
        audio: VoiceAudio,
        callSystem: CallSystem?,
        prepareAudio: @escaping () -> Void,
        markers: SessionMarkerStore,
        log: @escaping @Sendable (String) -> Void
    ) {
        self.markers = markers
        // Read before the first state of this run can overwrite what the last run left behind.
        sessionEnded = markers.takeNotice()
        let control = SessionVoiceControl(store: store)
        let interruptions = Interruptions(current: { control.current() }) { effect in
            switch effect {
            case .setVoice(let voice): await control.set(voice)
            case .pausePlayback(let paused): audio.setPlaybackPaused(paused)
            }
        }
        self.interruptions = interruptions
        session = CallSession(
            system: callSystem,
            prepareAudio: prepareAudio,
            onMuted: control.setMuted,
            onLeave: control.leave,
            onInterruption: interruptions.send,
            onActivation: audio.setActivation,
            log: log
        )
        // Sent to an app that is running when the user removes it from the app switcher: a
        // run the user ended themselves gets no notice at the next start.
        _ = NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { _ in
            markers.clear()
        }
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                self.follow(state)
            }
        }
    }

    /// The cut-off session of the last run, once; nil when there was none.
    func takeSessionEndedNotice() -> SessionEndedNotice? {
        defer { sessionEnded = nil }
        return sessionEnded
    }

    /// The app is on screen again: an interruption that never reported its end is over.
    func onAppActive() {
        interruptions.send(.interruptionEnded(shouldResume: true))
    }

    /// The phone's audio session reported something; called on a thread of the system.
    nonisolated func onAudioSignal(_ signal: AudioSessionSignal) {
        switch signal {
        case .interruptionBegan: interruptions.send(.interruptionBegan)
        case .interruptionEnded(let shouldResume): interruptions.send(.interruptionEnded(shouldResume: shouldResume))
        case .routeChanged: interruptions.send(.routeChanged)
        case .servicesReset: interruptions.send(.servicesReset)
        }
    }

    private func follow(_ state: SessionState) {
        let call = callInfo(state)
        if call != shownCall {
            shownCall = call
            session.follow(call)
        }
        let step = markerStep(state)
        guard step != lastMarkerStep else { return }
        lastMarkerStep = step
        switch step {
        case .mark(let marker): markers.mark(marker)
        case .clear: markers.clear()
        case .keep: break
        }
    }
}
