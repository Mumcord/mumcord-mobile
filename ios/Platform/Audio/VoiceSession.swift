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
/// (echo cancellation, the call volume) for the audio unit, and says where the voice plays
/// (`OutputDevice`). The only place that touches the phone's audio session.
final class SystemVoiceSession: VoiceSession, OutputDevice {
    /// The engine's rate, so the system does not have to convert where the device allows it.
    private static let sampleRate = 48_000.0
    /// 10 ms: short enough for a conversation, long enough to be cheap.
    private static let bufferDuration = 0.01

    private let log: (String) -> Void
    private let hasEarpiece: Bool
    private var observers: [NSObjectProtocol] = []
    private let lock = NSLock()
    /// True between `begin` and `end`: only then the phone knows where the voice plays.
    private var running = false
    private var outputsChanged: (@Sendable () -> Void)?
    var onEvent: ((VoiceSessionEvent) -> Void)?
    var systemActivates = false
    /// Receives every signal of the system, on a thread of the system. Interruptions are only
    /// reported here: whether they stop the audio is decided by the interruption rules.
    var onSignal: ((AudioSessionSignal) -> Void)?

    /// - Parameter hasEarpiece: the device has an earpiece to hold to the ear, as a phone has
    ///   and a tablet has not; the audio session does not say.
    init(hasEarpiece: Bool, log: @escaping (String) -> Void) {
        self.hasEarpiece = hasEarpiece
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
            self?.onOutputsChanged?()
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
            // headset). Another output is chosen with `select`.
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
        if !systemActivates {
            do {
                try AVAudioSession.sharedInstance().setActive(true)
            } catch {
                log("starting the voice session failed (\((error as NSError).code))")
                return false
            }
        }
        setRunning(true)
        return true
    }

    func end() {
        setRunning(false)
        if systemActivates { return }
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            log("ending the voice session failed (\((error as NSError).code))")
        }
    }
}

// MARK: Outputs

extension SystemVoiceSession {
    var onOutputsChanged: (@Sendable () -> Void)? {
        get { lock.withLock { outputsChanged } }
        set { lock.withLock { outputsChanged = newValue } }
    }

    private func setRunning(_ next: Bool) {
        lock.withLock { running = next }
        onOutputsChanged?()
    }

    func outputs() -> OutputState? {
        guard lock.withLock({ running }) else { return nil }
        let session = AVAudioSession.sharedInstance()
        return outputState(
            inputs: (session.availableInputs ?? []).map(Self.port),
            route: session.currentRoute.outputs.map(Self.port),
            hasEarpiece: hasEarpiece
        )
    }

    /// Moves the voice. The loudspeaker is an override of the route; every other output goes
    /// with its microphone, so the microphone is chosen and the phone moves the sound along.
    func select(_ id: String) {
        let session = AVAudioSession.sharedInstance()
        do {
            if id == OutputId.speaker {
                try session.overrideOutputAudioPort(.speaker)
                return
            }
            try session.overrideOutputAudioPort(.none)
            let inputs = session.availableInputs ?? []
            switch id {
            case OutputId.earpiece:
                try session.setPreferredInput(inputs.first { $0.portType == .builtInMic })
            // Headphones without a microphone play as soon as nothing overrides them.
            case OutputId.headphones:
                try session.setPreferredInput(nil)
            default:
                guard let input = inputs.first(where: { $0.uid == id }) else { return }
                try session.setPreferredInput(input)
            }
        } catch {
            log("choosing the audio output failed (\((error as NSError).code))")
        }
    }

    private static func port(_ port: AVAudioSessionPortDescription) -> AudioPort {
        let kind: AudioPort.Kind
        switch port.portType {
        case .builtInMic: kind = .builtInMicrophone
        case .builtInReceiver: kind = .builtInReceiver
        case .builtInSpeaker: kind = .builtInSpeaker
        case .headsetMic, .usbAudio: kind = .wiredHeadset
        case .headphones: kind = .headphones
        case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE: kind = .bluetooth
        default: kind = .other
        }
        return AudioPort(uid: port.uid, kind: kind, name: port.portName)
    }
}
