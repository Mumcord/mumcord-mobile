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

/// What the phone has to be told while voice runs. The one place for it: audio routing and call
/// integration of later tasks belong here too.
protocol VoiceSession: AnyObject {
    /// Called before the unit opens. False when the phone does not let the session start now.
    func begin() -> Bool
    /// Called after the unit closed; gives the audio back to other apps.
    func end()
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

    init(log: @escaping (String) -> Void) {
        self.log = log
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: nil
        ) { [weak self] notification in
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            switch raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) {
            case .began: self?.onEvent?(.interruptionBegan)
            case .ended: self?.onEvent?(.interruptionEnded)
            default: break
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: nil
        ) { [weak self] _ in
            self?.onEvent?(.servicesReset)
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }

    func begin() -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            // No options: the voice-chat mode already allows Bluetooth hands-free headsets, and
            // the output stays where the system puts a call (the earpiece, or a connected
            // headset). Choosing the speaker is the audio output task.
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [])
            // Wishes only: the device decides, and the audio unit converts what differs.
            try? session.setPreferredSampleRate(Self.sampleRate)
            try? session.setPreferredIOBufferDuration(Self.bufferDuration)
            try session.setActive(true)
            return true
        } catch {
            log("starting the voice session failed (\((error as NSError).code))")
            return false
        }
    }

    func end() {
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            log("ending the voice session failed (\((error as NSError).code))")
        }
    }
}
