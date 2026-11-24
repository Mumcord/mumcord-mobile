// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The add and edit server form. Kept as text so a half-typed port is not lost. A saved password
/// is never read back: the form only knows that there is one (`hasSavedPassword`).
struct ServerFormState: Equatable {
    /// The server being edited, or nil for a new one.
    var serverId: Int64?
    var name = ""
    var address = ""
    var port = String(defaultPort)
    var username = ""
    var password = ""
    var hasSavedPassword = false
    var removeSavedPassword = false
    /// Kept as it is: choosing the identity arrives with the identity screens.
    var identityId: Int64?
    /// The theme picked for this server (Spec 02 FR-2.17); nil shows it in the app theme.
    var themeId: String?
    /// The theme the server had when the form was opened; a different `themeId` is saved.
    var savedThemeId: String?
    /// The choice of the server's theme is open.
    var themeSheet = false
    /// Errors are shown once the user has tried to save.
    var showErrors = false
    var saving = false
    var saveError: ConnectionError?

    var isEdit: Bool { serverId != nil }

    /// The port as a number, or nil while the text is not a port between 1 and 65535.
    var portNumber: UInt16? {
        guard let number = UInt16(port.trimmed), number > 0 else { return nil }
        return number
    }

    var addressMissing: Bool { address.isBlank }

    var usernameMissing: Bool { username.isBlank }

    var themeChanged: Bool { themeId != savedThemeId }

    var isValid: Bool { !addressMissing && !usernameMissing && portNumber != nil }

    /// What to save, or nil while the form is not valid.
    func toInput() -> ServerInput? {
        guard isValid, let portNumber else { return nil }
        let passwordInput: PasswordInput
        if !password.isEmpty {
            passwordInput = .set(value: password)
        } else if removeSavedPassword {
            passwordInput = .clear
        } else {
            passwordInput = .keep
        }
        return ServerInput(
            name: name.trimmed,
            address: address.trimmed,
            port: portNumber,
            username: username.trimmed,
            password: passwordInput,
            identityId: identityId
        )
    }

    static func edit(_ server: SavedServer, themeId: String? = nil) -> ServerFormState {
        ServerFormState(
            serverId: server.id,
            name: server.name,
            address: server.address,
            port: String(server.port),
            username: server.username,
            hasSavedPassword: server.hasPassword,
            identityId: server.identityId,
            themeId: themeId,
            savedThemeId: themeId
        )
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    var isBlank: Bool { trimmed.isEmpty }
}
