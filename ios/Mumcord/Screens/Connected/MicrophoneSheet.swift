// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Says in one sentence why the microphone is needed, before the system asks for it. "Not now"
/// and tapping beside the sheet leave the user listening.
struct MicrophoneSheet: View {
    let onContinue: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        SheetOverlay(title: Strings.microphoneTitle, onDismiss: onDismiss) {
            MumcordBanner(text: Strings.microphoneText, horizontalMargin: 0)
            MumcordButton(text: Strings.microphoneContinue, fullWidth: true, action: onContinue)
                .accessibilityIdentifier("microphone-continue")
            MumcordButton(text: Strings.microphoneNotNow, style: .secondary, fullWidth: true, action: onDismiss)
                .accessibilityIdentifier("microphone-not-now")
        }
    }
}

/// The calm notice while the user listens without the microphone. Tapping it lets the system
/// ask, or opens the system settings once the system no longer asks (`blocked`).
struct MicrophoneBanner: View {
    let blocked: Bool
    var horizontalMargin: CGFloat = MumcordSpace.s4
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MumcordBanner(
                text: blocked ? Strings.microphoneDeniedSettings : Strings.microphoneDeniedAsk,
                title: Strings.microphoneDeniedTitle,
                icon: MumcordIcons.alert,
                tone: .warning,
                horizontalMargin: horizontalMargin
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(blocked ? Strings.microphoneOpenSettings : Strings.microphoneAllow)
        .accessibilityIdentifier("microphone-banner")
    }
}
