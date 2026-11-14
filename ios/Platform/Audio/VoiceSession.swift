// SPDX-License-Identifier: AGPL-3.0-only

import AVFAudio

/// What the phone says about the voice session while it runs.
enum VoiceSessionEvent: Equatable {
    /// Something else took the audio (a phone call, an alarm): the unit must stop.
    case interruptionBegan
    /// The interruption is over; the session may be started again.
    case interruptionEnded
    /// The system's audio service was restarted: everything has to be set up again.
    case servicesReset
}

/// What the phone's audio session reports while voice runs, as the system says it. What
/// follows from a signal is decided by the interruption rules (`InterruptionState`).
enum AudioSessionSignal: Equatable {
    case interruptionBegan
    /// `shouldResume` is the system's advice whether to go on playing.
    case interruptionEnded(shouldResume: Bool)
    case routeChanged
    case servicesReset
}

/// What the phone has to be told while voice runs. The one place for it: audio routing and call
/// integration of later tasks belong here too.
protocol VoiceSession: AnyObject {
    /// Called before the unit opens. False when the phone does not let the session start now.
    func begin() -> Bool
    /// Called after the unit closed; gives the audio back to other apps.
    func end()
    /// True while the session belongs to a call that the system starts and stops itself:
    /// `begin` then only sets the session up and `end` leaves it alone. Set by the controller.
    var systemActivates: Bool { get set }
    /// Told on a thread of the system; what follows from an event is decided elsewhere.
    var onEvent: ((VoiceSessionEvent) -> Void)? { get set }
}

/// Puts the phone into its voice-chat audio mode, which selects the system's voice processing
/// (echo cancellation, the call volume) for the audio unit.
final class SystemVoiceSession: VoiceSession {
    /// The engine's rate, so the system does not have to convert where the device allows it.
    private static let sampleRate = 48_000.0
    /// 10 ms: short enough for a conversation, long enough to be cheap.
    private static let bufferDuration = 0.01

    private let log: (String) -> Void
    private var observers: [NSObjectProtocol] = []
    var onEvent: ((VoiceSessionEvent) -> Void)?
    var systemActivates = false
    /// Receives every signal of the system, on a thread of the system. Interruptions are only
    /// reported here: whether they stop the audio is decided by the interruption rules.
    var onSignal: ((AudioSessionSignal) -> Void)?

    init(log: @escaping (String) -> Void) {
        self.log = log
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: nil
        ) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            switch raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) {
            case .began:
                self?.onSignal?(.interruptionBegan)
            case .ended:
                let options = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let shouldResume = AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume)
                self?.onSignal?(.interruptionEnded(shouldResume: shouldResume))
            default:
                break
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: session, queue: nil
        ) { [weak self] _ in
            self?.onSignal?(.routeChanged)
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: nil
        ) { [weak self] _ in
            self?.onEvent?(.servicesReset)
            self?.onSignal?(.servicesReset)
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }

    /// Sets the session up for voice without starting it. Called on its own before a call is
    /// asked for, so the system finds the session ready when it starts it for the call.
    @discardableResult
    func prepare() -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            // No options: the voice-chat mode already allows Bluetooth hands-free headsets, and
            // the output stays where the system puts a call (the earpiece, or a connected
            // headset). Choosing the speaker is the audio output task.
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [])
            // Wishes only: the device decides, and the audio unit converts what differs.
            try? session.setPreferredSampleRate(Self.sampleRate)
            try? session.setPreferredIOBufferDuration(Self.bufferDuration)
            return true
        } catch {
            log("setting up the voice session failed (\((error as NSError).code))")
            return false
        }
    }

    func begin() -> Bool {
        guard prepare() else { return false }
        // The session of a call is started by the system, never by the app.
        if systemActivates { return true }
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            return true
        } catch {
            log("starting the voice session failed (\((error as NSError).code))")
            return false
        }
    }

    func end() {
        if systemActivates { return }
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            log("ending the voice session failed (\((error as NSError).code))")
        }
    }
}
