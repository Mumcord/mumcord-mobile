// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// What the scan screen says about the camera permission.
enum CameraNotice: Equatable {
    /// Say why the camera is needed, then let the system ask.
    case explain
    /// The user said "Not now"; the screen offers to let the system ask.
    case denied
    /// The system no longer asks: only the system settings help.
    case blocked
}

enum ScanNote: Equatable {
    /// A QR code of something else.
    case notATransfer
    /// Read with an error; the next look at it helps.
    case unreadable
    case newerVersion
    case expired
    case clockSkew
    /// The code was wrong three times.
    case rescanRequired
    case damaged
    case outOfMemory
    case failed
    /// The camera of the phone could not be started.
    case noCamera
}

enum ScanStep: Equatable {
    /// Collecting the QR codes; `total` is 0 before the first one.
    case scanning(have: Int, total: Int)
    /// All QR codes are there: the user types the code. `typed` is the only place the code
    /// ever is, and it is emptied as soon as it has been checked.
    case code(CodeEntry)
    /// The typed code is being checked, which is slow on purpose.
    case checking(digits: Int, attemptsLeft: Int)
    /// The code was right: what would arrive, waiting for the user's "Import".
    case preview(TransferContents)
    case done(ImportSummary)

    static let waiting = ScanStep.scanning(have: 0, total: 0)
}

struct CodeEntry: Equatable {
    let digits: Int
    var typed = ""
    let attemptsLeft: Int
    /// The code typed before was wrong.
    var wrong = false
}

struct TransferContents: Equatable {
    let identities: [String]
    /// Nil when the server list is not part of the transfer.
    let servers: [String]?
    var importing = false
    var error: ConnectionError?
}

struct ImportSummary: Equatable {
    let identitiesImported: Int
    let serversImported: Int
    let skipped: [SkippedEntry]
}

struct SkippedEntry: Equatable {
    let name: String
    let alreadyThere: Bool
}

struct ScanState: Equatable {
    var step = ScanStep.waiting
    /// Nil while the camera may be used.
    var camera: CameraNotice?
    var note: ScanNote?

    /// The camera only runs while there is something to scan.
    var scanning: Bool {
        if case .scanning = step { return camera == nil }
        return false
    }
}

/// Where the bridge's scan stands after a step.
struct ScanStanding: Equatable {
    let progress: TransferProgress?
    let codeDigits: Int
    let attemptsLeft: Int
}

extension ScanState {
    func afterPart(_ standing: ScanStanding) -> ScanState {
        var next = self
        next.note = nil
        guard let progress = standing.progress else {
            next.step = .waiting
            return next
        }
        let have = Int(progress.have)
        let total = Int(progress.total)
        if total > 0 && have >= total {
            next.step = .code(CodeEntry(digits: standing.codeDigits, attemptsLeft: standing.attemptsLeft))
        } else {
            next.step = .scanning(have: have, total: total)
        }
        return next
    }

    func afterPartRefused(_ error: TransferError) -> ScanState {
        var next = self
        switch error {
        case .NotATransfer: next.note = .notATransfer
        case .BadChecksum: next.note = .unreadable
        case .UnsupportedVersion: next.note = .newerVersion
        default: return startedOver(error)
        }
        return next
    }

    func afterCodeRefused(_ error: TransferError, _ standing: ScanStanding) -> ScanState {
        var next = self
        next.note = nil
        switch error {
        case .WrongCode(let attemptsLeft):
            next.step = .code(CodeEntry(digits: standing.codeDigits, attemptsLeft: Int(attemptsLeft), wrong: true))
        case .InvalidCode(let digits):
            next.step = .code(CodeEntry(digits: Int(digits), attemptsLeft: standing.attemptsLeft))
        case .Incomplete:
            return afterPart(standing)
        case .OutOfMemory:
            next.step = .code(CodeEntry(digits: standing.codeDigits, attemptsLeft: standing.attemptsLeft))
            next.note = .outOfMemory
        case .Failed:
            next.step = .code(CodeEntry(digits: standing.codeDigits, attemptsLeft: standing.attemptsLeft))
            next.note = .failed
        default:
            return startedOver(error)
        }
        return next
    }

    private func startedOver(_ error: TransferError) -> ScanState {
        var next = self
        next.step = .waiting
        switch error {
        case .Expired: next.note = .expired
        case .ClockSkew: next.note = .clockSkew
        case .RescanRequired: next.note = .rescanRequired
        case .Damaged: next.note = .damaged
        case .UnsupportedVersion: next.note = .newerVersion
        default: next.note = .failed
        }
        return next
    }
}

extension TransferPreview {
    var step: ScanStep {
        .preview(TransferContents(identities: identities.map(\.label), servers: servers?.map(\.name)))
    }
}

extension ImportReport {
    var step: ScanStep {
        .done(
            ImportSummary(
                identitiesImported: identitiesImported.count,
                serversImported: serversImported.count,
                skipped: identitiesSkipped.map { SkippedEntry(name: $0.label, alreadyThere: $0.reason == .duplicate) }
                    + serversSkipped.map { SkippedEntry(name: $0.name, alreadyThere: $0.reason == .duplicate) }
            )
        )
    }
}

/// What the code field keeps of the typed text: digits only, no more than the code has.
func codeInput(_ text: String, digits: Int) -> String {
    String(text.filter { ("0"..."9").contains($0) }.prefix(digits))
}

/// The share of the codes collected, rounded, for "43%".
func scanPercent(have: Int, total: Int) -> Int {
    total == 0 ? 0 : (have * 100 + total / 2) / total
}
