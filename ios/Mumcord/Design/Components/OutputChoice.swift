// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The chip in the header of the voice view that names the audio output and opens the choice
/// (`.vchip`): icon, name, chevron. The pill is drawn at the mockup's height inside a touch
/// target of the platform's size.
struct MumcordOutputChip: View {
    let text: String
    let icon: MumcordIconData
    /// What VoiceOver says instead of the bare name, such as "Audio output: Speaker".
    let label: String
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            HStack(spacing: 6) {
                MumcordIcon(icon: icon, label: nil, tint: colors.text, size: MumcordSize.iconSm)
                LineText(text, style: .smallStrong, color: colors.text)
                MumcordIcon(icon: MumcordIcons.chevronDown, label: nil, tint: colors.text, size: MumcordSize.iconSm)
            }
            .padding(.horizontal, MumcordSpace.s3)
            .frame(minHeight: MumcordSize.touchVisual)
            .background(colors.bgElevated, in: Capsule())
            .overlay { Capsule().strokeBorder(colors.borderStrong, lineWidth: 1) }
            .frame(minHeight: MumcordSize.touchTargetIos)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// One choice of a list in which exactly one is chosen (`.row.opt`): the chosen one is tinted
/// and gets a check mark. An `unavailable` choice is shown faded and cannot be picked.
/// `subtitle` is a second line, such as the kind of device or why it is unavailable.
struct MumcordOptionRow: View {
    let title: String
    let icon: MumcordIconData
    let selected: Bool
    var subtitle: String?
    var unavailable = false
    var divider = false
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            HStack(spacing: MumcordSpace.s3) {
                MumcordIcon(icon: icon, label: nil, tint: colors.textSecondary)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 0) {
                    LineText(title, style: .bodyStrong, color: colors.text)
                    if let subtitle {
                        LineText(subtitle, style: .small, color: colors.textMuted)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if selected {
                    MumcordIcon(icon: MumcordIcons.check, label: nil, tint: colors.accentText)
                }
            }
            .padding(.horizontal, MumcordSpace.s4)
            .padding(.vertical, MumcordSpace.s2)
            .frame(minHeight: 64)
            .background(selected ? colors.bgSelected : .clear)
            .overlay(alignment: .top) {
                if divider {
                    Rectangle().fill(colors.border).frame(height: 1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(unavailable)
        .opacity(unavailable ? 0.55 : 1)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A quiet remark under a list (`.note`): a small info icon and one or two lines of text.
struct MumcordNote: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        HStack(spacing: MumcordSpace.s2) {
            MumcordIcon(icon: MumcordIcons.info, label: nil, tint: theme.colors.textMuted, size: MumcordSize.iconSm)
            ParagraphText(text, style: .small, color: theme.colors.textMuted)
        }
        .padding(.horizontal, MumcordSpace.s1)
        .accessibilityElement(children: .combine)
    }
}
