// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The engine's question before the first direct connection. It is about the user's privacy, so
/// it says plainly what the other person sees. Putting it away answers no, the choice that
/// shows less.
struct DirectConnectionsSheet: View {
    let onAnswer: (Bool) -> Void

    var body: some View {
        SheetOverlay(title: Strings.directConnectionsTitle, onDismiss: { onAnswer(false) }) {
            MumcordBanner(text: Strings.directConnectionsBody, horizontalMargin: 0)
            MumcordButton(text: Strings.directConnectionsYes, fullWidth: true, action: { onAnswer(true) })
                .accessibilityIdentifier("direct-connections-yes")
            MumcordButton(text: Strings.directConnectionsNo, style: .secondary, fullWidth: true, action: { onAnswer(false) })
                .accessibilityIdentifier("direct-connections-no")
        }
    }
}
