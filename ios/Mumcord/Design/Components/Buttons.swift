// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

private let disabledOpacity = 0.5

enum MumcordButtonStyle {
    case primary
    case secondary
    case danger
    case ghost
}

/// Text button (`.btn`): primary for the main action of a screen, secondary with an outline,
/// danger for destructive actions, ghost for quiet text actions. `fullWidth` makes it as wide
/// as its container, `minHeight` taller than the standard 48 pt. Disable it with `.disabled(true)`.
struct MumcordButton: View {
    let text: String
    var style = MumcordButtonStyle.primary
    var small = false
    var icon: MumcordIconData?
    var fullWidth = false
    var minHeight: CGFloat?
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            HStack(spacing: MumcordSpace.s2) {
                if let icon {
                    MumcordIcon(icon: icon, label: nil, tint: content)
                }
                LineText(text, style: small ? .smallStrong : .bodyStrong, color: content)
            }
            .padding(.horizontal, horizontalPadding)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: minHeight ?? (small ? MumcordSize.touchVisual : 48))
            .background(background, in: Capsule())
            .overlay {
                if style == .secondary {
                    Capsule().strokeBorder(colors.borderStrong, lineWidth: 1)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .tokenShadow(style == .primary && isEnabled ? theme.shadows.primaryGlow : nil, shape: Capsule())
        .opacity(isEnabled ? 1 : disabledOpacity)
    }

    private var background: Color {
        switch style {
        case .primary: theme.colors.primaryBg
        case .danger: theme.colors.danger
        case .secondary, .ghost: .clear
        }
    }

    private var content: Color {
        switch style {
        case .primary: theme.colors.primaryText
        case .danger: theme.colors.onAccent
        case .secondary: theme.colors.text
        case .ghost: theme.colors.accentText
        }
    }

    private var horizontalPadding: CGFloat {
        if style == .ghost { return MumcordSpace.s3 }
        return small ? MumcordSpace.s4 : MumcordSpace.s5
    }
}

/// Looks of the round icon button (`.iconbtn`): plain without a background, tonal on a soft
/// background, outline with a ring, on for a switched-on state, off for a switched-off state
/// (muted, deafened), danger for leaving or ending, accent for the main action (send).
enum MumcordIconButtonStyle: CaseIterable {
    case plain
    case tonal
    case outline
    case on
    case off
    case danger
    case accent
}

/// Round icon button, 44 pt wide as in the mockups (56 pt when `large`), which is also the
/// smallest touch target of iOS. `label` is what VoiceOver reads and must name the action.
struct MumcordIconButton: View {
    let icon: MumcordIconData
    let label: String
    var style = MumcordIconButtonStyle.plain
    var large = false
    var iconSize = MumcordSize.icon
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let diameter: CGFloat = large ? 56 : MumcordSize.touchVisual
        Button(action: action) {
            MumcordIcon(icon: icon, label: nil, tint: tint, size: iconSize)
                .frame(width: diameter, height: diameter)
                .background(background, in: Circle())
                .overlay {
                    if style == .outline {
                        Circle().strokeBorder(theme.colors.borderStrong, lineWidth: 1)
                    }
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .opacity(isEnabled ? 1 : disabledOpacity)
    }

    private var background: Color {
        let colors = theme.colors
        switch style {
        case .plain, .outline: return .clear
        case .tonal: return colors.bgHover
        case .on: return colors.accent
        case .off: return colors.danger.opacity(theme.opacities.offTint)
        case .danger: return colors.danger
        case .accent: return colors.primaryBg
        }
    }

    private var tint: Color {
        let colors = theme.colors
        switch style {
        case .plain: return colors.textSecondary
        case .tonal, .outline: return colors.text
        case .on, .danger: return colors.onAccent
        case .off: return colors.danger
        case .accent: return colors.primaryText
        }
    }
}

/// The floating main action of a screen (`.fab`), such as "Add server". The screen places it in
/// its bottom trailing corner. `label` is what VoiceOver reads and must name the action.
struct MumcordFab: View {
    let icon: MumcordIconData
    let label: String
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.sheet)
        Button(action: action) {
            MumcordIcon(icon: icon, label: nil, tint: theme.colors.primaryText, size: MumcordSize.iconLg)
                .frame(width: 60, height: 60)
                .background(theme.colors.primaryBg, in: shape)
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .tokenShadow(theme.shadows.primaryGlow, shape: shape)
        .accessibilityLabel(label)
    }
}
