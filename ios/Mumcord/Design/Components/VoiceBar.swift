// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// What VoiceOver reads for the parts of the voice bar; the caller supplies translated text.
struct MumcordVoiceBarLabels {
    let open: String
    let talking: String
    let notTalking: String
    let mute: String
    let unmute: String
    let deafen: String
    let undeafen: String
    let leave: String
}

/// Slim voice bar above the tabs (`.voicebar`): talk indicator, channel name with a status
/// line, and the mute, deafen and leave buttons. Tapping the text opens the full voice screen.
struct MumcordVoiceBar: View {
    let channelName: String
    let status: String
    let talking: Bool
    let muted: Bool
    let deafened: Bool
    let labels: MumcordVoiceBarLabels
    let onOpen: () -> Void
    let onToggleMute: () -> Void
    let onToggleDeafen: () -> Void
    let onLeave: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.bar)
        HStack(spacing: MumcordSpace.s2) {
            Button(action: onOpen) {
                HStack(spacing: MumcordSpace.s2) {
                    talkIndicator
                    VStack(alignment: .leading, spacing: 0) {
                        LineText(channelName, style: .bodyTight, color: colors.text)
                        LineText(status, style: .captionTight, color: colors.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(talking ? labels.talking : labels.notTalking)
            .accessibilityHint(labels.open)

            HStack(spacing: 4) {
                MumcordIconButton(
                    icon: muted ? MumcordIcons.micOff : MumcordIcons.mic,
                    label: muted ? labels.unmute : labels.mute,
                    style: muted ? .off : .tonal,
                    action: onToggleMute
                )
                MumcordIconButton(
                    icon: deafened ? MumcordIcons.headphonesOff : MumcordIcons.headphones,
                    label: deafened ? labels.undeafen : labels.deafen,
                    style: deafened ? .off : .tonal,
                    action: onToggleDeafen
                )
                MumcordIconButton(icon: MumcordIcons.phoneOff, label: labels.leave, style: .danger, action: onLeave)
            }
        }
        // 1 pt of each side is the border.
        .padding(EdgeInsets(top: 7, leading: 13, bottom: 7, trailing: 7))
        .frame(minHeight: 56)
        .background(colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .tokenShadow(theme.shadows.raised, shape: shape)
        .padding(EdgeInsets(top: 0, leading: 8, bottom: 8, trailing: 8))
    }

    private var talkIndicator: some View {
        let colors = theme.colors
        return MumcordIcon(
            icon: MumcordIcons.mic,
            label: nil,
            tint: talking ? colors.onTalking : colors.textMuted,
            size: MumcordSize.iconMd
        )
        .frame(width: 32, height: 32)
        .background(talking ? colors.talking : colors.bgHover, in: Circle())
        .tokenShadow(talking ? theme.shadows.talking : nil, shape: Circle())
    }
}
