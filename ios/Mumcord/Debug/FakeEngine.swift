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
    }
}
#endif
