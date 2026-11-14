// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// A person in the full voice view (`.vtile`): a 72 pt avatar with the name below. `talking`
/// draws the green ring and makes the name strong; `mutedLabel` puts the muted badge on the
/// avatar. VoiceOver reads the tile as one item: the name and, as its value, talking and muted.
struct MumcordVoiceTile: View {
    let name: String
    let initials: String
    let colorSlot: Int
    let talking: Bool
    let talkingLabel: String
    var mutedLabel: String?
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(spacing: MumcordSpace.s2) {
            MumcordAvatar(initials: initials, colorSlot: colorSlot, size: .tile)
                .overlay {
                    if talking {
                        // A 3 pt gap in the screen colour, then a 3 pt ring, both outside the avatar.
                        Circle().strokeBorder(colors.talking, lineWidth: 3).padding(-6)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if mutedLabel != nil {
                        MumcordIcon(icon: MumcordIcons.micOff, label: nil, tint: colors.localMuted, size: MumcordSize.iconSm)
                            .frame(width: 22, height: 22)
                            .background(colors.bgElevated, in: Circle())
                            .padding(2)
                            .background(colors.bgChat, in: Circle())
                            // The badge sits on the lower right edge of the avatar.
                            .offset(x: 4, y: 2)
                    }
                }
            LineText(name, style: talking ? .bodyStrong : .body, color: talking ? colors.text : colors.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
        .accessibilityValue([talking ? talkingLabel : nil, mutedLabel].compactMap { $0 }.joined(separator: ", "))
    }
}

/// A large round action of the full voice view with its name below (`.vaction`): a 64 pt
/// button and a label. `spokenLabel` is what VoiceOver reads for the button; the visible
/// `label` is not read a second time. Disable it with `.disabled(true)`.
struct MumcordVoiceAction: View {
    let icon: MumcordIconData
    let label: String
    let spokenLabel: String
    var style = MumcordIconButtonStyle.tonal
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            VStack(spacing: 6) {
                MumcordIcon(icon: icon, label: nil, tint: tint, size: MumcordSize.iconLg)
                    .frame(width: 64, height: 64)
                    .background(background, in: Circle())
                    .opacity(isEnabled ? 1 : 0.5)
                ParagraphText(label, style: .smallStrong, color: style == .danger ? colors.danger : colors.textSecondary)
                    .multilineTextAlignment(.center)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spokenLabel)
    }

    private var tint: Color {
        switch style {
        case .off: theme.colors.danger
        case .danger: theme.colors.onAccent
        default: theme.colors.text
        }
    }

    private var background: Color {
        switch style {
        case .off: theme.colors.danger.opacity(theme.opacities.offTint)
        case .danger: theme.colors.danger
        default: theme.colors.bgHover
        }
    }
}

/// A choice between two or three options in one pill (`.seg`): the options share the width and
/// the selected one is filled with the accent colour. `label` names the choice for VoiceOver.
struct MumcordSegmentedPill: View {
    let options: [String]
    let selectedIndex: Int
    let label: String
    let onSelect: (Int) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { index in
                let selected = index == selectedIndex
                Button {
                    onSelect(index)
                } label: {
                    ParagraphText(options[index], style: .smallStrong, color: selected ? colors.onAccent : colors.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, MumcordSpace.s2)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(selected ? colors.accent : Color.clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        // 1 pt of each side is the border.
        .padding(5)
        .background(colors.bgElevated, in: Capsule())
        .overlay { Capsule().strokeBorder(colors.border, lineWidth: 1) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(label)
    }
}

/// Level meter of the microphone: a track that fills from the start with the talking colour.
/// `level` runs from 0 to 1. `label` names the meter for VoiceOver; the moving level itself is
/// not announced.
struct MumcordLevelMeter: View {
    let level: Double
    let label: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let height: CGFloat = 8
        GeometryReader { geometry in
            let width = geometry.size.width * min(max(level, 0), 1)
            if width > 0 {
                Capsule().fill(colors.talking).frame(width: max(width, height))
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .background(colors.bgHover, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}
