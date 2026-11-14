// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// One call of `FakeEngine.connectServer`.
struct ConnectCall: Equatable {
    let serverId: Int64
    let password: String?
    let channelPath: [String]
}

/// Stands in for the engine and behaves like it: it keeps a server list, answers pings from
/// `pings`, and along the connect flow asks the certificate question (when `prompt` is set),
/// delivers `server` once trusted, and reports every end of a connection as an event.
///
/// Debug builds only: the unit tests and the UI tests run the app against it.
final class FakeEngine: EngineClient, @unchecked Sendable {
    private let listener: EngineListener
    private let server: ServerState
    private let prompt: CertificatePrompt?
    private let lock = NSLock()

    private var _connectCalls: [ConnectCall] = []
    private var _certificateAnswers: [(fingerprint: String, trust: Bool)] = []
    private var _serverInputs: [ServerInput] = []
    private var _pingRequests: [Int64] = []
    private var _disconnects = 0
    private var _joinCalls: [(channelId: UInt32, password: String?)] = []
    private var _volumeCalls: [(session: UInt32, percent: Int)] = []
    private var _pings: [Int64: ServerPing] = [:]
    private var _connectFailure: BridgeError?
    private var _saveFailure: BridgeError?
    private var _talkModeFailure: BridgeError?
    private var _requiredPassword: String?
    private var _channelPasswords: [UInt32: String] = [:]
    private var _deniedChannels: Set<UInt32> = []
    private var _talkMode = TalkMode.voiceActivity
    private var _audioOnConnect = AudioWanted(capture: true, playback: true)
    private var _pushToTalkCalls: [Bool] = []
    private var _whisperStarts: [WhisperTarget] = []
    private var _whisperStops = 0
    private var _whisperFailure: BridgeError?
    private var _levelOnConnect: UInt8?
    /// The user's own mute before a deafen, which comes back when the deafen ends.
    private var mutedBeforeDeafen = false
    private var people: [UInt32: Person]
    private var connection = ConnectionState.disconnected(error: nil)
    private var storedServers: [SavedServer]
    private var nextServerId: Int64

    init(
        listener: EngineListener,
        server: ServerState,
        prompt: CertificatePrompt? = nil,
        servers: [SavedServer] = []
    ) {
        self.listener = listener
        self.server = server
        self.prompt = prompt
        people = Dictionary(server.people.map { ($0.session, $0) }, uniquingKeysWith: { _, last in last })
        storedServers = servers
        nextServerId = (servers.map(\.id).max() ?? 0) + 1
    }

    private func locked<Value>(_ body: () throws -> Value) rethrows -> Value {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    var connectCalls: [ConnectCall] { locked { _connectCalls } }
    var certificateAnswers: [(fingerprint: String, trust: Bool)] { locked { _certificateAnswers } }
    var serverInputs: [ServerInput] { locked { _serverInputs } }
    var pingRequests: [Int64] { locked { _pingRequests } }
    var disconnects: Int { locked { _disconnects } }
    var joinCalls: [(channelId: UInt32, password: String?)] { locked { _joinCalls } }
    var volumeCalls: [(session: UInt32, percent: Int)] { locked { _volumeCalls } }
    var currentTalkMode: TalkMode { locked { _talkMode } }
    /// Every press (true) and release (false) of the hold-to-talk button, in order.
    var pushToTalkCalls: [Bool] { locked { _pushToTalkCalls } }
    var whisperStarts: [WhisperTarget] { locked { _whisperStarts } }
    var whisperStops: Int { locked { _whisperStops } }
    /// The talk mode the engine has saved before the app asks for it.
    var savedTalkMode: TalkMode {
        get { locked { _talkMode } }
        set { locked { _talkMode = newValue } }
    }
    /// What the engine wants from the phone's audio once connected.
    var audioOnConnect: AudioWanted {
        get { locked { _audioOnConnect } }
        set { locked { _audioOnConnect = newValue } }
    }
    /// The microphone level the engine reports once connected.
    var levelOnConnect: UInt8? {
        get { locked { _levelOnConnect } }
        set { locked { _levelOnConnect = newValue } }
    }
    /// Thrown by every `startWhisper` while set.
    var whisperFailure: BridgeError? {
        get { locked { _whisperFailure } }
        set { locked { _whisperFailure = newValue } }
    }

    /// What each server answers to a ping; a server without an entry does not answer.
    var pings: [Int64: ServerPing] {
        get { locked { _pings } }
        set { locked { _pings = newValue } }
    }

    /// Thrown by the next `connectServer` instead of connecting.
    var connectFailure: BridgeError? {
        get { locked { _connectFailure } }
        set { locked { _connectFailure = newValue } }
    }

    /// Thrown by the next `addServer` or `updateServer`.
    var saveFailure: BridgeError? {
        get { locked { _saveFailure } }
        set { locked { _saveFailure = newValue } }
    }

    /// Thrown by the next `setTalkMode`.
    var talkModeFailure: BridgeError? {
        get { locked { _talkModeFailure } }
        set { locked { _talkModeFailure = newValue } }
    }

    /// When set, the server refuses every connection without exactly this password.
    var requiredPassword: String? {
        get { locked { _requiredPassword } }
        set { locked { _requiredPassword = newValue } }
    }

    /// Channels that only let in who joins with exactly this password.
    var channelPasswords: [UInt32: String] {
        get { locked { _channelPasswords } }
        set { locked { _channelPasswords = newValue } }
    }

    /// Channels the server refuses to let the user into.
    var deniedChannels: Set<UInt32> {
        get { locked { _deniedChannels } }
        set { locked { _deniedChannels = newValue } }
    }

    func emit(_ event: BridgeEvent) {
        if case .connectionChanged(let state) = event {
            locked {
                connection = state
            }
        }
        listener.onEvent(event: event)
    }

    func connectServer(serverId: Int64, password: String?, channelPath: [String]) async throws {
        let needed: String? = try locked {
            if let failure = _connectFailure {
                _connectFailure = nil
                throw failure
            }
            guard storedServers.contains(where: { $0.id == serverId }) else {
                throw BridgeError.NotFound(detail: "no server \(serverId)")
            }
            guard case .disconnected = connection else {
                throw BridgeError.InvalidInput(detail: "already connected")
            }
            _connectCalls.append(ConnectCall(serverId: serverId, password: password, channelPath: channelPath))
            return _requiredPassword
        }
        emit(.connectionChanged(state: .connecting))
        if let needed, password != needed {
            emit(.connectionChanged(state: .disconnected(error: .WrongPassword(detail: "wrong password"))))
        } else if let prompt {
            emit(.certificatePromptRequired(prompt: prompt))
        } else {
            finishConnecting()
        }
    }

    func answerCertificatePrompt(fingerprint: String, trust: Bool) async throws {
        locked { _certificateAnswers.append((fingerprint, trust)) }
        if trust {
            finishConnecting()
        } else {
            emit(.connectionChanged(state: .disconnected(error: .CertificateRejected(detail: "rejected"))))
        }
    }

    func disconnect() async throws {
        locked { _disconnects += 1 }
        emit(.audioWantedChanged(audio: AudioWanted(capture: false, playback: false)))
        emit(.connectionChanged(state: .disconnected(error: nil)))
    }

    func savedServers() -> [SavedServer] {
        locked { storedServers }
    }

    func addServer(input: ServerInput) async throws -> SavedServer {
        let (saved, all): (SavedServer, [SavedServer]) = try locked {
            try failSaveOnce()
            _serverInputs.append(input)
            let saved = Self.saved(input, id: nextServerId, hadPassword: false)
            nextServerId += 1
            storedServers.append(saved)
            return (saved, storedServers)
        }
        emit(.serversChanged(servers: all))
        return saved
    }

    func updateServer(serverId: Int64, input: ServerInput) async throws -> SavedServer {
        let (saved, all): (SavedServer, [SavedServer]) = try locked {
            try failSaveOnce()
            guard let index = storedServers.firstIndex(where: { $0.id == serverId }) else {
                throw BridgeError.NotFound(detail: "no server \(serverId)")
            }
            _serverInputs.append(input)
            let saved = Self.saved(input, id: serverId, hadPassword: storedServers[index].hasPassword)
            storedServers[index] = saved
            return (saved, storedServers)
        }
        emit(.serversChanged(servers: all))
        return saved
    }

    /// Call with the lock held.
    private func failSaveOnce() throws {
        if let failure = _saveFailure {
            _saveFailure = nil
            throw failure
        }
    }

    func removeServer(serverId: Int64) async throws {
        let all: [SavedServer] = try locked {
            guard let index = storedServers.firstIndex(where: { $0.id == serverId }) else {
                throw BridgeError.NotFound(detail: "no server \(serverId)")
            }
            storedServers.remove(at: index)
            return storedServers
        }
        emit(.serversChanged(servers: all))
    }

    func pingServer(serverId: Int64, refresh: Bool) async throws -> ServerPing? {
        locked {
            _pingRequests.append(serverId)
            return _pings[serverId]
        }
    }

    func joinChannel(channelId: UInt32, password: String?) async throws {
        let (denied, needed): (Bool, String?) = locked {
            _joinCalls.append((channelId, password))
            return (_deniedChannels.contains(channelId), _channelPasswords[channelId])
        }
        if denied {
            emit(.actionDenied(channelId: channelId, detail: "Permission denied."))
        } else if needed != nil, password == nil {
            emit(.channelPasswordChanged(channelId: channelId, state: .required))
        } else if let needed, password != needed {
            emit(.channelPasswordChanged(channelId: channelId, state: .wrong))
        } else {
            if password != nil {
                emit(.channelPasswordChanged(channelId: channelId, state: .ended))
            }
            change(server.selfSession) { $0.channelId = channelId }
        }
    }

    func setPersonVolume(session: UInt32, percent: UInt16) async throws {
        locked { _volumeCalls.append((session, Int(percent))) }
        change(session) { $0.localVolume = percent }
    }

    func setPersonLocalMute(session: UInt32, muted: Bool) async throws {
        change(session) { $0.locallyMuted = muted }
    }

    /// Changes a person and reports it, as the engine does.
    func change(_ session: UInt32, _ change: (inout Person) -> Void) {
        let changed: Person? = locked {
            guard var person = people[session] else { return nil }
            change(&person)
            people[session] = person
            return person
        }
        if let changed {
            emit(.personChanged(person: changed))
        }
    }

    func talkMode() async throws -> TalkMode {
        locked { _talkMode }
    }

    func audioEndpoints() -> AudioEndpoints {
        // 0 means "no engine" to the audio functions: a push is dropped, a pull is silence.
        AudioEndpoints(capture: 0, playback: 0)
    }

    func setPushToTalkPressed(pressed: Bool) {
        locked { _pushToTalkCalls.append(pressed) }
        change(server.selfSession) { $0.talking = pressed }
    }

    /// As the engine: unmuting also ends a deafen.
    func setSelfMute(muted: Bool) async throws {
        change(server.selfSession) {
            $0.selfMuted = muted
            if !muted { $0.selfDeafened = false }
        }
    }

    /// As the engine: deafening also mutes, and the mute goes back to what it was afterwards.
    func setSelfDeaf(deafened: Bool) async throws {
        change(server.selfSession) {
            if deafened {
                mutedBeforeDeafen = $0.selfMuted
                $0.selfMuted = true
            } else {
                $0.selfMuted = mutedBeforeDeafen
            }
            $0.selfDeafened = deafened
        }
    }

    func startWhisper(target: WhisperTarget) async throws {
        try locked {
            if let failure = _whisperFailure { throw failure }
            _whisperStarts.append(target)
        }
    }

    func stopWhisper() {
        locked { _whisperStops += 1 }
    }

    func setTalkMode(mode: TalkMode) async throws {
        try locked {
            if let failure = _talkModeFailure {
                _talkModeFailure = nil
                throw failure
            }
            _talkMode = mode
        }
    }

    private static func saved(_ input: ServerInput, id: Int64, hadPassword: Bool) -> SavedServer {
        let hasPassword: Bool
        switch input.password {
        case .set: hasPassword = true
        case .clear: hasPassword = false
        case .keep: hasPassword = hadPassword
        }
        return SavedServer(
            id: id,
            name: input.name.isEmpty ? input.address : input.name,
            address: input.address,
            port: input.port,
            username: input.username,
            hasPassword: hasPassword,
            identityId: input.identityId
        )
    }

    private func finishConnecting() {
        emit(.connectionChanged(state: .connected))
        emit(.serverSynced(server: server))
        emit(.audioWantedChanged(audio: audioOnConnect))
        if let level = levelOnConnect {
            emit(.inputLevelChanged(percent: level))
        }
    }
}
#endif
