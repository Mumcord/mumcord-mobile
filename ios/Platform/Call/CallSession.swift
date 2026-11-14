// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// The phone's call service as the voice session uses it. A seam, so the rules of
/// `CallSession` can be tested without the system. Everything happens on the main thread.
protocol CallSystem: AnyObject {
    var delegate: CallSystemDelegate? { get set }
    /// Asks the system for an outgoing call named `name` that counts as connected at once.
    /// `completion` says whether the system took it.
    func start(_ call: UUID, name: String, completion: @escaping (Bool) -> Void)
    func rename(_ call: UUID, name: String)
    /// Tells the system that the user muted or unmuted in the app.
    func setMuted(_ call: UUID, muted: Bool)
    /// Takes the call off hold.
    func unhold(_ call: UUID)
    /// Ends the call because the session is over.
    func end(_ call: UUID)
}

/// What the phone's call service tells about the session's call.
protocol CallSystemDelegate: AnyObject {
    /// The user ended the call outside the app: the system's call screen, a car's hang-up button.
    func callEndedBySystem(_ call: UUID)
    /// The mute button of the system's call screen.
    func callMutedBySystem(_ call: UUID, muted: Bool)
    /// The system put the call on hold, or took it off hold.
    func callHeldBySystem(_ call: UUID, held: Bool)
    /// The system gave the audio to the call, or took it away.
    func callAudioChanged(active: Bool)
    /// No other call is left on the phone.
    func otherCallsEnded()
    /// The system dropped every call of the app.
    func callSystemReset()
}

/// Shows a voice session to the phone system as an ongoing call (FR-5.31, FR-5.32), which
/// keeps it running in the background and with a locked screen, gives it the system's call
/// screen with mute and end, and lets the system hold it for a phone call.
///
/// Without a call system, or when the system refuses the call, the session is no call to the
/// phone: its audio then runs as the app's own audio session, which also goes on in the
/// background. Nothing is shown by the system in that case.
///
/// Used on the main thread only.
final class CallSession: CallSystemDelegate {
    /// How long the system may take to give the audio to a call it has accepted before the
    /// app starts the audio itself, so that a call the system never activates is not silent.
    static let activationTimeout: TimeInterval = 5

    private struct Call {
        let id: UUID
        /// False until the system has taken the call.
        var running = false
    }

    private let makeSystem: (() -> CallSystem)?
    private let prepareAudio: () -> Void
    private let onMuted: (Bool) -> Void
    private let onLeave: () -> Void
    private let onInterruption: (InterruptionEvent) -> Void
    private let onActivation: (AudioActivation) -> Void
    private let after: (TimeInterval, @escaping () -> Void) -> Void
    private let log: (String) -> Void

    private var system: CallSystem?
    private var shown: CallInfo?
    private var call: Call?
    private var held = false
    /// The system has given the audio to the current call at least once.
    private var activated = false

    /// - Parameters:
    ///   - makeSystem: creates the phone's call service when the first session begins (the
    ///     system wants it only once per process), or nil when sessions are not shown as calls.
    ///   - prepareAudio: sets the audio session up for voice without starting it.
    ///   - onMuted: the system's mute button asks to mute or unmute.
    ///   - onLeave: the call was ended outside the app; leaves the server.
    ///   - onInterruption: receives what the system does to the call; see `Interruptions`.
    ///   - onActivation: says who starts the audio, see `AudioActivation`.
    ///   - after: runs work on the main thread after some seconds.
    init(
        makeSystem: (() -> CallSystem)?,
        prepareAudio: @escaping () -> Void,
        onMuted: @escaping (Bool) -> Void,
        onLeave: @escaping () -> Void,
        onInterruption: @escaping (InterruptionEvent) -> Void,
        onActivation: @escaping (AudioActivation) -> Void,
        after: @escaping (TimeInterval, @escaping () -> Void) -> Void = { seconds, work in
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
        },
        log: @escaping (String) -> Void
    ) {
        self.makeSystem = makeSystem
        self.prepareAudio = prepareAudio
        self.onMuted = onMuted
        self.onLeave = onLeave
        self.onInterruption = onInterruption
        self.onActivation = onActivation
        self.after = after
        self.log = log
    }

    /// The session changed; nil while there is none.
    func follow(_ next: CallInfo?) {
        let previous = shown
        shown = next
        guard let next else {
            if previous != nil { end() }
            return
        }
        guard let previous else {
            begin(next)
            return
        }
        guard let call, call.running else { return }
        if next.title != previous.title { system?.rename(call.id, name: next.title) }
        if next.muted != previous.muted { system?.setMuted(call.id, muted: next.muted) }
    }

    private func begin(_ first: CallInfo) {
        guard let system = callSystem() else { return }
        let id = UUID()
        call = Call(id: id)
        activated = false
        // From here on the system starts the audio: an audio session the app has started
        // itself would keep the system from giving the audio to the call.
        onActivation(.callWaiting)
        prepareAudio()
        system.start(id, name: first.title) { [weak self] accepted in
            guard let self else { return }
            guard self.call?.id == id else {
                // The session ended while the system was still thinking about its call.
                if accepted { system.end(id) }
                return
            }
            guard accepted else {
                self.log("the phone system did not take the voice session as a call")
                self.call = nil
                self.onActivation(.own)
                return
            }
            self.call?.running = true
            // What changed while the system was still thinking.
            if let shown = self.shown {
                if shown.title != first.title { system.rename(id, name: shown.title) }
                if shown.muted { system.setMuted(id, muted: true) }
            }
            self.after(Self.activationTimeout) { [weak self] in
                guard let self, self.call?.id == id, !self.activated, !self.held else { return }
                self.log("the phone system did not start the audio of the call")
                self.onActivation(.own)
            }
        }
    }

    private func callSystem() -> CallSystem? {
        if system == nil, let makeSystem {
            let created = makeSystem()
            created.delegate = self
            system = created
        }
        return system
    }

    private func end() {
        if let call {
            if call.running { system?.end(call.id) }
            self.call = nil
            onActivation(.own)
        }
        held = false
        onInterruption(.sessionEnded)
    }

    // MARK: CallSystemDelegate

    func callEndedBySystem(_ id: UUID) {
        guard call?.id == id else { return }
        call = nil
        held = false
        // Until the server has been left the session's audio is the app's own again.
        onActivation(.own)
        onLeave()
    }

    func callMutedBySystem(_ id: UUID, muted: Bool) {
        // The system also reports back what the app itself has just told it.
        guard call?.id == id, let shown, shown.muted != muted else { return }
        onMuted(muted)
    }

    func callHeldBySystem(_ id: UUID, held: Bool) {
        guard call?.id == id else { return }
        self.held = held
        onInterruption(.callHeld(held))
    }

    func callAudioChanged(active: Bool) {
        guard call != nil else { return }
        if active { activated = true }
        onActivation(active ? .callActive : .callWaiting)
    }

    func otherCallsEnded() {
        // The system may leave the session's call on hold when the phone call that held it is
        // over; it is taken off hold from here.
        guard let call, call.running, held else { return }
        system?.unhold(call.id)
    }

    func callSystemReset() {
        guard call != nil else { return }
        log("the phone system dropped the call of the voice session")
        call = nil
        onActivation(.own)
        if held {
            held = false
            onInterruption(.callHeld(false))
        }
    }
}
