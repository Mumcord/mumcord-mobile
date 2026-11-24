// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The result of a command that the screen which started it reports itself (a form, a sheet).
enum Outcome<Value> {
    case ok(Value)
    case failed(ConnectionError)
}

/// Creates the engine. A seam so tests can run the store against a fake engine. It reads the
/// database and the secure storage and may throw; the store calls it off the main thread.
typealias EngineFactory = @Sendable (EngineListener) throws -> EngineClient

/// Owns the engine, the server list and the one connection. The only place that talks to the
/// bridge: screens read the state and call the commands through `AppModel`.
///
/// The state is an immutable value behind a lock, so it can be read from any thread; engine
/// events are folded into it on a background task, never on the main thread.
final class SessionStore: @unchecked Sendable {
    private let lock = NSLock()
    private var current = SessionState()
    private var observers: [UUID: AsyncStream<SessionState>.Continuation] = [:]
    private var levelObservers: [UUID: AsyncStream<Int>.Continuation] = [:]
    /// The engine once it exists, for the commands that must never wait (a button let go).
    private var createdEngine: EngineClient?
    private let engine: EngineHolder
    private let log: @Sendable (String) -> Void
    private let linkParser: @Sendable (String) throws -> MumbleLink

    // ----- getting back in by the app itself, see `reconnectNow` -----

    /// The password typed for the running connection only (not the saved one). Kept in memory
    /// so that `reconnectNow` can use it again; forgotten when the connection ends, never
    /// stored and never logged.
    private var sessionPassword: String?
    /// True while the state hides that the connection is ended and started again.
    private var resuming = false
    private var resumeTask: Task<Void, Never>?
    /// What the engine last said about the connection, also what `resuming` hides.
    private var raw = RawConnection()
    private var rawObservers: [UUID: AsyncStream<RawConnection>.Continuation] = [:]
    /// The last change of the settings, see `afterEarlierSettingsChanges`.
    private var settingsChange: Task<Outcome<Void>, Never>?

    private struct RawConnection {
        var connection: ConnectionState?
        /// How often a server's channels arrived; grows with every connection that got in.
        var synced = 0
    }

    /// How long ending the connection and getting in again may take together.
    private static let resumeTimeout: TimeInterval = 30
    /// How long the engine may take to end a connection it is told to end.
    private static let stopTimeout: TimeInterval = 5
    /// Chat messages, unread counts, notifications, message requests and blocked people as the
    /// engine reports them, for `ChatStore`, its one reader. They never pass through the state, so chat traffic does not redraw the channels.
    let chatEvents: AsyncStream<BridgeEvent>

    /// - Parameters:
    ///   - log: receives short technical messages; never names, addresses, passwords or other
    ///     personal data.
    ///   - linkParser: reads a `mumble://` link; a seam for tests.
    init(
        engineFactory: @escaping EngineFactory,
        log: @escaping @Sendable (String) -> Void,
        linkParser: @escaping @Sendable (String) throws -> MumbleLink = { try parseMumbleLink(url: $0) }
    ) {
        self.log = log
        self.linkParser = linkParser
        // The engine calls back on its own thread, which must never wait: the callback only
        // queues the event, and a single reader keeps them in order.
        let (events, queue) = AsyncStream<BridgeEvent>.makeStream()
        engine = EngineHolder(factory: engineFactory, listener: QueueingListener(queue: queue))
        // A reader that is behind loses the oldest events instead of holding ever more of them.
        let (chatEvents, chatQueue) = AsyncStream<BridgeEvent>.makeStream(
            bufferingPolicy: .bufferingNewest(Self.chatEventBuffer)
        )
        self.chatEvents = chatEvents
        Task.detached { [weak self] in
            for await event in events {
                switch event {
                case .inputLevelChanged(let percent):
                    self?.publishInputLevel(Int(percent))
                case .chatMessageReceived, .chatUnreadChanged, .chatNotification, .messageRequestsChanged,
                     .blockedPeopleChanged:
                    chatQueue.yield(event)
                default:
                    self?.receive(event)
                }
            }
        }
    }

    private static let chatEventBuffer = 256

    /// The latest state.
    var state: SessionState {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    /// The current state and every later one, in order.
    func states() -> AsyncStream<SessionState> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            observers[id] = continuation
            continuation.yield(current)
            lock.unlock()
            continuation.onTermination = { [weak self] _ in self?.removeObserver(id) }
        }
    }

    /// Waits for the first state that fulfils the condition.
    func first(where condition: (SessionState) -> Bool) async -> SessionState {
        for await state in states() where condition(state) {
            return state
        }
        return state
    }

    private func removeObserver(_ id: UUID) {
        lock.lock()
        observers[id] = nil
        levelObservers[id] = nil
        rawObservers[id] = nil
        lock.unlock()
    }

    /// The microphone level for the level meter, 0 to 100, up to 20 times a second. Only the
    /// newest value is kept for a reader that is behind.
    func inputLevels() -> AsyncStream<Int> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            lock.lock()
            levelObservers[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in self?.removeObserver(id) }
        }
    }

    private func publishInputLevel(_ percent: Int) {
        lock.lock()
        defer { lock.unlock() }
        for observer in levelObservers.values {
            observer.yield(percent)
        }
    }

    private func update(_ change: (SessionState) -> SessionState) {
        lock.lock()
        defer { lock.unlock() }
        setLocked(change(current))
    }

    /// Call with the lock held.
    private func setLocked(_ next: SessionState) {
        guard next != current else { return }
        current = next
        // Sent while the lock is held, so observers see the states in order; it never waits.
        for observer in observers.values {
            observer.yield(next)
        }
    }

    /// Folds an engine event; while the app gets back in by itself (`reconnectNow`) the end and
    /// the start of the connection are hidden. The password of the connection is forgotten
    /// once it is over.
    private func receive(_ event: BridgeEvent) {
        lock.lock()
        defer { lock.unlock() }
        if case .connectionChanged(let state) = event { raw.connection = state }
        if case .serverSynced = event { raw.synced += 1 }
        for observer in rawObservers.values { observer.yield(raw) }
        setLocked(resuming ? current.foldResuming(event) : current.fold(event))
        if !resuming && !current.isBusy { sessionPassword = nil }
    }

    // MARK: Commands

    /// Reads the saved servers once; later changes arrive as events. A failure can be retried.
    func loadServers() async {
        if state.serversLoaded { return }
        switch await call("load servers", { ($0.savedServers(), $0.savedIdentities()) }) {
        // An event that arrived in between is newer than the snapshot.
        case .ok(let (servers, identities)):
            update { state in
                if state.serversLoaded { return state }
                var next = state
                next.servers = servers
                next.serversLoaded = true
                if next.identities == nil { next.identities = identities }
                return next
            }
            // The engine exists now; nothing else reports the talk mode it has saved.
            if case .ok(let mode) = await call("read talk mode", { try await $0.talkMode() }) {
                update { state in
                    var next = state
                    next.talkMode = mode
                    return next
                }
            }
            // An event that arrived in between is newer than what is read here.
            if case .ok(let settings) = await call("mobile settings", { $0.mobileSettings() }) {
                update { state in
                    if state.mobileSettings != nil { return state }
                    var next = state
                    next.mobileSettings = settings
                    return next
                }
            }
            if case .ok(let settings) = await call("app settings", { $0.appSettings() }) {
                update { state in
                    if state.appSettings != nil { return state }
                    var next = state
                    next.appSettings = settings
                    return next
                }
            }
        case .failed(let error):
            update { state in
                var next = state
                next.phase = .failed(error)
                return next
            }
        }
    }

    func addServer(_ input: ServerInput) async -> Outcome<SavedServer> {
        await call("add server") { try await $0.addServer(input: input) }
    }

    func updateServer(_ serverId: Int64, _ input: ServerInput) async -> Outcome<SavedServer> {
        await call("update server") { try await $0.updateServer(serverId: serverId, input: input) }
    }

    func removeServer(_ serverId: Int64) async -> Outcome<Void> {
        await call("remove server") { try await $0.removeServer(serverId: serverId) }
    }

    /// People online and latency of a saved server, or nil when it does not answer.
    func pingServer(_ serverId: Int64, refresh: Bool) async -> ServerPing? {
        switch await call("ping server", { try await $0.pingServer(serverId: serverId, refresh: refresh) }) {
        case .ok(let ping): ping
        case .failed: nil
        }
    }

    func setTalkMode(_ mode: TalkMode) async -> Outcome<Void> {
        let outcome: Outcome<Void> = await call("set talk mode") { try await $0.setTalkMode(mode: mode) }
        if case .ok = outcome {
            update { state in
                var next = state
                next.talkMode = mode
                return next
            }
        }
        return outcome
    }

    /// Switches the talk mode while connected; a failure is shown like a refusal.
    func changeTalkMode(_ mode: TalkMode) async {
        report(await setTalkMode(mode))
    }

    // MARK: Voice

    /// The engine's handles for the audio threads, or nil while the engine does not exist.
    func audioEndpoints() -> AudioEndpoints? {
        engineNow()?.audioEndpoints()
    }

    /// The hold-to-talk button went down or up. Never waits, so a release always gets through.
    func setPushToTalkPressed(_ pressed: Bool) {
        engineNow()?.setPushToTalkPressed(pressed: pressed)
    }

    func setSelfMute(_ muted: Bool) async {
        report(await call("set self mute") { try await $0.setSelfMute(muted: muted) })
    }

    func setSelfDeaf(_ deafened: Bool) async {
        report(await call("set self deaf") { try await $0.setSelfDeaf(deafened: deafened) })
    }

    /// Sends the user's voice to `target` instead of their channel until `stopWhisper`. False
    /// when it was refused; the reason is then shown like any other refusal.
    func startWhisper(_ target: WhisperTarget) async -> Bool {
        do {
            let client = try await engine.get()
            try await client.startWhisper(target: target)
            return true
        } catch let error as BridgeError {
            // Only the kind is logged; the text can name a person or a channel.
            log("start whisper failed: \(error.connectionError)")
            update { state in
                var next = state
                next.denied = DeniedAction(channelId: nil, detail: error.detail)
                return next
            }
            return false
        } catch {
            report(failed("start whisper", error))
            return false
        }
    }

    /// Ends the whisper or shout. Never waits, so a release always gets through.
    func stopWhisper() {
        engineNow()?.stopWhisper()
    }

    /// Tells the engine which chat is on screen, or that none is. Never waits; without an
    /// engine there is nobody to tell.
    func setOpenChat(_ chat: ChatId?) {
        engineNow()?.setOpenChat(chat: chat)
    }

    private func engineNow() -> EngineClient? {
        lock.lock()
        defer { lock.unlock() }
        return createdEngine
    }

    /// The target of a `mumble://` link. Nothing is saved or connected by reading it.
    func parseLink(_ url: String) -> Outcome<MumbleLink> {
        do {
            return .ok(try linkParser(url))
        } catch {
            return failed("parse link", error)
        }
    }

    /// Connects to a saved server. `password` replaces the saved one for this attempt only; a
    /// non-empty `channelPath` (from a link) is joined after connecting. The caller makes sure
    /// that no other connection is alive (one server at a time).
    func connectServer(_ serverId: Int64, password: String?, channelPath: [String]) async {
        // Shown at once: creating the engine on first use takes a moment.
        update { state in
            sessionPassword = password
            var next = state
            next.phase = .connecting
            next.certificatePrompt = nil
            next.server = nil
            next.activeServerId = serverId
            return next
        }
        let result = await call("connect") {
            try await $0.connectServer(serverId: serverId, password: password, channelPath: channelPath)
        }
        if case .failed(let error) = result {
            update { state in
                var next = state
                next.phase = .failed(error)
                next.certificatePrompt = nil
                next.server = nil
                next.streams = []
                next.directConnectionsQuestion = false
                return next
            }
        }
    }

    func answerCertificatePrompt(fingerprint: String, trust: Bool) async {
        update { state in
            var next = state
            next.certificatePrompt = nil
            return next
        }
        let result = await call("answer certificate prompt") {
            try await $0.answerCertificatePrompt(fingerprint: fingerprint, trust: trust)
        }
        if case .failed(let error) = result {
            update { state in
                var next = state
                next.phase = .failed(error)
                next.server = nil
                return next
            }
        }
    }

    func disconnect() async {
        lock.withLock {
            sessionPassword = nil
            guard resuming else { return }
            // Leaving wins over getting back in; what the state hid is shown now.
            resumeTask?.cancel()
            resuming = false
            setLocked(current.fold(.connectionChanged(state: .disconnected(error: nil))))
        }
        let result = await call("disconnect") { try await $0.disconnect() }
        if case .failed(let error) = result {
            update { state in
                var next = state
                next.phase = .failed(error)
                next.certificatePrompt = nil
                next.server = nil
                return next
            }
        }
    }

    /// Gets back into the server at once, for the moment a network is there again while the
    /// engine still waits before its next attempt (FR-5.40). The engine has no command for
    /// that, so the connection is ended and started again: into the channel the user was in,
    /// with the mute and deafen they had, and with the password of this connection. Meanwhile
    /// the state stays "Reconnecting". A running whisper ends.
    ///
    /// Does nothing unless the engine is reconnecting, and never runs twice at a time. When it
    /// does not get in, the connection ends as lost, like the engine's own last attempt.
    func reconnectNow() async {
        let task: Task<Void, Never>? = lock.withLock {
            guard resumeTask == nil, case .reconnecting = current.phase else { return nil }
            let task = Task { [weak self] in
                guard let self else { return }
                await self.resume()
            }
            resumeTask = task
            return task
        }
        await task?.value
    }

    private func resume() async {
        defer {
            lock.withLock {
                resuming = false
                resumeTask = nil
            }
        }
        let before = state
        guard let serverId = before.activeServerId else { return }
        let channelPath = before.server?.selfChannelPath() ?? []
        let voice = selfVoice(before)
        let (syncedBefore, password): (Int, String?) = lock.withLock {
            resuming = true
            return (raw.synced, sessionPassword)
        }
        let back = await withTimeout(Self.resumeTimeout) { [self] in
            guard case .ok = await call("disconnect", { try await $0.disconnect() }) else { return false }
            await firstRaw { $0.connection.isDisconnected }
            // Forgotten, so that the end waited for below is the new connection's.
            lock.withLock { raw.connection = nil }
            let connect = await call("reconnect") {
                try await $0.connectServer(serverId: serverId, password: password, channelPath: channelPath)
            }
            guard case .ok = connect else { return false }
            let end = await firstRaw { $0.connection.isDisconnected || $0.connection.isConnected }
            guard end.connection.isConnected else { return false }
            await firstRaw { $0.synced > syncedBefore }
            return true
        }
        // The user left meanwhile; `disconnect` has shown that already.
        if Task.isCancelled { return }
        if back {
            lock.withLock { resuming = false }
            if let voice { await restore(voice) }
        } else {
            await lost()
        }
    }

    /// The connection could not be got back: it ends as lost, like the engine's own last
    /// attempt. The engine is told to stop while the state still hides its orderly end, which
    /// would otherwise look as if the user had left.
    private func lost() async {
        if !lock.withLock({ raw.connection.isDisconnected }) {
            _ = await call("disconnect") { try await $0.disconnect() }
            _ = await withTimeout(Self.stopTimeout) { [self] in
                await firstRaw { $0.connection.isDisconnected }
                return true
            }
        }
        lock.withLock {
            sessionPassword = nil
            // A failure the engine reported itself is already shown with its own reason.
            if case .failed = current.phase { return }
            var next = current
            next.phase = .failed(.serverUnreachable)
            next.certificatePrompt = nil
            next.server = nil
            setLocked(next)
        }
    }

    /// Puts back the mute and deafen the user had, where the new connection starts otherwise.
    private func restore(_ voice: SelfVoice) async {
        guard let now = selfVoice(state), now != voice else { return }
        if voice.deafened {
            await setSelfDeaf(true)
        } else {
            if now.deafened { await setSelfDeaf(false) }
            await setSelfMute(voice.muted)
        }
    }

    /// Waits for the first raw connection that fulfils the condition, or for the cancellation
    /// of the waiting task.
    @discardableResult
    private func firstRaw(_ condition: @escaping (RawConnection) -> Bool) async -> RawConnection {
        let id = UUID()
        let stream = AsyncStream<RawConnection> { continuation in
            lock.withLock {
                rawObservers[id] = continuation
                continuation.yield(raw)
            }
            continuation.onTermination = { [weak self] _ in self?.removeObserver(id) }
        }
        for await value in stream where condition(value) {
            return value
        }
        return lock.withLock { raw }
    }

    /// Runs `work`, or gives up with false after `seconds`.
    private func withTimeout(_ seconds: TimeInterval, _ work: @escaping @Sendable () async -> Bool) async -> Bool {
        await withTaskGroup(of: Bool?.self) { group in
            group.addTask { await work() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? false
        }
    }

    /// Limits the voice bitrate in kbit/s, or lifts the limit with nil. The engine does not
    /// keep it across a restart, so it is set again whenever it could have been lost. Never
    /// waits; without an engine there is nothing to limit yet.
    func setBitrateLimit(_ kbps: UInt32?) {
        engineNow()?.setBitrateLimitKbps(kbps: kbps)
    }

    /// Asks to enter a channel; a refusal arrives as a password question or a denial.
    func joinChannel(_ channelId: UInt32, password: String?) async {
        dismissDenied()
        report(await call("join channel") { try await $0.joinChannel(channelId: channelId, password: password) })
    }

    /// How loud a person is for the user only, 0 to 200 percent.
    func setPersonVolume(_ session: UInt32, percent: Int) async {
        let clamped = UInt16(min(max(percent, 0), 200))
        report(await call("set person volume") { try await $0.setPersonVolume(session: session, percent: clamped) })
    }

    func setPersonLocalMute(_ session: UInt32, muted: Bool) async {
        report(await call("set person mute") { try await $0.setPersonLocalMute(session: session, muted: muted) })
    }

    // MARK: Streams (FR-5.70 … FR-5.73)

    // How a watch goes shows in the stream's state; a failed command only means that the
    // stream or the connection went away meanwhile, so it is logged and not shown.

    func watchStream(_ session: UInt32, kind: StreamKind) async {
        _ = await call("watch stream") { try await $0.watchStream(session: session, kind: kind) }
    }

    func stopWatchingStream(_ session: UInt32, kind: StreamKind) async {
        _ = await call("stop watching stream") { try await $0.stopWatchingStream(session: session, kind: kind) }
    }

    /// How loud a person's screen share sound plays, 0 to 200 percent.
    func setStreamVolume(_ session: UInt32, percent: Int) async {
        let clamped = UInt16(min(max(percent, 0), 200))
        _ = await call("set stream volume") { try await $0.setStreamVolume(session: session, percent: clamped) }
    }

    /// Answers "Allow direct connections?"; the question goes away either way.
    func answerDirectConnections(_ allow: Bool) async {
        update { state in
            var next = state
            next.directConnectionsQuestion = false
            return next
        }
        _ = await call("answer direct connections") { try await $0.answerDirectConnections(allow: allow) }
    }

    /// Where the news of new stream pictures goes, once an engine exists. Never waits.
    func setVideoSink(_ sink: VideoSink?) {
        engineNow()?.setVideoSink(sink: sink)
    }

    /// The handle for the engine's picture functions, or 0 while no engine exists. Never waits.
    func videoHandle() -> UInt64 {
        engineNow()?.videoHandle() ?? 0
    }

    // MARK: Moderation (FR-5.90 … FR-5.93)

    // The commands that change a person give true when the server confirmed the change and
    // false when it did not answer in time; either way the tree shows what happened.

    func movePerson(_ session: UInt32, to channelId: UInt32) async -> Result<Bool, ModerationFailure> {
        await moderate("move person") { try await $0.movePerson(session: session, channelId: channelId) }
    }

    func setServerMute(_ session: UInt32, muted: Bool) async -> Result<Bool, ModerationFailure> {
        await moderate("set server mute") { try await $0.setServerMute(session: session, muted: muted) }
    }

    func kickPerson(_ session: UInt32, reason: String?) async -> Result<Bool, ModerationFailure> {
        await moderate("kick person") { try await $0.kickPerson(session: session, reason: reason) }
    }

    /// What the ban sheet needs about a person; asks the server (up to a few seconds).
    func banTarget(_ session: UInt32) async -> Result<BanTarget, ModerationFailure> {
        await moderate("read ban target") { try await $0.banTarget(session: session) }
    }

    func banPerson(_ session: UInt32, _ ban: BanInput) async -> Result<BanResult, ModerationFailure> {
        await moderate("ban person") { try await $0.banPerson(session: session, ban: ban) }
    }

    /// Runs a moderation command. Its sheet shows a refusal in the server's own words, which
    /// are kept out of the log like every other detail.
    private func moderate<Value>(
        _ name: String,
        _ command: (EngineClient) async throws -> Value
    ) async -> Result<Value, ModerationFailure> {
        do {
            let client = try await engine.get()
            lock.withLock { createdEngine = client }
            return .success(try await command(client))
        } catch {
            var refusal: String?
            if case .PermissionDenied(let detail) = error as? BridgeError { refusal = detail }
            return .failure(moderationFailure(logged(name, error), refusal: refusal))
        }
    }

    /// Gives up on a channel password question.
    func dismissChannelPassword() {
        update { state in
            var next = state
            next.channelPassword = nil
            return next
        }
    }

    func dismissDenied() {
        update { state in
            var next = state
            next.denied = nil
            return next
        }
    }

    /// Puts a shown error away.
    func dismissFailure() {
        update { state in
            guard case .failed = state.phase else { return state }
            var next = state
            next.phase = .idle
            next.activeServerId = nil
            return next
        }
    }

    /// A command of the connected screens that failed in the engine is shown like a refusal.
    private func report(_ outcome: Outcome<Void>) {
        guard case .failed = outcome else { return }
        reportFailure()
    }

    /// Shows a failed command of the connected screens like a refusal by the server.
    func reportFailure() {
        update { state in
            var next = state
            next.denied = DeniedAction(channelId: nil, detail: "")
            return next
        }
    }

    /// The phone settings, or nil when they cannot be read.
    func mobileSettings() async -> MobileSettings? {
        await call("mobile settings") { $0.mobileSettings() }.value
    }

    /// Reads the phone settings, changes them and saves the result.
    func updateMobileSettings(_ change: @escaping @Sendable (inout MobileSettings) -> Void) async -> Outcome<Void> {
        await afterEarlierSettingsChanges { [self] in
            let read = await call("mobile settings") { $0.mobileSettings() }
            guard case .ok(var settings) = read else {
                if case .failed(let error) = read { return .failed(error) }
                return .failed(.internalError)
            }
            change(&settings)
            let changed = settings
            let saved: Outcome<Void> = await call("set mobile settings") {
                try await $0.setMobileSettings(settings: changed)
            }
            if case .ok = saved {
                update { state in
                    var next = state
                    next.mobileSettings = changed
                    return next
                }
            }
            return saved
        }
    }

    /// Reads the settings shared with the desktop (notifications, chat history), changes them
    /// and saves the result, as `updateMobileSettings` does for the phone settings.
    func updateAppSettings(_ change: @escaping @Sendable (inout AppSettings) -> Void) async -> Outcome<Void> {
        await afterEarlierSettingsChanges { [self] in
            let read = await call("app settings") { $0.appSettings() }
            guard case .ok(var settings) = read else {
                if case .failed(let error) = read { return .failed(error) }
                return .failed(.internalError)
            }
            change(&settings)
            let changed = settings
            let saved: Outcome<Void> = await call("set app settings") {
                try await $0.setAppSettings(settings: changed)
            }
            if case .ok = saved {
                update { state in
                    var next = state
                    next.appSettings = changed
                    return next
                }
            }
            return saved
        }
    }

    /// Runs a change of the settings once every earlier one is done. A change reads the whole
    /// record and saves it back, and the settings have several writers (the More tab, the
    /// notification settings, the audio output): two changes at the same time would each save
    /// what they read, and the later save would undo the earlier one.
    private func afterEarlierSettingsChanges(
        _ change: @escaping @Sendable () async -> Outcome<Void>
    ) async -> Outcome<Void> {
        let task = lock.withLock {
            let previous = settingsChange
            let task = Task {
                _ = await previous?.value
                return await change()
            }
            settingsChange = task
            return task
        }
        return await task.value
    }

    /// The voice settings as they apply now, or nil when they cannot be read.
    func voiceSettings() async -> VoiceSettings? {
        await call("voice settings") { try await $0.voiceSettings() }.value
    }

    /// Saves whether noise suppression runs; the engine applies it at once.
    func setNoiseSuppression(_ on: Bool) async -> Outcome<Void> {
        await call("set noise suppression") { try await $0.setNoiseSuppression(on: on) }
    }

    /// Runs a command on the engine, creating it on first use. `name` is what the log calls it.
    func call<Value>(
        _ name: String,
        _ command: (EngineClient) async throws -> Value
    ) async -> Outcome<Value> {
        do {
            let client = try await engine.get()
            lock.withLock { createdEngine = client }
            return .ok(try await command(client))
        } catch {
            return failed(name, error)
        }
    }

    #if DEBUG
    /// Shows a hint for something the app cannot show. Nothing in the engine reports these
    /// yet, so only debug launches and tests put one on screen.
    func showHint(_ hint: UnsupportedHint) {
        update { $0.withHint(hint) }
    }
    #endif

    private func failed<Value>(_ command: String, _ error: Error) -> Outcome<Value> {
        .failed(logged(command, error))
    }

    /// The kind of a failed command, written to the log.
    private func logged(_ command: String, _ error: Error) -> ConnectionError {
        let kind: ConnectionError
        if let bridgeError = error as? BridgeError {
            kind = bridgeError.connectionError
        } else if error is CocoaError {
            // The folder for the engine's data could not be prepared.
            kind = .io
        } else {
            kind = .internalError
        }
        // Only the kind of failure: the detail text can contain the server address.
        log("\(command) failed: \(kind) (\(type(of: error)))")
        return kind
    }
}

/// Created on first use and kept for the life of the process; a failed start is retried. An
/// actor, so two commands at the same time cannot create two engines, and never the main thread.
private actor EngineHolder {
    private let factory: EngineFactory
    private let listener: EngineListener
    private var created: EngineClient?

    init(factory: @escaping EngineFactory, listener: EngineListener) {
        self.factory = factory
        self.listener = listener
    }

    func get() throws -> EngineClient {
        if let created { return created }
        let engine = try factory(listener)
        created = engine
        return engine
    }
}

private final class QueueingListener: EngineListener, @unchecked Sendable {
    private let queue: AsyncStream<BridgeEvent>.Continuation

    init(queue: AsyncStream<BridgeEvent>.Continuation) {
        self.queue = queue
    }

    func onEvent(event: BridgeEvent) {
        queue.yield(event)
    }
}

private extension Optional where Wrapped == ConnectionState {
    var isDisconnected: Bool {
        if case .disconnected = self { return true }
        return false
    }

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}
