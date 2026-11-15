// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Says in one sentence why a server on the local network needs access to it, before the
/// connection makes iOS ask. "Not now" and tapping beside the sheet leave everything as it was.
struct LocalNetworkSheet: View {
    let serverName: String
    let onContinue: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.localNetworkTitle, onDismiss: onDismiss) {
            MumcordBanner(text: Strings.localNetworkText(serverName), horizontalMargin: 0)
            MumcordButton(text: Strings.localNetworkContinue, fullWidth: true, action: onContinue)
                .accessibilityIdentifier("local-network-continue")
            MumcordButton(text: Strings.localNetworkNotNow, style: .secondary, fullWidth: true, action: onDismiss)
        }
    }
}
