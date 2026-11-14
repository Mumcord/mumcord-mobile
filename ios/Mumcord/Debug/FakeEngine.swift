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
    private var _messages: [ChatId: [ChatMessage]] = [:]
    private var _unread: [ChatId: UInt32] = [:]
    private var _gifFavourites: [GifFavourite] = []
    private var _mentionNames: [MentionCandidate] = []
    private var _chatImages: [String: ImageData] = [:]
    private var _remoteImages: [String: ImageData] = [:]
    private var _openChatCalls: [ChatId?] = []
    private var _markReadCalls: [ChatId] = []
    private var _pageFailure: BridgeError?
    private var _sendFailure: BridgeError?
    private var _limits = ChatLimits(text: 5000, image: 131_072, allowHtml: true)
    private var _loadMediaCalls: [Bool] = []
    private var nextMessageId = 1
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

    // MARK: Chat

    /// The messages of each chat, oldest first.
    var messages: [ChatId: [ChatMessage]] {
        get { locked { _messages } }
        set { locked { _messages = newValue } }
    }

    /// Unread messages per chat as the engine has them before the app asks.
    var unread: [ChatId: UInt32] {
        get { locked { _unread } }
        set { locked { _unread = newValue } }
    }

    var storedGifFavourites: [GifFavourite] {
        get { locked { _gifFavourites } }
        set { locked { _gifFavourites = newValue } }
    }

    /// Who the `@` autocomplete can offer.
    var mentionNames: [MentionCandidate] {
        get { locked { _mentionNames } }
        set { locked { _mentionNames = newValue } }
    }

    /// The pictures sent inside messages, by `imageKey`.
    var chatImages: [String: ImageData] {
        get { locked { _chatImages } }
        set { locked { _chatImages = newValue } }
    }

    /// The pictures on the web, by address.
    var remoteImages: [String: ImageData] {
        get { locked { _remoteImages } }
        set { locked { _remoteImages = newValue } }
    }

    /// While set, reading a page of a chat fails with it.
    var pageFailure: BridgeError? {
        get { locked { _pageFailure } }
        set { locked { _pageFailure = newValue } }
    }

    /// While set, sending anything to a chat fails with it.
    var sendFailure: BridgeError? {
        get { locked { _sendFailure } }
        set { locked { _sendFailure = newValue } }
    }

    var limits: ChatLimits {
        get { locked { _limits } }
        set { locked { _limits = newValue } }
    }

    /// Every chat the app reported as on screen, and nil for none, in order.
    var openChatCalls: [ChatId?] { locked { _openChatCalls } }
    var markReadCalls: [ChatId] { locked { _markReadCalls } }
    /// What every request for a picture from the web said about loading media.
    var loadMediaCalls: [Bool] { locked { _loadMediaCalls } }

    static func imageKey(messageId: String, imageId: String) -> String {
        "\(messageId)/\(imageId)"
    }

    /// A message of somebody else arrives: it is stored, counted as unread and reported.
    func receive(_ message: ChatMessage) {
        let count: UInt32 = locked {
            _messages[message.chat, default: []].append(message)
            let count = (_unread[message.chat] ?? 0) + 1
            _unread[message.chat] = count
            return count
        }
        emit(.chatUnreadChanged(chat: message.chat, count: count))
        emit(.chatMessageReceived(message: message))
    }

    func chatUnreadSnapshot() -> [ChatUnread] {
        locked { _unread.map { ChatUnread(chat: $0.key, count: $0.value) } }
    }

    func chatPage(chat: ChatId, before: String?, limit: UInt32) async throws -> ChatPage {
        try locked {
            if let failure = _pageFailure { throw failure }
            let all = _messages[chat] ?? []
            let end = before.flatMap { id in all.firstIndex { $0.id == id } } ?? all.count
            let start = max(0, end - Int(limit))
            return ChatPage(messages: Array(all[start..<end]), hasMore: start > 0)
        }
    }

    func sendMessage(chat: ChatId, text: String, replyTo: ReplyQuote?) async throws -> ChatMessage {
        try locked {
            if let failure = _sendFailure { throw failure }
            return appendOwn(chat, MessageContent(reply: replyTo, blocks: [.paragraph(spans: [span(text)])], truncated: false))
        }
    }

    func sendImage(chat: ChatId, bytes: Data, mime: String) async throws -> ChatMessage {
        try locked {
            if let failure = _sendFailure { throw failure }
            let imageId = "image-\(nextMessageId)"
            let image = ImageRef.embedded(imageId: imageId, mime: mime, width: 1, height: 1, byteLen: UInt32(bytes.count), animated: false)
            let message = appendOwn(chat, MessageContent(reply: nil, blocks: [.image(image: image)], truncated: false))
            _chatImages[Self.imageKey(messageId: message.id, imageId: imageId)] =
                ImageData(bytes: bytes, mime: mime, width: 1, height: 1, animated: false)
            return message
        }
    }

    func sendGifFavourite(chat: ChatId, url: String) async throws -> ChatMessage {
        try locked {
            if let failure = _sendFailure { throw failure }
            return appendOwn(chat, messageContent(url))
        }
    }

    /// Call with the lock held.
    private func appendOwn(_ chat: ChatId, _ content: MessageContent) -> ChatMessage {
        let message = chatMessage(
            id: "own-\(nextMessageId)",
            chat: chat,
            senderName: people[server.selfSession]?.name ?? "",
            own: true,
            sentAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            content: content
        )
        nextMessageId += 1
        _messages[chat, default: []].append(message)
        return message
    }

    func chatImage(chat: ChatId, messageId: String, imageId: String) async throws -> ImageData {
        try locked {
            guard let image = _chatImages[Self.imageKey(messageId: messageId, imageId: imageId)] else {
                throw BridgeError.NotFound(detail: "no such image")
            }
            return image
        }
    }

    func loadRemoteImage(url: String, loadMedia: Bool) async throws -> ImageData? {
        locked {
            _loadMediaCalls.append(loadMedia)
            return loadMedia ? _remoteImages[url] : nil
        }
    }

    func mentionCandidates(query: String, limit: UInt32) -> [MentionCandidate] {
        locked {
            let matching = _mentionNames.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
            return Array(matching.prefix(Int(limit)))
        }
    }

    func chatLimits() async throws -> ChatLimits {
        locked { _limits }
    }

    func markChatRead(chat: ChatId) async throws {
        let had: Bool = locked {
            _markReadCalls.append(chat)
            return _unread.removeValue(forKey: chat) != nil
        }
        if had {
            emit(.chatUnreadChanged(chat: chat, count: 0))
        }
    }

    func setOpenChat(chat: ChatId?) {
        locked { _openChatCalls.append(chat) }
    }

    func gifFavourites() async throws -> [GifFavourite] {
        locked { _gifFavourites }
    }

    func addGifFavourite(url: String) async throws {
        locked {
            if !_gifFavourites.contains(where: { $0.url == url }) {
                _gifFavourites.append(GifFavourite(url: url, addedAtMs: 0, lastUsedAtMs: 0))
            }
        }
    }

    func removeGifFavourite(url: String) async throws {
        locked { _gifFavourites.removeAll { $0.url == url } }
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
