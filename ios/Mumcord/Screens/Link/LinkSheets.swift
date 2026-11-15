// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Shows where an opened `mumble://` link leads (server, channel, user name) and connects only
/// when the user confirms. Everything in a link comes from outside and is drawn as plain text.
struct LinkConfirmSheet: View {
    let state: LinkConfirmState
    let onUsernameChange: (String) -> Void
    let onConnect: () -> Void
    let onDismiss: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.linkTitle, onDismiss: onDismiss) {
            Paragraph(Strings.linkText)
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordListRow(title: Strings.linkServer, subtitle: serverText, icon: MumcordIcons.globe)
                if !state.link.channelPath.isEmpty {
                    MumcordListRow(
                        title: Strings.linkChannel,
                        subtitle: state.link.channelPath.joined(separator: " / "),
                        icon: MumcordIcons.hash,
                        divider: true
                    )
                }
                if !state.asksForUsername {
                    MumcordListRow(
                        title: Strings.linkUsername,
                        subtitle: state.username,
                        icon: MumcordIcons.user,
                        divider: true
                    )
                }
            }
            if state.asksForUsername {
                MumcordTextField(
                    value: state.typedUsername,
                    onValueChange: onUsernameChange,
                    label: Strings.linkUsername,
                    hint: Strings.formUsernameHint
                )
            }
            if state.willAddServer {
                MumcordBanner(text: Strings.linkWillAdd, horizontalMargin: 0)
            }
            if let error = state.error {
                MumcordBanner(
                    text: error.message,
                    icon: MumcordIcons.alert,
                    tone: .danger,
                    horizontalMargin: 0
                )
            }
            MumcordButton(text: Strings.serversConnect, fullWidth: true, action: onConnect)
                .disabled(!state.canConnect)
                .accessibilityIdentifier("link-connect")
            MumcordButton(text: Strings.actionCancel, style: .secondary, fullWidth: true, action: onDismiss)
        }
    }

    /// The address of the link, with the name of the saved server it belongs to before it.
    private var serverText: String {
        let address = displayAddress(state.link.address, port: state.link.port)
        if let savedServer = state.savedServer {
            return "\(savedServer.name) · \(address)"
        }
        return address
    }
}

/// An opened link that is not a usable `mumble://` link.
struct InvalidLinkSheet: View {
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.linkInvalidTitle, onDismiss: onDismiss) {
            Paragraph(Strings.linkInvalidText)
            MumcordButton(text: Strings.actionClose, style: .secondary, fullWidth: true, action: onDismiss)
        }
    }
}
