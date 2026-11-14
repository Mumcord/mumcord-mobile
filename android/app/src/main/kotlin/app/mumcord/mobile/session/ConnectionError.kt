// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeException

/** Why connecting failed or a connection ended; one value per error the engine reports. */
enum class ConnectionError {
    NotConnected,
    ServerUnreachable,
    WrongPassword,
    NameInUse,
    CertificateRejected,
    CertificateChanged,
    ServerTooOld,
    InvalidInput,
    SecureStorageUnavailable,
    Io,
    Internal,
}

fun BridgeException.toConnectionError(): ConnectionError = when (this) {
    is BridgeException.NotConnected -> ConnectionError.NotConnected
    is BridgeException.ServerUnreachable -> ConnectionError.ServerUnreachable
    is BridgeException.WrongPassword -> ConnectionError.WrongPassword
    is BridgeException.NameInUse -> ConnectionError.NameInUse
    is BridgeException.CertificateRejected -> ConnectionError.CertificateRejected
    is BridgeException.CertificateChanged -> ConnectionError.CertificateChanged
    is BridgeException.ServerTooOld -> ConnectionError.ServerTooOld
    is BridgeException.InvalidInput -> ConnectionError.InvalidInput
    is BridgeException.SecureStorageUnavailable -> ConnectionError.SecureStorageUnavailable
    is BridgeException.Io -> ConnectionError.Io
    is BridgeException.Internal -> ConnectionError.Internal
}
