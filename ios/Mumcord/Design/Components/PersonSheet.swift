// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Head of the person sheet (`.phead`): large avatar, name, the channel the person is in and
/// the state icons at the end.
struct MumcordPersonHeader: View {
    let name: String
    let initials: String
    let colorSlot: Int
    let channelName: String
    var status: [MumcordPersonStatus] = []
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s3) {
            MumcordAvatar(initials: initials, colorSlot: colorSlot, size: .sheet)
            VStack(alignment: .leading, spacing: 0) {
                LineText(name, style: .title, color: colors.text)
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: 6) {
                    MumcordIcon(icon: MumcordIcons.hash, label: nil, tint: colors.textMuted, size: MumcordSize.iconSm)
                    LineText(channelName, style: .small, color: colors.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: MumcordSpace.s2) {
                ForEach(status.indices, id: \.self) { index in
                    MumcordIcon(
                        icon: status[index].icon,
                        label: status[index].label,
                        tint: colors.localMuted,
                        size: MumcordSize.iconMd
                    )
                }
            }
        }
        .padding(.vertical, MumcordSpace.s1)
        // The head is a group of its own, so an identifier names it and not each text in it.
        .accessibilityElement(children: .contain)
    }
}

/// A list row with a slider under its title (`.row.vol`), such as "Volume for you" with the
/// current value as `valueText` at the end. `value` goes from 0 to 1.
struct MumcordSliderRow: View {
    let title: String
    let valueText: String
    let value: Double
    let icon: MumcordIconData
    let onValueChange: (Double) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(spacing: 0) {
            HStack(spacing: MumcordSpace.s3) {
                MumcordIcon(icon: icon, label: nil, tint: colors.textSecondary)
                    .frame(width: 24)
                LineText(title, style: .bodyStrong, color: colors.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                MumcordListRowValue(text: valueText)
                    .layoutPriority(1)
            }
            .padding(.top, MumcordSpace.s2)
            MumcordSlider(value: value, label: title, onValueChange: onValueChange)
        }
        .padding(.horizontal, MumcordSpace.s4)
        .padding(.top, MumcordSpace.s2)
    }
}

/// A calm, informative line for something the app cannot show, such as "Jonas shared a file ·
/// available in the desktop app". It is not a control.
struct MumcordHintLine: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s2) {
            MumcordIcon(icon: MumcordIcons.info, label: nil, tint: colors.textMuted, size: MumcordSize.iconSm)
            LineText(text, style: .small, color: colors.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, MumcordSpace.s4)
        .padding(.vertical, MumcordSpace.s1)
    }
}
