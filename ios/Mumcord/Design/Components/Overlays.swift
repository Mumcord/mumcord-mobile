// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The frame of a bottom sheet (`.sheet`): rounded top, grip, optional `title`, and the content
/// in a column. Showing and hiding it, and the dimmed backdrop behind it
/// (`theme.colors.backdrop`), belong to the screen that uses it.
struct MumcordSheetFrame<Content: View>: View {
    private let title: String?
    private let content: Content
    @Environment(\.mumcordTheme) private var theme

    init(title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        let colors = theme.colors
        let shape = UnevenRoundedRectangle(topLeadingRadius: MumcordRadius.sheet, topTrailingRadius: MumcordRadius.sheet)
        VStack(alignment: .leading, spacing: MumcordSpace.s3) {
            Capsule()
                .fill(colors.borderStrong)
                .opacity(0.7)
                .frame(width: 40, height: 5)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            if let title {
                LineText(title, style: TextToken.title.with(lineHeight: 1.4), color: colors.text)
                    .accessibilityAddTraits(.isHeader)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(EdgeInsets(top: 10, leading: MumcordSpace.s4, bottom: MumcordSpace.s2, trailing: MumcordSpace.s4))
        .overlay(alignment: .top) {
            // The line along the top edge, between the rounded corners.
            Rectangle().fill(colors.border).frame(height: 1).padding(.horizontal, MumcordRadius.sheet)
        }
        .background { shape.fill(colors.bgElevated).ignoresSafeArea(edges: .bottom) }
        .tokenShadow(theme.shadows.raised, shape: shape)
    }
}

enum MumcordBannerTone {
    case info
    case warning
    case danger
}

/// Hint banner (`.banner`) with a 16 pt margin at the sides: an icon in the colour of its
/// `tone`, an optional bold `title` and the hint `text`. Inside a bottom sheet, which has its
/// own margin, `horizontalMargin` is zero.
struct MumcordBanner: View {
    let text: String
    var title: String?
    var icon = MumcordIcons.info
    var tone = MumcordBannerTone.info
    var horizontalMargin: CGFloat = MumcordSpace.s4
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.card)
        HStack(spacing: MumcordSpace.s3) {
            MumcordIcon(icon: icon, label: nil, tint: iconTint)
            ParagraphText(message, style: .small, color: colors.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        // 1 pt of each side is the border.
        .padding(EdgeInsets(top: MumcordSpace.s3 + 1, leading: MumcordSpace.s4 + 1, bottom: MumcordSpace.s3 + 1, trailing: MumcordSpace.s3 + 1))
        .background(colors.bgSelected, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .padding(.horizontal, horizontalMargin)
        .padding(.vertical, MumcordSpace.s2)
        .accessibilityElement(children: .combine)
    }

    private var message: AttributedString {
        var message = AttributedString(text)
        if let title {
            var bold = AttributedString(title + " ")
            bold.font = Font(MumcordFonts.font(TextToken.small.with(weight: 700), scale: scale))
            bold.foregroundColor = theme.colors.text
            message = bold + message
        }
        return message
    }

    private var iconTint: Color {
        switch tone {
        case .info: theme.colors.accentText
        case .warning: theme.colors.warning
        case .danger: theme.colors.danger
        }
    }
}
