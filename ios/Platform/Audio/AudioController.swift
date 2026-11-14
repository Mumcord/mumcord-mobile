// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// A piece of work that waits for its time and can be called off.
protocol AudioTimer: AnyObject {
    func cancel()
}

/// Where the controller does its work: one thing after the other, off the main thread (opening
/// the audio unit takes a moment) and never on an audio thread. A seam, so tests decide when
/// time passes.
protocol AudioScheduler: AnyObject {
    func run(_ work: @escaping () -> Void)
    func after(_ seconds: TimeInterval, _ work: @escaping () -> Void) -> AudioTimer
}

/// The real scheduler: a serial queue of its own.
final class QueueAudioScheduler: AudioScheduler {
    private let queue = DispatchQueue(label: "app.mumcord.mobile.audio")

    func run(_ work: @escaping () -> Void) {
        queue.async(execute: work)
    }

    func after(_ seconds: TimeInterval, _ work: @escaping () -> Void) -> AudioTimer {
        let item = DispatchWorkItem(block: work)
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
        return QueueTimer(item: item)
    }

    private final class QueueTimer: AudioTimer {
        private let item: DispatchWorkItem

        init(item: DispatchWorkItem) {
            self.item = item
        }

        func cancel() {
            item.cancel()
        }
    }
}

/// Runs the speaker and the microphone exactly while the engine wants them. The app only says
/// what is wanted (`setWanted`) and where the samples go (`setEndpoints`); `onState` says what
/// came of it.
///
/// The microphone is opened only with the user's permission; without it the state says so and
/// the speaker keeps running. A unit that cannot be opened is tried again a few times before it
/// counts as failed; a microphone that stays unusable does not take listening with it.
///
/// While only listening is wanted the unit is opened without its microphone side, so the phone
/// does not hold the microphone (and shows no recording indicator) for a muted user.
final class AudioController {
    /// Waits before the second, third and fourth try to open the unit.
    static let retryDelays: [TimeInterval] = [0.25, 1, 2]

    private let backend: StreamBackend
    private let session: VoiceSession
    private let microphoneGranted: () -> Bool
    private let scheduler: AudioScheduler
    private let log: (String) -> Void
    private let onState: (AudioState) -> Void

    private var state = AudioState()
    private var wantedPlayback = false
    private var wantedCapture = false
    /// Nil until the engine exists and has said where its samples go.
    private var handles: (playback: UInt64, capture: UInt64)?
    /// What the open unit does, or nil while none is open.
    private var opened: StreamRequest?
    private var retry: (request: StreamRequest, timer: AudioTimer)?
    private var sessionActive = false
    /// Between the begin and the end of an interruption by the phone.
    private var interrupted = false
    /// The unit could not be opened with the microphone, or not at all, after every retry.
    private var captureFailed = false
    private var playbackFailed = false

    /// - Parameters:
    ///   - microphoneGranted: whether the user allows the microphone right now.
    ///   - log: receives short technical messages without personal data.
    ///   - onState: called on the scheduler with every new state.
    init(
        backend: StreamBackend,
        session: VoiceSession,
        microphoneGranted: @escaping () -> Bool,
        scheduler: AudioScheduler,
        log: @escaping (String) -> Void,
        onState: @escaping (AudioState) -> Void
    ) {
        self.backend = backend
        self.session = session
        self.microphoneGranted = microphoneGranted
        self.scheduler = scheduler
        self.log = log
        self.onState = onState
        session.onEvent = { [weak self] event in
            scheduler.run { self?.handle(event) }
        }
    }

    /// The engine's handles for the two directions (`MumcordEngine.audioEndpoints()`).
    func setEndpoints(capture: UInt64, playback: UInt64) {
        scheduler.run {
            self.handles = (playback, capture)
            self.forgetFailures()
            self.reconcile()
        }
    }

    /// What the engine wants to run right now (`AudioWanted`); each direction on its own.
    func setWanted(playback: Bool, capture: Bool) {
        scheduler.run {
            // A new wish is a new chance for a direction that had failed.
            if playback != self.wantedPlayback { self.playbackFailed = false }
            if capture != self.wantedCapture { self.captureFailed = false }
            self.wantedPlayback = playback
            self.wantedCapture = capture
            self.reconcile()
        }
    }

    /// The app is on screen again: the microphone permission may have changed in the system
    /// settings, and an interruption that never reported its end is over.
    func refresh() {
        scheduler.run {
            self.interrupted = false
            self.forgetFailures()
            self.reconcile()
        }
    }

    private func handle(_ event: VoiceSessionEvent) {
        switch event {
        case .interruptionBegan:
            interrupted = true
        case .interruptionEnded:
            interrupted = false
            forgetFailures()
        case .servicesReset:
            // The unit and the session are gone with the service that ran them.
            log("the audio service was reset, opening the unit again")
            backend.close()
            opened = nil
            sessionActive = false
            forgetFailures()
        }
        reconcile()
    }

    private func forgetFailures() {
        captureFailed = false
        playbackFailed = false
    }

    private var playbackNeeded: Bool { wantedPlayback && handles != nil }
    private var captureNeeded: Bool { wantedCapture && handles != nil }

    /// What the unit should do now, or nil when it should not run.
    private func desired() -> StreamRequest? {
        guard let handles, !interrupted else { return nil }
        let playback = playbackNeeded && !playbackFailed
        let capture = captureNeeded && !captureFailed && microphoneGranted()
        guard playback || capture else { return nil }
        return StreamRequest(
            playbackHandle: handles.playback,
            captureHandle: handles.capture,
            playback: playback,
            capture: capture
        )
    }

    private func reconcile() {
        defer { publish() }
        guard let request = desired() else {
            stop()
            return
        }
        if request == opened || request == retry?.request { return }
        retry?.timer.cancel()
        retry = nil
        open(request, attempt: 0)
    }

    private func stop() {
        retry?.timer.cancel()
        retry = nil
        if opened != nil {
            backend.close()
            opened = nil
        }
        if sessionActive {
            // During an interruption the phone has taken the session away itself.
            if !interrupted { session.end() }
            sessionActive = false
        }
    }

    private func open(_ request: StreamRequest, attempt: Int) {
        if opened != nil {
            backend.close()
            opened = nil
        }
        // Before the unit: the mode decides which device and processing it gets.
        if !sessionActive { sessionActive = session.begin() }
        let result = sessionActive ? backend.open(request) : OpenResult.failed(code: -1, step: 0)
        switch result {
        case .opened(let microphoneOpen):
            opened = request
            if microphoneOpen && !request.capture {
                log("the unit keeps its microphone side open while only listening")
            }
        case .failed(let code, let step):
            log("opening the audio unit failed (\(code) at step \(step)), attempt \(attempt + 1)")
            if attempt < Self.retryDelays.count {
                // A device that is just being switched is often back a moment later.
                let timer = scheduler.after(Self.retryDelays[attempt]) { [weak self] in
                    guard let self, self.retry?.request == request else { return }
                    self.retry = nil
                    self.open(request, attempt: attempt + 1)
                    self.publish()
                }
                retry = (request, timer)
            } else if request.capture {
                // Without the microphone the unit may still play: listening goes on.
                captureFailed = true
                reconcile()
            } else {
                playbackFailed = true
                reconcile()
            }
        }
    }

    private func publish() {
        var next = AudioState()
        if opened?.playback == true {
            next.playback = .running
        } else if playbackNeeded && playbackFailed {
            next.playback = .failed(.speakerUnavailable)
        }
        if captureNeeded {
            if !microphoneGranted() {
                next.capture = .noPermission
            } else if opened?.capture == true {
                next.capture = .running
            } else if captureFailed {
                next.capture = .failed(.microphoneUnavailable)
            }
        }
        guard next != state else { return }
        state = next
        onState(next)
    }
}
