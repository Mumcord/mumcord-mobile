// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// Why connecting failed or a connection ended; one value per error the engine reports.
enum ConnectionError: CaseIterable, Equatable {
    case notConnected
    case serverUnreachable
    case wrongPassword
    case nameInUse
    case certificateRejected
    case certificateChanged
    case serverTooOld
    case banned
    case notFound
    case permissionDenied
    case invalidInput
    case secureStorageUnavailable
    case io
    case internalError
}

extension BridgeError {
    var connectionError: ConnectionError {
        switch self {
        case .NotConnected: .notConnected
        case .ServerUnreachable: .serverUnreachable
        case .WrongPassword: .wrongPassword
        case .NameInUse: .nameInUse
        case .CertificateRejected: .certificateRejected
        case .CertificateChanged: .certificateChanged
        case .ServerTooOld: .serverTooOld
        case .Banned: .banned
        case .NotFound: .notFound
        case .PermissionDenied: .permissionDenied
        case .InvalidInput: .invalidInput
        case .SecureStorageUnavailable: .secureStorageUnavailable
        case .Io: .io
        case .Internal: .internalError
        }
    }

    /// The engine's or the server's own words about the failure; untrusted text.
    var detail: String {
        switch self {
        case .NotConnected(let detail), .ServerUnreachable(let detail), .WrongPassword(let detail),
             .NameInUse(let detail), .CertificateRejected(let detail), .CertificateChanged(let detail),
             .ServerTooOld(let detail), .Banned(let detail), .NotFound(let detail),
             .PermissionDenied(let detail), .InvalidInput(let detail),
             .SecureStorageUnavailable(let detail), .Io(let detail), .Internal(let detail):
            detail
        }
    }
}
