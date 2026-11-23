// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// One icon of the set in the given colour. Pass a `label` when the icon carries meaning on its
/// own, and nil when the text next to it already says the same.
struct MumcordIcon: View {
    let icon: MumcordIconData
    let label: String?
    let tint: Color
    var size: CGFloat = MumcordSize.icon

    var body: some View {
        let scale = size / MumcordIcons.viewBox
        let drawing = icon.path
            .applying(CGAffineTransform(scaleX: scale, y: scale))
            .stroke(tint, style: StrokeStyle(lineWidth: MumcordIcons.strokeWidth * scale, lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
        if let label {
            drawing
                .accessibilityElement()
                .accessibilityLabel(label)
                .accessibilityAddTraits(.isImage)
        } else {
            drawing.accessibilityHidden(true)
        }
    }
}

/// Single-line text that ends with an ellipsis when it does not fit, as everywhere in the
/// mockups. The line box has the height of the style's line height with the text centred in
/// it, like CSS, so that row heights come out the same as in the mockups.
struct LineText: View {
    private let text: String
    private let style: TextToken
    private let color: Color
    private let tabularNumbers: Bool
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    init(_ text: String, style: TextToken, color: Color, tabularNumbers: Bool = false) {
        self.text = text
        self.style = style
        self.color = color
        self.tabularNumbers = tabularNumbers
    }

    var body: some View {
        let font = Font(MumcordFonts.font(style, scale: scale))
        Text(text)
            .font(tabularNumbers ? font.monospacedDigit() : font)
            .tracking(style.letterSpacing * style.size * scale)
            .foregroundStyle(color)
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(height: style.lineHeight * style.size * scale)
    }
}

/// Text over several lines in a text style, with the line height of the style.
struct ParagraphText: View {
    private let text: AttributedString
    private let style: TextToken
    private let color: Color
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    init(_ text: AttributedString, style: TextToken, color: Color) {
        self.text = text
        self.style = style
        self.color = color
    }

    init(_ text: String, style: TextToken, color: Color) {
        self.init(AttributedString(text), style: style, color: color)
    }

    var body: some View {
        let font = MumcordFonts.font(style, scale: scale)
        // What the line height of the style adds to the height the font draws a line with.
        let extra = style.lineHeight * style.size * scale - MumcordFonts.naturalLineHeight(font)
        Text(text)
            .font(Font(font))
            .tracking(style.letterSpacing * style.size * scale)
            .foregroundStyle(color)
            .lineSpacing(max(extra, 0))
            .padding(.vertical, extra / 2)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Small round state dot: green for online, grey for `off`.
struct MumcordDot: View {
    var off = false
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        Circle()
            .fill(off ? theme.colors.borderStrong : theme.colors.success)
            .frame(width: 8, height: 8)
            .accessibilityHidden(true)
    }
}

/// Count badge (unread messages). With a `ringColor` it gets a 2 pt ring in the colour of the
/// bar it sits on, so it stands off the icon below it.
struct MumcordBadge: View {
    let text: String
    var ringColor: Color?
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        LineText(text, style: .badge, color: theme.colors.onAccent)
            .padding(.horizontal, 6)
            .frame(minWidth: 20, minHeight: 20)
            .background(theme.colors.accentStop2, in: Capsule())
            .background {
                if let ringColor {
                    Capsule().fill(ringColor).padding(-2)
                }
            }
    }
}

extension MumcordColors {
    /// The brand gradient (peach to pink to violet at 135°) of the server tile and the talk button.
    var accentGradient: LinearGradient {
        LinearGradient(
            stops: [
                Gradient.Stop(color: accentStop1, location: 0),
                Gradient.Stop(color: accentStop2, location: 0.5),
                Gradient.Stop(color: accentStop3, location: 1),
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    /// The avatar background for a colour slot from 1 to 8; other numbers wrap around.
    func avatar(_ slot: Int) -> Color {
        let all = [avatar1, avatar2, avatar3, avatar4, avatar5, avatar6, avatar7, avatar8]
        return all[((slot - 1) % all.count + all.count) % all.count]
    }
}

/// Square tile with initials for a server. `brand` uses the brand gradient.
struct MumcordTile: View {
    let initials: String
    var brand = false
    var small = false
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let side: CGFloat = small ? 40 : 48
        let shape = RoundedRectangle(cornerRadius: small ? MumcordRadius.tileSmall : MumcordRadius.card)
        LineText(initials, style: small ? .tileSmall : .tile, color: brand ? colors.onAccent : colors.text)
            .frame(width: side, height: side)
            .background(brand ? AnyShapeStyle(colors.accentGradient) : AnyShapeStyle(colors.bgHover), in: shape)
            .clipShape(shape)
            .accessibilityHidden(true)
    }
}
