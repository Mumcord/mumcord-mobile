// SPDX-License-Identifier: AGPL-3.0-only

/// Whether the user is muted and deafened on the server, as they set it themselves.
struct SelfVoice: Equatable {
    var muted: Bool
    var deafened: Bool

    /// What a phone call makes of the user: deafened, which includes muted.
    static let silenced = SelfVoice(muted: true, deafened: true)
}

/// What the phone reports while a voice session runs.
enum InterruptionEvent: Equatable {
    /// The system put the session's call on hold (the user accepted a phone call), or took it
    /// off hold again.
    case callHeld(Bool)
    /// Something else took the audio for now (an alarm, another app's recording).
    case interruptionBegan
    /// The interruption is over. `shouldResume` is the system's advice for players of music;
    /// a voice session goes on either way, like a call that goes on while music starts.
    case interruptionEnded(shouldResume: Bool)
    /// The sound goes somewhere else now (a headset was plugged in or pulled out).
    case routeChanged
    /// The system's audio service was restarted; an interruption it had reported is gone.
    case servicesReset
    /// The voice session is over; nothing is restored on a server the user has left.
    case sessionEnded
}

/// What the app has to do about an interruption.
enum InterruptionEffect: Equatable {
    /// Mute and deafen on the server, or put back what the user had before.
    case setVoice(SelfVoice)
    /// Stop or resume playing on this phone only; the others see no change.
    case pausePlayback(Bool)
}

/// The rules for interruptions (FR-5.33, FR-5.36) as a value without any iOS in it.
///
/// An accepted phone call (the system holds the session's call) mutes and deafens for its
/// duration and puts the previous state back afterwards, unless the user changed it meanwhile.
/// A call that only rings changes nothing: the system reports nothing for it. An interruption
/// pauses playback on this phone until it is over. A change of the audio route changes nothing.
struct InterruptionState: Equatable {
    /// The system holds the session's call.
    var held = false
    /// What the user had before the phone call, while one is going on.
    var saved: SelfVoice?
    var playbackPaused = false

    /// The state after `event` and what to do now. `current` is what the user is set to on the
    /// server at this moment, or nil while that is unknown.
    func step(_ event: InterruptionEvent, current: SelfVoice?) -> (InterruptionState, [InterruptionEffect]) {
        switch event {
        case .callHeld(let nowHeld):
            return hold(nowHeld, current: current)
        case .interruptionBegan:
            // The phone call silences everything already.
            if held || playbackPaused { return (self, []) }
            return pause(true)
        case .interruptionEnded, .servicesReset:
            return playbackPaused ? pause(false) : (self, [])
        case .routeChanged:
            return (self, [])
        case .sessionEnded:
            return (InterruptionState(), playbackPaused ? [.pausePlayback(false)] : [])
        }
    }

    private func pause(_ paused: Bool) -> (InterruptionState, [InterruptionEffect]) {
        var next = self
        next.playbackPaused = paused
        return (next, [.pausePlayback(paused)])
    }

    private func hold(_ nowHeld: Bool, current: SelfVoice?) -> (InterruptionState, [InterruptionEffect]) {
        var next = self
        next.held = nowHeld
        var effects: [InterruptionEffect] = []
        if !nowHeld && playbackPaused {
            // The system gave the audio back with the call; the end of an interruption that
            // began with the phone call is not always reported.
            next.playbackPaused = false
            effects.append(.pausePlayback(false))
        }
        guard nowHeld != held, let current else { return (next, effects) }
        if nowHeld {
            next.saved = current
            if current != .silenced { effects.append(.setVoice(.silenced)) }
        } else {
            next.saved = nil
            // Only what the phone call itself did is undone: a change by the user stays.
            if let saved, saved != current, current == .silenced { effects.append(.setVoice(saved)) }
        }
        return (next, effects)
    }
}
