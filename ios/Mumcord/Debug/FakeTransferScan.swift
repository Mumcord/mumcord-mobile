// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// A "Send to your phone" scan that follows the rules of the bridge's one without its
/// cryptography: `parts` are the texts of the QR codes, `code` opens them, `preview` is what
/// they hold and `importer` what importing does. The real format is tested in the engine.
///
/// Debug builds only: the unit tests and the UI tests feed it scripted QR texts.
final class FakeTransferScan: TransferSession, @unchecked Sendable {
    private static let maxAttempts: UInt8 = 3

    private let parts: [String]
    private let code: String
    private let preview: TransferPreview
    private let importer: @Sendable () throws -> ImportReport
    private let lock = NSLock()
    private var collected: [String] = []
    private var attempts = FakeTransferScan.maxAttempts
    private var opened = false
    private var _failNext: TransferError?
    private var _importFailure: BridgeError?
    private var _resets = 0
    private var _checkedCodes = 0
    private var _openDelay: Duration = .zero

    init(
        parts: [String],
        code: String,
        preview: TransferPreview,
        importer: @escaping @Sendable () throws -> ImportReport
    ) {
        self.parts = parts
        self.code = code
        self.preview = preview
        self.importer = importer
    }

    private func locked<Value>(_ body: () throws -> Value) rethrows -> Value {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    /// Makes the next step fail with it, once.
    var failNext: TransferError? {
        get { locked { _failNext } }
        set { locked { _failNext = newValue } }
    }

    /// When set, importing fails with it, so the preview stays for another try.
    var importFailure: BridgeError? {
        get { locked { _importFailure } }
        set { locked { _importFailure = newValue } }
    }

    /// How long checking the code takes; the bridge's check takes one to two seconds.
    var openDelay: Duration {
        get { locked { _openDelay } }
        set { locked { _openDelay = newValue } }
    }

    /// How often the scan was thrown away.
    var resets: Int { locked { _resets } }

    /// How many codes reached the check.
    var checkedCodes: Int { locked { _checkedCodes } }

    private func failure() -> TransferError? {
        defer { _failNext = nil }
        return _failNext
    }

    private func clear() {
        collected = []
        attempts = Self.maxAttempts
        opened = false
    }

    private var standing: TransferProgress {
        TransferProgress(have: UInt8(collected.count), total: UInt8(parts.count))
    }

    func addPart(text: String) throws -> TransferProgress {
        try locked {
            if let failed = failure() {
                if case .Expired = failed { clear() }
                if case .ClockSkew = failed { clear() }
                throw failed
            }
            guard parts.contains(text) else { throw TransferError.NotATransfer }
            if !collected.contains(text) { collected.append(text) }
            return standing
        }
    }

    func progress() -> TransferProgress? {
        locked { collected.isEmpty ? nil : standing }
    }

    func codeDigits() -> UInt8 {
        UInt8(code.count)
    }

    func attemptsLeft() -> UInt8 {
        locked { attempts }
    }

    func reset() {
        locked {
            _resets += 1
            clear()
        }
    }

    func open(code typed: String) async throws -> TransferPreview {
        let delay = openDelay
        if delay > .zero { try? await Task.sleep(for: delay) }
        return try locked {
            _checkedCodes += 1
            if let failed = failure() {
                switch failed {
                case .OutOfMemory, .Failed: break
                default: clear()
                }
                throw failed
            }
            if collected.count < parts.count { throw TransferError.Incomplete }
            if typed.count != code.count { throw TransferError.InvalidCode(digits: UInt8(code.count)) }
            if typed != code {
                attempts -= 1
                if attempts == 0 {
                    clear()
                    throw TransferError.RescanRequired
                }
                throw TransferError.WrongCode(attemptsLeft: attempts)
            }
            opened = true
            return preview
        }
    }

    func importOpened() async throws -> ImportReport {
        try locked {
            if let failure = _importFailure {
                _importFailure = nil
                throw failure
            }
            if !opened { throw BridgeError.InvalidInput(detail: "no transfer is open") }
        }
        return try importer()
    }
}

/// The transfer of the mockup `18-qr-scan`: seven QR codes holding the identity "Lea" of the
/// desktop and four servers, one of which the phone already has.
enum FakeDesktopTransfer {
    static let parts = (1...7).map { "mumcord-fake-transfer-part-\($0)-of-7" }
    /// Opens the fake transfer; a scripted value of the debug build, not a real code.
    static let code = "48217305"
    static let wrongCode = "11112222"
    static let foreignText = "https://example.org/not-a-transfer"

    static let identityFingerprint = "9c1e5d77a0b4f3e2".padding(toLength: 40, withPad: "7", startingAt: 0)

    static let servers = [
        ServerInput(name: "Nordlicht", address: "nordlicht.example.org", port: 64738, username: "Lea", password: .clear, identityId: nil),
        ServerInput(name: "Tidepool", address: "tidepool.example.org", port: 64738, username: "Lea", password: .clear, identityId: nil),
        ServerInput(name: "Lan party", address: "192.168.1.20", port: 64738, username: "Lea", password: .clear, identityId: nil),
        ServerInput(name: "Night owls", address: "owls.example.org", port: 64739, username: "Lea", password: .clear, identityId: nil),
    ]

    static var preview: TransferPreview {
        TransferPreview(
            identities: [TransferIdentityPreview(label: "Lea", fingerprint: identityFingerprint, isDefault: true)],
            servers: servers.map {
                TransferServerPreview(name: $0.name, address: $0.address, port: $0.port, username: $0.username)
            }
        )
    }

    /// A scan whose import adds to `engine` what it does not have yet, as the bridge does.
    static func scan(for engine: FakeEngine) -> FakeTransferScan {
        FakeTransferScan(parts: parts, code: code, preview: preview) { [weak engine] in
            guard let engine else { throw BridgeError.Internal(detail: "the engine is gone") }
            var identitiesImported: [Identity] = []
            var identitiesSkipped: [SkippedIdentity] = []
            if engine.identities.contains(where: { $0.fingerprint == identityFingerprint }) {
                identitiesSkipped.append(SkippedIdentity(label: "Lea", reason: .duplicate))
            } else {
                identitiesImported.append(try engine.addIdentity(name: "Lea", label: nil, fingerprint: identityFingerprint))
            }
            let existing = engine.savedServers()
            let duplicate: (ServerInput) -> Bool = { input in
                existing.contains { $0.name == input.name && $0.address == input.address && $0.port == input.port }
            }
            let added = engine.addServers(servers.filter { !duplicate($0) })
            return ImportReport(
                identitiesImported: identitiesImported,
                identitiesSkipped: identitiesSkipped,
                serversImported: added,
                serversSkipped: servers.filter(duplicate).map {
                    SkippedServer(name: $0.name, address: $0.address, reason: .duplicate)
                }
            )
        }
    }
}
#endif
