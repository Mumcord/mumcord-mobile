// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Large hold-to-talk button (`.ptt`): round and 208 pt wide, or a 72 pt high bar over the full
/// width when `wide` (96 pt with larger text when also `tall`, as in the full voice view). `onPressedChange` reports finger down (true) and up (false); `active`
/// shows the talking state the caller derives from it.
///
/// Holding is not possible with VoiceOver or a switch, so the accessibility action toggles
/// instead: one activation starts talking, the next one stops. `stateLabel` says which state
/// it is in.
struct MumcordTalkButton: View {
    let label: String
    let active: Bool
    let stateLabel: String
    var wide = false
    var tall = false
    var icon = MumcordIcons.mic
    let onPressedChange: (Bool) -> Void
    @Environment(\.mumcordTheme) private var theme
    // Back to false also when the system cancels the touch (a call, a gesture of the system).
    @GestureState private var pressed = false

    var body: some View {
        let colors = theme.colors
        let content = active ? colors.onTalking : colors.text
        Group {
            if wide {
                HStack(spacing: tall ? MumcordSpace.s3 : MumcordSpace.s2) {
                    MumcordIcon(icon: icon, label: nil, tint: content, size: tall ? MumcordSize.iconLg : MumcordSize.icon)
                    LineText(label, style: tall ? TextToken.talkButton.with(size: 22) : .talkButton, color: content)
                }
            } else {
                VStack(spacing: MumcordSpace.s2) {
                    MumcordIcon(icon: icon, label: nil, tint: content, size: MumcordSize.iconXl)
                    LineText(label, style: .talkButton, color: content)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(active ? colors.talking : colors.bgElevated, in: Capsule())
        .padding(wide ? 3 : 5)
        .frame(maxWidth: wide ? .infinity : 208)
        .frame(height: wide ? (tall ? 96 : 72) : 208)
        .background(active ? AnyShapeStyle(colors.talking) : AnyShapeStyle(colors.accentGradient), in: Capsule())
        .background {
            if active {
                // Two soft rings around the button while talking: 24 pt at 10 % and 10 pt at 22 %.
                Capsule().fill(colors.talking.opacity(0.10)).padding(-24)
                Capsule().fill(colors.talking.opacity(0.22)).padding(-10)
            }
        }
        .tokenShadow(active ? nil : theme.shadows.glow, shape: Capsule())
        .contentShape(Capsule())
        .gesture(
            DragGesture(minimumDistance: 0).updating($pressed) { _, state, _ in
                state = true
            }
        )
        .onChange(of: pressed) { _, isPressed in
            onPressedChange(isPressed)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(stateLabel)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            onPressedChange(!active)
        }
    }
}
