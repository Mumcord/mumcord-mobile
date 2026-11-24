// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Round button on a stream's dark stage (`.stage .iconbtn` of the full screen, `.pip__btn` of
/// the mini window): a white icon on a light veil, or on the live red for `danger` ("Stop
/// watching"). The stage is dark in both themes. `size` is the visible circle; the touch target
/// is never smaller than 44 pt.
struct MumcordStageButton: View {
    let icon: MumcordIconData
    let label: String
    var danger = false
    var size: CGFloat = 48
    var iconSize = MumcordSize.icon
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let target = max(size, MumcordSize.touchTargetIos)
        Button(action: action) {
            MumcordIcon(icon: icon, label: nil, tint: colors.mediaText, size: iconSize)
                .frame(width: size, height: size)
                .background(danger ? colors.live : colors.stageControl, in: Circle())
                .frame(width: target, height: target)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// A hint on the stage (`.hint`), such as "Rotate for landscape": an icon and a short text in a pill.
struct MumcordStageHint: View {
    let icon: MumcordIconData
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s2) {
            MumcordIcon(icon: icon, label: nil, tint: colors.stageTextSecondary, size: MumcordSize.iconMd)
            LineText(text, style: .small, color: colors.stageTextSecondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, MumcordSpace.s2)
        .background(colors.stageControl, in: Capsule())
    }
}
