// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import XCTest

@testable import Mumcord

/// Polls until the condition holds or the timeout is over. The session folds engine events on a
/// background task and the model starts tasks of its own, so tests wait for the state they expect
/// instead of reading it at once. The condition runs on the actor of the caller.
func eventually(
    timeout: TimeInterval = 5,
    isolation: isolated (any Actor)? = #isolation,
    _ condition: @escaping () async -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while true {
        if await condition() { return true }
        if Date() >= deadline { return false }
        try? await Task.sleep(nanoseconds: 10_000_000)
    }
}

/// `eventually` as an assertion, so a failure points at the line of the test.
func assertEventually(
    _ message: @autoclosure () -> String = "the expected state was not reached",
    file: StaticString = #filePath,
    line: UInt = #line,
    isolation: isolated (any Actor)? = #isolation,
    _ condition: @escaping () async -> Bool
) async {
    let reached = await eventually(isolation: isolation, condition)
    XCTAssertTrue(reached, message(), file: file, line: line)
}

/// Collects what a store under test did behind the scenes: the engines its factory created,
/// what it logged, and a failure the next engine start should throw.
final class EngineBox: @unchecked Sendable {
    private let lock = NSLock()
    private var created: [FakeEngine] = []
    private var logged: [String] = []
    private var failure: Error?

    private func locked<Value>(_ body: () -> Value) -> Value {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    var engines: [FakeEngine] { locked { created } }

    /// The engine the store created; only valid once a command has made it create one.
    var engine: FakeEngine {
        guard let engine = engines.last else { preconditionFailure("the engine has not been created yet") }
        return engine
    }

    var logs: [String] { locked { logged } }

    /// Thrown instead of creating an engine, until it is set back to nil.
    var startFailure: Error? {
        get { locked { failure } }
        set { locked { failure = newValue } }
    }

    fileprivate func add(_ engine: FakeEngine) {
        locked { created.append(engine) }
    }

    fileprivate func append(log message: String) {
        locked { logged.append(message) }
    }
}

/// A start of the engine that fails with something that is not an engine error.
struct EngineStartFailure: Error {}

/// A store around a `FakeEngine`, which the factory creates on the first command, as the real
/// one would. `configure` runs on the new engine before the store sees it. Without a
/// `linkParser` the native parser is used.
func makeStore(
    _ box: EngineBox,
    server: ServerState,
    prompt: CertificatePrompt? = nil,
    servers: [SavedServer] = [],
    configure: @escaping @Sendable (FakeEngine) -> Void = { _ in },
    linkParser: (@Sendable (String) throws -> MumbleLink)? = nil
) -> SessionStore {
    let factory: EngineFactory = { listener in
        if let failure = box.startFailure { throw failure }
        let engine = FakeEngine(listener: listener, server: server, prompt: prompt, servers: servers)
        configure(engine)
        box.add(engine)
        return engine
    }
    let log: @Sendable (String) -> Void = { box.append(log: $0) }
    guard let linkParser else { return SessionStore(engineFactory: factory, log: log) }
    return SessionStore(engineFactory: factory, log: log, linkParser: linkParser)
}

/// `mumble://[user@]host[:port][/channel/…]`, enough of the real parser for these tests.
func parseTestLink(_ url: String) throws -> MumbleLink {
    let scheme = "mumble://"
    guard url.hasPrefix(scheme) else { throw BridgeError.InvalidInput(detail: "not a mumble link") }
    let rest = url.dropFirst(scheme.count)
    let parts = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
    let authority = parts.first.map(String.init) ?? ""
    let path = parts.count > 1 ? String(parts[1]) : ""
    let userAndHost = authority.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
    let hostPort = String(userAndHost.last ?? "")
    let user = userAndHost.count > 1 ? String(userAndHost[0]) : ""
    let hostAndPort = hostPort.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
    guard let port = hostAndPort.count > 1 ? UInt16(hostAndPort[1]) : 64738 else {
        throw BridgeError.InvalidInput(detail: "bad port")
    }
    return MumbleLink(
        address: String(hostAndPort[0]),
        port: port,
        username: user.isEmpty ? nil : user,
        channelPath: path.split(separator: "/").map(String.init)
    )
}

extension Outcome {
    /// The value of a successful command.
    var value: Value? {
        if case .ok(let value) = self { return value }
        return nil
    }

    /// The error of a failed command.
    var failure: ConnectionError? {
        if case .failed(let error) = self { return error }
        return nil
    }
}

/// The phone's side of voice and of the local network, faked, for a model under test.
final class PhoneFakes {
    let microphone: FakeMicrophoneGateway
    let backend = FakeStreamBackend()
    let session = FakeVoiceSession()
    let localNetwork: FakeLocalNetworkGateway
    /// How often the app's page in the system settings was opened.
    private(set) var settingsOpened = 0

    init(
        microphone: FakeMicrophoneGateway = FakeMicrophoneGateway(),
        localNetwork: FakeLocalNetworkGateway = FakeLocalNetworkGateway()
    ) {
        self.microphone = microphone
        self.localNetwork = localNetwork
    }

    /// A model on `store` whose audio, microphone question and local network are these fakes.
    @MainActor
    func makeModel(store: SessionStore, setupStore: SetupStore) -> AppModel {
        AppModel(
            store: store,
            setupStore: setupStore,
            microphone: VoiceAudio(
                store: store,
                gateway: microphone,
                backend: backend,
                session: session,
                scheduler: QueueAudioScheduler(),
                log: { _ in }
            ),
            localNetwork: localNetwork,
            openSettings: { [weak self] in self?.settingsOpened += 1 }
        )
    }
}

extension AppModel {
    /// A model for tests that do not look at voice or the local network: the microphone is
    /// allowed and every server is on the internet.
    convenience init(store: SessionStore, setupStore: SetupStore) {
        let fakes = PhoneFakes()
        self.init(
            store: store,
            setupStore: setupStore,
            microphone: VoiceAudio(
                store: store,
                gateway: fakes.microphone,
                backend: fakes.backend,
                session: fakes.session,
                scheduler: QueueAudioScheduler(),
                log: { _ in }
            ),
            localNetwork: fakes.localNetwork,
            openSettings: {}
        )
    }
}
