// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Switch (`.toggle`). The track is 52 x 32 pt as in the mockups; the area that reacts to touch
/// is 44 pt high. `label` names the setting for VoiceOver, which gets a standard switch.
struct MumcordToggle: View {
    let checked: Bool
    let label: String
    let onCheckedChange: (Bool) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        Button {
            onCheckedChange(!checked)
        } label: {
            Capsule()
                .fill(checked ? colors.accent : colors.bgHover)
                .overlay { Capsule().strokeBorder(checked ? colors.accent : colors.borderStrong, lineWidth: 2) }
                .overlay(alignment: .leading) {
                    Circle()
                        .fill(checked ? colors.onAccent : colors.borderStrong)
                        .frame(width: 20, height: 20)
                        // 2 pt border plus the 4 pt (off) or 24 pt (on) of the mockup.
                        .offset(x: checked ? 26 : 6)
                }
                .frame(width: 52, height: 32)
                .contentShape(Rectangle().inset(by: -6))
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            Toggle(label, isOn: Binding(get: { checked }, set: onCheckedChange))
        }
    }
}

/// Slider (`.slider`) for a `value` from 0 to 1, such as a volume. `label` names what it
/// changes. VoiceOver gets a standard slider and changes the value in steps.
struct MumcordSlider: View {
    let value: Double
    let label: String
    let onValueChange: (Double) -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let fraction = CGFloat(min(max(value, 0), 1))
        let thumbShadow = ShadowToken(y: 2, blur: 6, spread: 0, color: colors.sliderThumbShadow)
        GeometryReader { proxy in
            let width = proxy.size.width
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(colors.bgHover)
                    // The filled part ends square under the thumb, like the CSS gradient.
                    .overlay(alignment: .leading) {
                        Rectangle().fill(colors.accent).frame(width: width * fraction)
                    }
                    .clipShape(Capsule())
                    .overlay { Capsule().strokeBorder(colors.border, lineWidth: 1) }
                    .frame(height: 6)
                Circle()
                    .fill(colors.text)
                    .frame(width: 26, height: 26)
                    .tokenShadow(thumbShadow, shape: Circle())
                    // Centred on the value, so it hangs over the ends of the track at 0 and 1.
                    .offset(x: width * fraction - 13)
            }
            .frame(width: width, height: proxy.size.height, alignment: .leading)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { drag in
                    guard width > 0 else { return }
                    onValueChange(Double(min(max(drag.location.x / width, 0), 1)))
                }
            )
        }
        .frame(minWidth: 80, maxWidth: .infinity)
        .frame(height: MumcordSize.touchVisual)
        .accessibilityRepresentation {
            Slider(value: Binding(get: { Double(fraction) }, set: onValueChange), in: 0...1) {
                Text(label)
            }
        }
    }
}

/// Chip (`.chip`) for a filter or a choice; `selected` fills it with the accent colour.
/// `expand` lets it take the width it is offered (segmented control).
struct MumcordChip: View {
    let text: String
    let selected: Bool
    var icon: MumcordIconData?
    var expand = false
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let content = selected ? colors.onAccent : colors.textSecondary
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    MumcordIcon(icon: icon, label: nil, tint: content, size: MumcordSize.iconSm)
                }
                LineText(text, style: .smallStrong, color: content)
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: expand ? .infinity : nil)
            .frame(height: 36)
            .background(selected ? colors.accent : .clear, in: Capsule())
            .overlay { Capsule().strokeBorder(selected ? colors.accent : colors.borderStrong, lineWidth: 1) }
            // 36 pt high as in the mockups; 4 pt above and below react to touch as well.
            .contentShape(Rectangle().inset(by: -4))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Row of chips (`.chips`) that scrolls sideways when the chips do not fit.
struct MumcordChipRow: View {
    let options: [String]
    let selectedIndex: Int
    let onSelect: (Int) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MumcordSpace.s2) {
                ForEach(options.indices, id: \.self) { index in
                    MumcordChip(text: options[index], selected: index == selectedIndex) { onSelect(index) }
                }
            }
            .padding(.horizontal, MumcordSpace.s4)
            .padding(.vertical, MumcordSpace.s1)
        }
    }
}

/// Segmented control: a choice between a few options that share the width equally. The mockups
/// draw such choices with chips, so this is a row of equally wide chips.
struct MumcordSegmentedControl: View {
    let options: [String]
    let selectedIndex: Int
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: MumcordSpace.s2) {
            ForEach(options.indices, id: \.self) { index in
                MumcordChip(text: options[index], selected: index == selectedIndex, expand: true) { onSelect(index) }
            }
        }
    }
}
