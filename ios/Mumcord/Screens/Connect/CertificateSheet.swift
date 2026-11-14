// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Asks whether to trust the server's certificate: a calm question for a server seen for the
/// first time, a strong warning when a trusted server presents another certificate. It cannot be
/// dismissed by tapping outside: trusting or not is a security decision the user has to make on
/// purpose.
struct CertificateSheet: View {
    let prompt: CertificatePromptState
    let onTrust: () -> Void
    let onReject: () -> Void

    var body: some View {
        SheetOverlay(
            title: prompt.previousFingerprint == nil ? Strings.certificateUnknownTitle : Strings.certificateChangedTitle,
            onDismiss: nil
        ) {
            if let previous = prompt.previousFingerprint {
                MumcordBanner(
                    text: Strings.certificateChangedMessage,
                    icon: MumcordIcons.alert,
                    tone: .danger,
                    horizontalMargin: 0
                )
                Fingerprint(label: Strings.certificateChangedPreviousLabel, fingerprint: previous)
                Fingerprint(label: Strings.certificateChangedNewLabel, fingerprint: prompt.fingerprint)
                // Not connecting is the safe answer and therefore the prominent one.
                VStack(spacing: MumcordSpace.s2) {
                    MumcordButton(text: Strings.certificateReject, fullWidth: true, action: onReject)
                        .accessibilityIdentifier("certificate-reject")
                    MumcordButton(text: Strings.certificateChangedTrust, style: .danger, fullWidth: true, action: onTrust)
                        .accessibilityIdentifier("certificate-trust")
                }
            } else {
                MumcordBanner(
                    text: Strings.certificateUnknownMessage,
                    icon: MumcordIcons.shield,
                    horizontalMargin: 0
                )
                Fingerprint(label: Strings.certificateFingerprintLabel, fingerprint: prompt.fingerprint)
                VStack(spacing: MumcordSpace.s2) {
                    MumcordButton(text: Strings.certificateUnknownTrust, fullWidth: true, action: onTrust)
                        .accessibilityIdentifier("certificate-trust")
                    MumcordButton(text: Strings.certificateReject, style: .secondary, fullWidth: true, action: onReject)
                        .accessibilityIdentifier("certificate-reject")
                }
            }
        }
    }
}

/// A certificate fingerprint under its label, in a fixed-width face so that two of them can be
/// compared character by character.
private struct Fingerprint: View {
    let label: String
    let fingerprint: String
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        VStack(alignment: .leading, spacing: 6) {
            ParagraphText(label, style: .smallStrong, color: colors.textSecondary)
            Text(verbatim: fingerprint)
                .font(.system(size: TextToken.small.size * scale, design: .monospaced))
                .foregroundStyle(colors.text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(MumcordSpace.s3)
                .background(colors.bgHover, in: RoundedRectangle(cornerRadius: MumcordRadius.lg))
                .accessibilityIdentifier("certificate-fingerprint")
        }
    }
}
