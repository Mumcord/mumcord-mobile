// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Removing a server is destructive (its saved password goes too), so it asks first.
struct RemoveServerSheet: View {
    let serverName: String
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.removeTitle, onDismiss: onDismiss) {
            Paragraph(Strings.removeText(serverName))
            MumcordButton(
                text: Strings.removeConfirm,
                style: .danger,
                icon: MumcordIcons.trash,
                fullWidth: true,
                action: onConfirm
            )
            .accessibilityIdentifier("server-remove-confirm")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: onDismiss)
        }
    }
}
