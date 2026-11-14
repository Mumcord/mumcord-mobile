// SPDX-License-Identifier: AGPL-3.0-only

extension ConnectionError {
    /// The plain-words sentence shown for each error.
    var message: String {
        switch self {
        case .notConnected: Strings.errorNotConnected
        case .serverUnreachable: Strings.errorServerUnreachable
        case .wrongPassword: Strings.errorWrongPassword
        case .nameInUse: Strings.errorNameInUse
        case .certificateRejected: Strings.errorCertificateRejected
        case .certificateChanged: Strings.errorCertificateChanged
        case .serverTooOld: Strings.errorServerTooOld
        case .banned: Strings.errorBanned
        case .notFound: Strings.errorNotFound
        case .permissionDenied: Strings.errorPermissionDenied
        case .invalidInput: Strings.errorInvalidInput
        case .secureStorageUnavailable: Strings.errorSecureStorageUnavailable
        case .io: Strings.errorIo
        case .internalError: Strings.errorInternal
        }
    }
}
