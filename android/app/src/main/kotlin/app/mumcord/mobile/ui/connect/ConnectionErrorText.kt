// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import androidx.annotation.StringRes
import app.mumcord.mobile.R
import app.mumcord.mobile.session.ConnectionError

/** The plain-words sentence shown for each error. */
@StringRes
fun ConnectionError.messageRes(): Int = when (this) {
    ConnectionError.NotConnected -> R.string.error_not_connected
    ConnectionError.ServerUnreachable -> R.string.error_server_unreachable
    ConnectionError.WrongPassword -> R.string.error_wrong_password
    ConnectionError.NameInUse -> R.string.error_name_in_use
    ConnectionError.CertificateRejected -> R.string.error_certificate_rejected
    ConnectionError.CertificateChanged -> R.string.error_certificate_changed
    ConnectionError.ServerTooOld -> R.string.error_server_too_old
    ConnectionError.Banned -> R.string.error_banned
    ConnectionError.NotFound -> R.string.error_not_found
    ConnectionError.PermissionDenied -> R.string.error_permission_denied
    ConnectionError.InvalidInput -> R.string.error_invalid_input
    ConnectionError.SecureStorageUnavailable -> R.string.error_secure_storage_unavailable
    ConnectionError.Io -> R.string.error_io
    ConnectionError.Internal -> R.string.error_internal
}
