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
    /// Chat messages and unread counts as the engine reports them, for `ChatStore`, its one
    /// reader. They never pass through the state, so chat traffic does not redraw the channels.
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
                case .chatMessageReceived, .chatUnreadChanged:
                    chatQueue.yield(event)
                default:
                    self?.update { $0.fold(event) }
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
        let next = change(current)
        guard next != current else { return }
        current = next
        // Sent while the lock is held, so observers see the states in order; it never waits.
        for observer in observers.values {
            observer.yield(next)
        }
    }

    // MARK: Commands

    /// Reads the saved servers once; later changes arrive as events. A failure can be retried.
    func loadServers() async {
        if state.serversLoaded { return }
        switch await call("load servers", { $0.savedServers() }) {
        // An event that arrived in between is newer than the snapshot.
        case .ok(let servers):
            update { state in
                if state.serversLoaded { return state }
                var next = state
                next.servers = servers
                next.serversLoaded = true
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
        update { state in
            var next = state
            next.denied = DeniedAction(channelId: nil, detail: "")
            return next
        }
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
        return .failed(kind)
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
