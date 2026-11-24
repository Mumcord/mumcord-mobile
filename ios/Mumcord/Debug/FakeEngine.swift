// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// The identity a phone has after its first start; a fingerprint has 40 hex digits.
let fakeIdentity = Identity(
    id: 1,
    label: "Lea",
    commonName: "Lea",
    fingerprint: "4fa91c7ed208b36a".padding(toLength: 40, withPad: "5", startingAt: 0),
    createdMs: 0,
    isDefault: true,
    serverIds: []
)

/// The name inside every identity file of the fake.
let fakeImportedName = "Imported"

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
    private var _bitrateLimits: [UInt32?] = []
    private var _joinCalls: [(channelId: UInt32, password: String?)] = []
    private var _volumeCalls: [(session: UInt32, percent: Int)] = []
    private var _moderationCalls: [String] = []
    private var _moderationFailure: BridgeError?
    private var _banTargetAnswer = BanTarget(
        address: "192.0.2.7",
        onlineSecs: nil,
        banListReadable: true,
        hasCertificate: true,
        canKick: true,
        canBan: true
    )
    private var _banResults: [BanResult] = []
    private var _kickReasons: [String?] = []
    private var _banInputs: [BanInput] = []
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
    private var _conversations: [Conversation] = []
    private var _requests = MessageRequests(incoming: [], sent: [])
    private var _blocked: [BlockedPerson] = []
    private var nextBlockId: Int64 = 1
    private var _requestAnswers: [(id: UInt64, accepted: Bool)] = []
    private var _chatRequestCalls: [(session: UInt32, note: String?)] = []
    private var _chatRequestFailure: BridgeError?
    private var _blockFailure: BridgeError?
    private var _conversationReads = 0
    private var _mobileSettings = MobileSettings(
        talkModeChosen: true,
        defaultOutput: .speaker,
        mobileDataAutoplayStreams: false,
        mobileDataLoadMedia: true,
        mobileDataReducedVoice: false,
        idleDisconnect: false,
        notifySenderOnly: false,
        updateCheck: true
    )
    private var _appSettings = AppSettings(
        language: nil,
        linkPreviews: true,
        historyMaxAgeDays: nil,
        notifyPrivateMessages: true,
        notifyMentions: true
    )
    private var _noiseSuppression = false
    private var _voiceSettingsFailure: BridgeError?
    private var _mobileSettingsSaveDelay: Duration?
    private var _mobileSettingsSaves = 0
    private var _settingsSaveFailure: BridgeError?
    private var _activityOnConnect: [ActivityEntry] = []
    /// The user's own mute before a deafen, which comes back when the deafen ends.
    private var _identities: [Identity]
    private var nextIdentityId: Int64
    private var _filePassword = ""
    private var _exportError: BridgeError?
    private var _transfer: TransferSession?
    private var mutedBeforeDeafen = false
    private var people: [UInt32: Person]
    private var connection = ConnectionState.disconnected(error: nil)
    private var storedServers: [SavedServer]
    private var nextServerId: Int64

    init(
        listener: EngineListener,
        server: ServerState,
        prompt: CertificatePrompt? = nil,
        servers: [SavedServer] = [],
        identities: [Identity] = [fakeIdentity]
    ) {
        _identities = identities
        nextIdentityId = (identities.map(\.id).max() ?? 0) + 1
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
    /// Every bitrate limit the app set, in order.
    var bitrateLimits: [UInt32?] { locked { _bitrateLimits } }
    var joinCalls: [(channelId: UInt32, password: String?)] { locked { _joinCalls } }
    var volumeCalls: [(session: UInt32, percent: Int)] { locked { _volumeCalls } }
    /// Every move ("move <session> <channel>") and server mute ("mute <session> <muted>"), in order.
    var moderationCalls: [String] { locked { _moderationCalls } }
    /// The reason of every kick, in order.
    var kickReasons: [String?] { locked { _kickReasons } }
    /// Every ban as it was sent, in order.
    var banInputs: [BanInput] { locked { _banInputs } }
    /// Thrown by the next moderation command (move, mute, kick, ban and its target) instead of acting.
    var moderationFailure: BridgeError? {
        get { locked { _moderationFailure } }
        set { locked { _moderationFailure = newValue } }
    }
    /// What `banTarget` reports about a person.
    var banTargetAnswer: BanTarget {
        get { locked { _banTargetAnswer } }
        set { locked { _banTargetAnswer = newValue } }
    }
    /// What the next bans produce, in order; a ban beyond them is saved.
    var banResults: [BanResult] {
        get { locked { _banResults } }
        set { locked { _banResults = newValue } }
    }
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
        locked {
            _disconnects += 1
            // The bridge's list of a connection ends with it.
            _streams = []
        }
        updateFrameTimer()
        // As the engine: the own camera ends with the connection.
        endCamera()
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
            // As the engine: leaving the channel turns the own camera off (FR-8.21).
            endCamera()
        }
    }

    func setPersonVolume(session: UInt32, percent: UInt16) async throws {
        locked { _volumeCalls.append((session, Int(percent))) }
        change(session) { $0.localVolume = percent }
    }

    func setPersonLocalMute(session: UInt32, muted: Bool) async throws {
        change(session) { $0.locallyMuted = muted }
    }

    // MARK: Moderation

    /// Call with the lock held.
    private func failModerationOnce() throws {
        if let failure = _moderationFailure {
            _moderationFailure = nil
            throw failure
        }
    }

    /// Runs a move or a server mute. As the engine: the server's refusal of one is also told
    /// as its refusal event.
    private func moderate(_ call: String) throws {
        do {
            try locked {
                try failModerationOnce()
                _moderationCalls.append(call)
            }
        } catch BridgeError.PermissionDenied(let detail) {
            emit(.actionDenied(channelId: nil, detail: detail))
            throw BridgeError.PermissionDenied(detail: detail)
        }
    }

    func movePerson(session: UInt32, channelId: UInt32) async throws -> Bool {
        try moderate("move \(session) \(channelId)")
        change(session) { $0.channelId = channelId }
        return true
    }

    func setServerMute(session: UInt32, muted: Bool) async throws -> Bool {
        try moderate("mute \(session) \(muted)")
        change(session) { $0.serverMuted = muted }
        return true
    }

    func kickPerson(session: UInt32, reason: String?) async throws -> Bool {
        try locked {
            try failModerationOnce()
            _kickReasons.append(reason)
        }
        remove(session)
        return true
    }

    func banTarget(session: UInt32) async throws -> BanTarget {
        try locked {
            try failModerationOnce()
            return _banTargetAnswer
        }
    }

    /// As a server: the person is taken off when the ban says so, and always by its default ban.
    func banPerson(session: UInt32, ban: BanInput) async throws -> BanResult {
        let result: BanResult = try locked {
            try failModerationOnce()
            _banInputs.append(ban)
            if !_banResults.isEmpty { return _banResults.removeFirst() }
            return ban.quick ? .quickBanned : .saved(removed: ban.removeNow)
        }
        if result != .conflict, ban.quick || ban.removeNow {
            remove(session)
        }
        return result
    }

    /// Takes a person off the server and reports it, as the engine does.
    private func remove(_ session: UInt32) {
        let removed = locked { people.removeValue(forKey: session) != nil }
        if removed {
            emit(.personRemoved(session: session))
        }
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

    // MARK: Private conversations, requests, blocking

    /// The private conversations as the engine lists them, newest first.
    var conversations: [Conversation] {
        get { locked { _conversations } }
        set { locked { _conversations = newValue } }
    }

    /// How often the list of private conversations was read.
    var conversationReads: Int { locked { _conversationReads } }

    /// The message requests as the engine holds them before the app asks.
    var requests: MessageRequests {
        get { locked { _requests } }
        set { locked { _requests = newValue } }
    }

    /// The blocked people as the engine has them before the app asks.
    var blocked: [BlockedPerson] {
        get { locked { _blocked } }
        set { locked { _blocked = newValue } }
    }

    /// Every answer to a message request, in order.
    var requestAnswers: [(id: UInt64, accepted: Bool)] { locked { _requestAnswers } }
    var chatRequestCalls: [(session: UInt32, note: String?)] { locked { _chatRequestCalls } }

    /// While set, sending a chat request fails with it.
    var chatRequestFailure: BridgeError? {
        get { locked { _chatRequestFailure } }
        set { locked { _chatRequestFailure = newValue } }
    }

    /// While set, blocking a person fails with it.
    var blockFailure: BridgeError? {
        get { locked { _blockFailure } }
        set { locked { _blockFailure = newValue } }
    }

    var storedMobileSettings: MobileSettings {
        get { locked { _mobileSettings } }
        set { locked { _mobileSettings = newValue } }
    }

    var storedAppSettings: AppSettings {
        get { locked { _appSettings } }
        set { locked { _appSettings = newValue } }
    }

    /// Thrown by the next `setMobileSettings` or `setAppSettings`.
    var settingsSaveFailure: BridgeError? {
        get { locked { _settingsSaveFailure } }
        set { locked { _settingsSaveFailure = newValue } }
    }

    /// How long `setMobileSettings` takes, for two changes that meet in the engine.
    var mobileSettingsSaveDelay: Duration? {
        get { locked { _mobileSettingsSaveDelay } }
        set { locked { _mobileSettingsSaveDelay = newValue } }
    }

    /// How often `setMobileSettings` saved, so that a test knows every save is done.
    var mobileSettingsSaves: Int {
        locked { _mobileSettingsSaves }
    }

    /// Whether the engine has noise suppression switched on.
    var noiseSuppression: Bool {
        get { locked { _noiseSuppression } }
        set { locked { _noiseSuppression = newValue } }
    }

    /// While set, reading the voice settings and switching noise suppression fail with it.
    var voiceSettingsFailure: BridgeError? {
        get { locked { _voiceSettingsFailure } }
        set { locked { _voiceSettingsFailure = newValue } }
    }

    /// The activity entries the engine reports right after the server's channels, oldest first.
    var activityOnConnect: [ActivityEntry] {
        get { locked { _activityOnConnect } }
        set { locked { _activityOnConnect = newValue } }
    }

    /// The engine found a message worth a system notification.
    func notify(_ notification: ChatNotification) {
        emit(.chatNotification(notification: notification))
    }

    /// The held requests change, as when somebody writes for the first time.
    func changeRequests(_ requests: MessageRequests) {
        locked { _requests = requests }
        emit(.messageRequestsChanged(requests: requests))
    }

    /// A person comes to the server.
    func join(_ person: Person) {
        locked { people[person.session] = person }
        emit(.personChanged(person: person))
    }

    /// A person leaves the server.
    func leave(_ session: UInt32) {
        locked { people[session] = nil }
        emit(.personRemoved(session: session))
    }

    func privateConversations() async throws -> [Conversation] {
        locked {
            _conversationReads += 1
            return _conversations
        }
    }

    func chatWith(session: UInt32) throws -> ChatId {
        try locked {
            guard people[session] != nil else { throw BridgeError.NotFound(detail: "no such person") }
            // As the engine: the stored conversation of a person who is listed, else their session's.
            return _conversations.first { $0.peerSession == session }?.chat ?? .session(session: session)
        }
    }

    func messageRequests() async throws -> MessageRequests {
        locked { _requests }
    }

    func acceptMessageRequest(id: UInt64) async throws {
        try answerRequest(id, accepted: true)
    }

    func declineMessageRequest(id: UInt64) async throws {
        try answerRequest(id, accepted: false)
    }

    /// An accepted request turns its held messages into a conversation, as in the engine.
    private func answerRequest(_ id: UInt64, accepted: Bool) throws {
        let requests: MessageRequests = try locked {
            guard let request = _requests.incoming.first(where: { $0.id == id }) else {
                throw BridgeError.NotFound(detail: "no such request")
            }
            _requestAnswers.append((id, accepted))
            _requests = MessageRequests(incoming: _requests.incoming.filter { $0.id != id }, sent: _requests.sent)
            if accepted, let last = request.messages.last {
                let chat = ChatId.session(session: request.session)
                _messages[chat, default: []].append(contentsOf: request.messages)
                _conversations.insert(
                    Conversation(chat: chat, peerName: request.name, peerSession: request.session, last: last, unread: 0),
                    at: 0
                )
            }
            return _requests
        }
        emit(.messageRequestsChanged(requests: requests))
    }

    func sendChatRequest(session: UInt32, note: String?) async throws {
        let requests: MessageRequests = try locked {
            if let failure = _chatRequestFailure { throw failure }
            _chatRequestCalls.append((session, note))
            let sent = SentChatRequest(session: session, name: people[session]?.name ?? "", accepted: false)
            _requests = MessageRequests(incoming: _requests.incoming, sent: _requests.sent + [sent])
            return _requests
        }
        emit(.messageRequestsChanged(requests: requests))
    }

    func blockedPeople() async throws -> [BlockedPerson] {
        locked { _blocked }
    }

    func blockPerson(session: UInt32) async throws {
        let list: [BlockedPerson] = try locked {
            if let failure = _blockFailure { throw failure }
            guard let person = people[session] else { throw BridgeError.NotFound(detail: "no such person") }
            _blocked.append(
                BlockedPerson(
                    id: nextBlockId,
                    name: person.name,
                    serverLabel: "",
                    blockedAtMs: 0,
                    certHash: nil,
                    session: session
                )
            )
            nextBlockId += 1
            return _blocked
        }
        change(session) { $0.blocked = true }
        emit(.blockedPeopleChanged(people: list))
    }

    func unblockPerson(id: Int64) async throws {
        let (list, session): ([BlockedPerson], UInt32?) = locked {
            let session = _blocked.first { $0.id == id }?.session
            _blocked.removeAll { $0.id == id }
            return (_blocked, session)
        }
        if let session {
            change(session) { $0.blocked = false }
        }
        emit(.blockedPeopleChanged(people: list))
    }

    func mobileSettings() -> MobileSettings {
        locked { _mobileSettings }
    }

    func setMobileSettings(settings: MobileSettings) async throws {
        if let delay = mobileSettingsSaveDelay { try await Task.sleep(for: delay) }
        try locked {
            if let failure = _settingsSaveFailure {
                _settingsSaveFailure = nil
                throw failure
            }
            _mobileSettings = settings
            _mobileSettingsSaves += 1
        }
    }

    func appSettings() -> AppSettings {
        locked { _appSettings }
    }

    /// As the engine: the saved settings arrive as an event.
    func setAppSettings(settings: AppSettings) async throws {
        try locked {
            if let failure = _settingsSaveFailure {
                _settingsSaveFailure = nil
                throw failure
            }
            _appSettings = settings
        }
        emit(.appSettingsChanged(settings: settings))
    }

    func voiceSettings() async throws -> VoiceSettings {
        try locked {
            if let failure = _voiceSettingsFailure { throw failure }
            return VoiceSettings(
                talkMode: _talkMode,
                noiseSuppression: _noiseSuppression,
                autoGain: true,
                echoCancellation: .system,
                bitrateLimitKbps: _bitrateLimits.last ?? nil
            )
        }
    }

    func setNoiseSuppression(on: Bool) async throws {
        try locked {
            if let failure = _voiceSettingsFailure { throw failure }
            _noiseSuppression = on
        }
    }

    func setBitrateLimitKbps(kbps: UInt32?) {
        locked { _bitrateLimits.append(kbps) }
    }

    // MARK: Streams

    // The tests set the streams with `showStreams` (or `streamsOnConnect`); a watch goes to
    // Connecting and then to `watchOutcome` (the first one to `firstWatchOutcome` if set), after the direct connection question when
    // `asksDirectConnections`. While a stream is watched and a sink is set, the fake plays the
    // bridge's video thread and says 15 times a second that a picture is ready; the pictures
    // themselves come from `FakeVideoFrames`.

    private var _streams: [MumcordBridge.Stream] = []
    private var _streamsOnConnect: [MumcordBridge.Stream] = []
    private var _watchCalls: [StreamKey] = []
    private var _stopCalls: [StreamKey] = []
    private var _directAnswers: [Bool] = []
    private var _watchOutcome = StreamWatch.watching(path: .direct)
    private var _firstWatchOutcome: StreamWatch?
    private var _asksDirectConnections = false
    private var _videoSink: VideoSink?
    private var _videoSinkCalls = 0
    private var frameTimer: DispatchSourceTimer?

    /// The streams shown once connected, as the bridge reports them.
    var streamsOnConnect: [MumcordBridge.Stream] {
        get { locked { _streamsOnConnect } }
        set { locked { _streamsOnConnect = newValue } }
    }
    var watchCalls: [StreamKey] { locked { _watchCalls } }
    var stopCalls: [StreamKey] { locked { _stopCalls } }
    var directAnswers: [Bool] { locked { _directAnswers } }
    var currentStreams: [MumcordBridge.Stream] { locked { _streams } }
    /// The sink the app set last; nil before it set one.
    var videoSink: VideoSink? { locked { _videoSink } }
    /// What a watch turns into once it has connected.
    var watchOutcome: StreamWatch {
        get { locked { _watchOutcome } }
        set { locked { _watchOutcome = newValue } }
    }
    /// What the first watch turns into instead of `watchOutcome`; nil: the same as the others.
    var firstWatchOutcome: StreamWatch? {
        get { locked { _firstWatchOutcome } }
        set { locked { _firstWatchOutcome = newValue } }
    }
    /// The first watch asks "Allow direct connections?" and waits for the answer.
    var asksDirectConnections: Bool {
        get { locked { _asksDirectConnections } }
        set { locked { _asksDirectConnections = newValue } }
    }

    /// Replaces the streams of the user's channel, as the bridge reports them.
    func showStreams(_ streams: [MumcordBridge.Stream]) {
        locked { _streams = streams }
        streamsChanged()
    }

    private func streamsChanged() {
        let streams = locked { _streams }
        emit(.streamsChanged(streams: streams))
        updateFrameTimer()
    }

    private func changeStream(_ key: StreamKey, _ change: (inout MumcordBridge.Stream) -> Void) throws {
        try locked {
            guard let index = _streams.firstIndex(where: { $0.key == key }) else {
                throw BridgeError.NotFound(detail: "no such stream")
            }
            change(&_streams[index])
        }
        streamsChanged()
    }

    func watchStream(session: UInt32, kind: StreamKind) async throws {
        let key = StreamKey(session: session, kind: kind)
        let (ask, outcome): (Bool, StreamWatch) = locked {
            _watchCalls.append(key)
            let first = _watchCalls.count == 1 ? _firstWatchOutcome : nil
            return (_asksDirectConnections && _directAnswers.isEmpty, first ?? _watchOutcome)
        }
        try changeStream(key) { $0.watch = .connecting }
        if ask {
            emit(.directConnectionsQuestion)
            return
        }
        try changeStream(key) { $0.watch = outcome }
    }

    func stopWatchingStream(session: UInt32, kind: StreamKind) async throws {
        let key = StreamKey(session: session, kind: kind)
        locked { _stopCalls.append(key) }
        try changeStream(key) { $0.watch = .idle }
    }

    func setStreamVolume(session: UInt32, percent: UInt16) async throws {
        let keys = locked { _streams.filter { $0.session == session }.map(\.key) }
        for key in keys {
            try changeStream(key) { $0.volume = percent }
        }
    }

    /// The answer also lets the watches that waited for it go on.
    func answerDirectConnections(allow: Bool) async throws {
        let (waiting, outcome): ([StreamKey], StreamWatch) = locked {
            _directAnswers.append(allow)
            return (_streams.filter { $0.watch == .connecting }.map(\.key), _watchOutcome)
        }
        for key in waiting {
            try changeStream(key) { $0.watch = allow ? outcome : .unreachable(reason: .directConnectionsDeclined) }
        }
    }

    func setVideoSink(sink: VideoSink?) {
        locked { _videoSink = sink }
        updateFrameTimer()
    }

    func videoHandle() -> UInt64 { 0 }

    /// Runs the picture news while something is watched and a sink is set, and only then.
    private func updateFrameTimer() {
        locked {
            let watched = _streams.contains { if case .watching = $0.watch { true } else { false } }
            if watched && _videoSink != nil {
                guard frameTimer == nil else { return }
                let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "app.mumcord.mobile.fake-video"))
                timer.schedule(deadline: .now(), repeating: .milliseconds(66))
                timer.setEventHandler { [weak self] in self?.framesReady() }
                timer.resume()
                frameTimer = timer
            } else {
                frameTimer?.cancel()
                frameTimer = nil
            }
        }
    }

    private func framesReady() {
        let (sink, ids): (VideoSink?, [UInt64]) = locked {
            (_videoSink, _streams.filter { if case .watching = $0.watch { true } else { false } }.map(\.videoId))
        }
        for id in ids {
            sink?.onFrameReady(videoId: id, width: FakeVideoFrames.width, height: FakeVideoFrames.height)
        }
    }

    // MARK: Own camera

    // Opening starts the app's camera and shows the preview; starting shares it, or fails with
    // `cameraStartProblem`; stopping stops the app's camera. Leaving the channel and the end of
    // the connection turn it off as the engine does.

    private var _appCamera: CameraCapture?
    private var _cameraStartProblem: CameraProblem?
    private var _cameraViewers: UInt32 = 0
    private var _captureFailures: [CaptureFailure] = []
    private var _ownCameraState = OwnCameraState.off

    /// The camera the app gave the engine; nil before it gave one.
    var appCamera: CameraCapture? { locked { _appCamera } }
    /// Why "Start sharing" fails; nil: it works.
    var cameraStartProblem: CameraProblem? {
        get { locked { _cameraStartProblem } }
        set { locked { _cameraStartProblem = newValue } }
    }
    var captureFailures: [CaptureFailure] { locked { _captureFailures } }
    /// The own camera's state as last reported.
    var ownCameraState: OwnCameraState { locked { _ownCameraState } }

    private func emitCamera(_ state: OwnCameraState, _ problem: CameraProblem? = nil) {
        let viewers: UInt32 = locked {
            _ownCameraState = state
            return state == .sharing ? _cameraViewers : 0
        }
        emit(.ownCameraChanged(camera: OwnCamera(state: state, problem: problem, viewers: viewers)))
    }

    func setCameraCapture(capture: CameraCapture?) {
        locked { _appCamera = capture }
    }

    func cameraHandle() -> UInt64 { 0 }

    func openCameraPreview() async throws {
        guard let capture = appCamera else { throw BridgeError.Internal(detail: "camera not shared: no-camera") }
        guard case .connected = locked({ connection }) else { throw BridgeError.NotConnected(detail: "not connected") }
        capture.start()
        emitCamera(.preview)
    }

    func startCameraSharing() async throws {
        emitCamera(.starting)
        if let problem = cameraStartProblem {
            emitCamera(.preview, problem)
            throw BridgeError.Internal(detail: "camera not shared")
        }
        emitCamera(.sharing)
    }

    func stopCamera() async throws {
        appCamera?.stop()
        emitCamera(.off)
    }

    func cameraCaptureFailed(failure: CaptureFailure) {
        locked { _captureFailures.append(failure) }
        appCamera?.stop()
        emitCamera(.off, .cameraFailed)
    }

    /// The engine reports a new number of people who get the camera.
    func watchCamera(_ viewers: UInt32) {
        locked { _cameraViewers = viewers }
        emitCamera(.sharing)
    }

    /// Turns a running camera off without a reason, as the engine's stop rules do.
    private func endCamera() {
        guard ownCameraState != .off else { return }
        appCamera?.stop()
        emitCamera(.off)
    }

    // MARK: Identities

    /// The identities as they are; set before the engine is used, without an event.
    var identities: [Identity] {
        get { locked { _identities } }
        set { locked { _identities = newValue } }
    }

    /// The password every identity file of the fake has; empty for none.
    var filePassword: String {
        get { locked { _filePassword } }
        set { locked { _filePassword = newValue } }
    }

    /// When set, exporting fails with it.
    var exportError: BridgeError? {
        get { locked { _exportError } }
        set { locked { _exportError = newValue } }
    }

    /// The scan the next "Scan from desktop app" gets; without one the engine has no transfer.
    var transfer: TransferSession? {
        get { locked { _transfer } }
        set { locked { _transfer = newValue } }
    }

    func savedIdentities() -> [Identity] {
        locked { Self.ordered(_identities) }
    }

    func createIdentity(name: String, label: String?) async throws -> Identity {
        let fingerprint = locked { String(format: "%040llx", UInt64(nextIdentityId) &* 0x9E37_79B9) }
        return try addIdentity(name: name, label: label, fingerprint: fingerprint)
    }

    /// As the bridge: a wrong password and a file without an identity are the same failure. A
    /// file this fake exported holds the fingerprint, so importing it again is a duplicate.
    func importIdentity(data: Data, password: String, label: String?) async throws -> Identity {
        if password != filePassword { throw BridgeError.InvalidInput(detail: "wrong password or no identity") }
        let text = String(decoding: data, as: UTF8.self)
        let fingerprint = text.count == 40 && text.allSatisfy(\.isHexDigit)
            ? text
            : String(data.map { String(format: "%02x", $0) }.joined().prefix(40)).padding(toLength: 40, withPad: "0", startingAt: 0)
        return try addIdentity(name: label ?? fakeImportedName, label: label, fingerprint: fingerprint)
    }

    func exportIdentity(identityId: Int64, password: String) async throws -> Data {
        try locked {
            if let failure = _exportError { throw failure }
            guard let identity = _identities.first(where: { $0.id == identityId }) else {
                throw BridgeError.NotFound(detail: "no such identity")
            }
            return Data(identity.fingerprint.utf8)
        }
    }

    func setDefaultIdentity(identityId: Int64) async throws {
        let list: [Identity] = locked {
            _identities = _identities.map { identity in
                var changed = identity
                changed.isDefault = identity.id == identityId
                return changed
            }
            return Self.ordered(_identities)
        }
        emit(.identitiesChanged(identities: list))
    }

    /// As the bridge: the oldest remaining identity becomes the default, and servers that named
    /// the deleted one use the default again.
    func deleteIdentity(identityId: Int64) async throws {
        let (list, servers): ([Identity], [SavedServer]?) = try locked {
            if _identities.count < 2 { throw BridgeError.LastIdentity(detail: "the last identity cannot be deleted") }
            guard let deleted = _identities.first(where: { $0.id == identityId }) else {
                throw BridgeError.NotFound(detail: "no such identity")
            }
            _identities.removeAll { $0.id == identityId }
            if deleted.isDefault, let next = _identities.min(by: { ($0.createdMs, $0.id) < ($1.createdMs, $1.id) }) {
                _identities = _identities.map { identity in
                    var changed = identity
                    changed.isDefault = identity.id == next.id
                    return changed
                }
            }
            var servers: [SavedServer]?
            if storedServers.contains(where: { $0.identityId == identityId }) {
                storedServers = storedServers.map { server in
                    var changed = server
                    if changed.identityId == identityId { changed.identityId = nil }
                    return changed
                }
                servers = storedServers
            }
            return (Self.ordered(_identities), servers)
        }
        if let servers { emit(.serversChanged(servers: servers)) }
        emit(.identitiesChanged(identities: list))
    }

    func startTransfer() throws -> TransferSession {
        guard let transfer else { throw BridgeError.Internal(detail: "the fake engine has no transfer") }
        return transfer
    }

    /// Adds an identity as the bridge does: the first one becomes the default.
    @discardableResult
    func addIdentity(name: String, label: String?, fingerprint: String) throws -> Identity {
        let (identity, list): (Identity, [Identity]) = try locked {
            if _identities.contains(where: { $0.fingerprint == fingerprint }) {
                throw BridgeError.DuplicateIdentity(detail: "the identity is already there")
            }
            let identity = Identity(
                id: nextIdentityId,
                label: label ?? name,
                commonName: name,
                fingerprint: fingerprint,
                createdMs: nextIdentityId * 1000,
                isDefault: _identities.isEmpty,
                serverIds: []
            )
            nextIdentityId += 1
            _identities.append(identity)
            return (identity, Self.ordered(_identities))
        }
        emit(.identitiesChanged(identities: list))
        return identity
    }

    /// Adds servers of a transfer to the list, as its import does.
    func addServers(_ inputs: [ServerInput]) -> [SavedServer] {
        let (added, all): ([SavedServer], [SavedServer]) = locked {
            let added = inputs.map { input in
                let saved = Self.saved(input, id: nextServerId, hadPassword: false)
                nextServerId += 1
                return saved
            }
            storedServers += added
            return (added, storedServers)
        }
        emit(.serversChanged(servers: all))
        return added
    }

    private static func ordered(_ identities: [Identity]) -> [Identity] {
        identities.filter(\.isDefault) + identities.filter { !$0.isDefault }
    }

    private func finishConnecting() {
        emit(.connectionChanged(state: .connected))
        emit(.serverSynced(server: server))
        for entry in activityOnConnect {
            emit(.activityAdded(entry: entry))
        }
        emit(.audioWantedChanged(audio: audioOnConnect))
        if let level = levelOnConnect {
            emit(.inputLevelChanged(percent: level))
        }
        let streams = streamsOnConnect
        if !streams.isEmpty {
            showStreams(streams)
        }
    }
}
#endif
