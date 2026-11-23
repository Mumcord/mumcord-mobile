// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// One tab. `badge` is the unread count shown on the icon; `accessibilityLabel` replaces the
/// label for VoiceOver when the badge needs words ("Messages, 2 unread").
struct MumcordTab {
    let icon: MumcordIconData
    let label: String
    var badge: String?
    var accessibilityLabel: String?
}

/// Bottom tab bar (`.tabbar`) for the four main areas. Its background fills the area of the
/// home indicator.
struct MumcordTabBar: View {
    let tabs: [MumcordTab]
    let selectedIndex: Int
    let onSelect: (Int) -> Void
    @Environment(\.mumcordTheme) private var theme
    @Environment(\.mumcordBottomInset) private var bottomInset

    var body: some View {
        let colors = theme.colors
        HStack(spacing: 0) {
            ForEach(tabs.indices, id: \.self) { index in
                TabItem(tab: tabs[index], selected: index == selectedIndex) { onSelect(index) }
            }
        }
        // 1 pt of the top padding is the border line. The labels sit 8 pt into the area of the
        // home indicator, as in the mockups, but never closer than 6 pt to the edge.
        .padding(EdgeInsets(top: 7, leading: 8, bottom: max(6 - bottomInset, -8), trailing: 8))
        .overlay(alignment: .top) {
            Rectangle().fill(colors.border).frame(height: 1)
        }
        .background { colors.bgServers.ignoresSafeArea(edges: .bottom) }
    }
}

private struct TabItem: View {
    let tab: MumcordTab
    let selected: Bool
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            VStack(spacing: 3) {
                MumcordIcon(icon: tab.icon, label: nil, tint: selected ? colors.accent : colors.textMuted)
                    .frame(width: 56, height: 28)
                    .background(selected ? colors.accent.opacity(theme.opacities.tabActive) : .clear, in: Capsule())
                    .overlay(alignment: .topLeading) {
                        if let badge = tab.badge {
                            MumcordBadge(text: badge, ringColor: colors.bgServers)
                                .fixedSize()
                                .offset(x: 31, y: -5)
                        }
                    }
                LineText(tab.label, style: .tab, color: selected ? colors.text : colors.textMuted)
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.accessibilityLabel ?? tab.label)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}
