// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation

/// Stands in for the microphone permission of iOS. `answer` is what the user says to the
/// system's question. Debug builds only: the unit tests and the UI tests use it.
final class FakeMicrophoneGateway: MicrophoneGateway, @unchecked Sendable {
    private let lock = NSLock()
    private var _permission: MicrophonePermission
    private var _declined: Bool
    private var _answer = true
    private var _requests = 0

    init(permission: MicrophonePermission = .granted, declined: Bool = false) {
        _permission = permission
        _declined = declined
    }

    private func locked<Value>(_ body: () -> Value) -> Value {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func permission() -> MicrophonePermission { locked { _permission } }

    /// Changes the permission behind the app's back, as the system settings do.
    func set(_ permission: MicrophonePermission) { locked { _permission = permission } }

    var declined: Bool {
        get { locked { _declined } }
        set { locked { _declined = newValue } }
    }

    var answer: Bool {
        get { locked { _answer } }
        set { locked { _answer = newValue } }
    }

    /// How often the system's question was shown.
    var requests: Int { locked { _requests } }

    func request(_ completion: @escaping @Sendable (Bool) -> Void) {
        let granted: Bool = locked {
            _requests += 1
            _permission = _answer ? .granted : .denied
            return _answer
        }
        completion(granted)
    }
}

/// Stands in for the phone's audio outputs: it plays where it was last told to. `stopped`
/// is the phone while the session's audio does not run and it knows no outputs.
final class FakeOutputDevice: OutputDevice, @unchecked Sendable {
    private let lock = NSLock()
    private var _state: OutputState
    private var _stopped = false
    private var _selections: [String] = []
    private var _onOutputsChanged: (@Sendable () -> Void)?

    init(_ state: OutputState) {
        _state = state
    }

    var onOutputsChanged: (@Sendable () -> Void)? {
        get { lock.withLock { _onOutputsChanged } }
        set { lock.withLock { _onOutputsChanged = newValue } }
    }

    /// Every output the voice was moved to, in order.
    var selections: [String] { lock.withLock { _selections } }

    func outputs() -> OutputState? {
        lock.withLock { _stopped ? nil : _state }
    }

    func select(_ id: String) {
        lock.withLock {
            _selections.append(id)
            if let output = _state.available.first(where: { $0.id == id }) { _state.current = output }
        }
        onOutputsChanged?()
    }

    /// Changes the outputs behind the app's back, as plugging in a headset does.
    func set(_ state: OutputState) {
        lock.withLock { _state = state }
        onOutputsChanged?()
    }

    /// Stops or restarts the session's audio; a restart plays where the phone chooses.
    func setStopped(_ stopped: Bool, restartsOn current: AudioOutput? = nil) {
        lock.withLock {
            _stopped = stopped
            if let current { _state.current = current }
        }
        onOutputsChanged?()
    }
}

/// An audio unit that only notes what it was asked to do.
final class FakeStreamBackend: StreamBackend, @unchecked Sendable {
    private let lock = NSLock()
    private var _opens: [StreamRequest] = []
    private var _closes = 0
    private var _running: StreamRequest?
    private var _failures = 0
    private var _microphoneFails = false

    private func locked<Value>(_ body: () -> Value) -> Value {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Every request to open the unit, also the failed ones.
    var opens: [StreamRequest] { locked { _opens } }
    var closes: Int { locked { _closes } }
    /// What the open unit does, or nil while none is open.
    var running: StreamRequest? { locked { _running } }

    /// How many of the next requests fail.
    var failures: Int {
        get { locked { _failures } }
        set { locked { _failures = newValue } }
    }

    /// Every request that includes the microphone fails, as on a device without one.
    var microphoneFails: Bool {
        get { locked { _microphoneFails } }
        set { locked { _microphoneFails = newValue } }
    }

    func open(_ request: StreamRequest) -> OpenResult {
        locked {
            _opens.append(request)
            if _failures > 0 {
                _failures -= 1
                return .failed(code: -50, step: 9)
            }
            if _microphoneFails && request.capture { return .failed(code: -50, step: 9) }
            _running = request
            return .opened(microphoneOpen: request.capture)
        }
    }

    func close() {
        locked {
            _closes += 1
            _running = nil
        }
    }
}

/// A voice session that only counts.
final class FakeVoiceSession: VoiceSession, @unchecked Sendable {
    private let lock = NSLock()
    private var _begins = 0
    private var _ends = 0
    private var _systemActivates = false
    var onEvent: ((VoiceSessionEvent) -> Void)?

    var systemActivates: Bool {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _systemActivates
        }
        set {
            lock.lock()
            _systemActivates = newValue
            lock.unlock()
        }
    }

    var begins: Int {
        lock.lock()
        defer { lock.unlock() }
        return _begins
    }

    var ends: Int {
        lock.lock()
        defer { lock.unlock() }
        return _ends
    }

    func begin() -> Bool {
        lock.lock()
        _begins += 1
        lock.unlock()
        return true
    }

    func end() {
        lock.lock()
        _ends += 1
        lock.unlock()
    }
}

/// Stands in for the device's networks and for what iOS tells about local network access.
final class FakeLocalNetworkGateway: LocalNetworkGateway, @unchecked Sendable {
    private let lock = NSLock()
    private var _explained: Bool
    private var _probes = 0
    /// The networks the device is on.
    let prefixes: [IpPrefix]
    /// What each host name resolves to; an unknown name does not resolve.
    let addresses: [String: [IpAddress]]
    /// What the system says after a failed connection.
    let refused: Bool

    init(
        prefixes: [IpPrefix] = [],
        addresses: [String: [IpAddress]] = [:],
        explained: Bool = false,
        refused: Bool = false
    ) {
        self.prefixes = prefixes
        self.addresses = addresses
        _explained = explained
        self.refused = refused
    }

    var explained: Bool {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _explained
        }
        set {
            lock.lock()
            _explained = newValue
            lock.unlock()
        }
    }

    /// How often the system was asked whether it refuses access.
    var probes: Int {
        lock.lock()
        defer { lock.unlock() }
        return _probes
    }

    func onLinkPrefixes() -> [IpPrefix] { prefixes }

    func resolve(_ host: String) async -> [IpAddress] { addresses[host] ?? [] }

    func accessRefused(host: String, port: UInt16) async -> Bool {
        lock.withLock { _probes += 1 }
        return refused
    }
}
#endif
