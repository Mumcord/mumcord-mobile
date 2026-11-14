// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// One server at a time: asks before the current connection is ended for another server.
struct ConfirmSwitchSheet: View {
    let currentName: String
    let targetName: String
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.switchTitle, onDismiss: onDismiss) {
            Paragraph(Strings.switchText(currentName, targetName))
            MumcordButton(text: Strings.switchConfirm, fullWidth: true, action: onConfirm)
                .accessibilityIdentifier("switch-confirm")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: onDismiss)
        }
    }
}

/// After a wrong or missing server password: type it and connect again.
struct PasswordSheet: View {
    let serverName: String
    let password: String
    let onPasswordChange: (String) -> Void
    let onConnect: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.passwordTitle, onDismiss: onDismiss) {
            Paragraph(Strings.passwordText(serverName))
            MumcordTextField(
                value: password,
                onValueChange: onPasswordChange,
                label: Strings.passwordLabel,
                password: true,
                submitLabel: .go,
                onSubmit: onConnect
            )
            MumcordButton(text: Strings.serversConnect, fullWidth: true, action: onConnect)
                .disabled(password.isEmpty)
                .accessibilityIdentifier("password-connect")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: onDismiss)
        }
    }
}
