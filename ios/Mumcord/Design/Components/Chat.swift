// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Chat message (`.msg`): avatar, author with time, and the text. `attachment` is shown under
/// the text (an image). The text is plain: it comes from the server and is shown as it is,
/// never interpreted here.
struct MumcordMessageRow<Attachment: View>: View {
    private let author: String
    private let time: String
    private let text: String
    private let initials: String
    private let colorSlot: Int
    private let attachment: Attachment
    @Environment(\.mumcordTheme) private var theme

    init(
        author: String,
        time: String,
        text: String,
        initials: String,
        colorSlot: Int,
        @ViewBuilder attachment: () -> Attachment
    ) {
        self.author = author
        self.time = time
        self.text = text
        self.initials = initials
        self.colorSlot = colorSlot
        self.attachment = attachment()
    }

    var body: some View {
        let colors = theme.colors
        HStack(alignment: .top, spacing: MumcordSpace.s3) {
            MumcordAvatar(initials: initials, colorSlot: colorSlot, size: .large)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: MumcordSpace.s2) {
                    LineText(author, style: .bodyStrong, color: colors.text)
                    LineText(time, style: .caption, color: colors.textMuted)
                        .layoutPriority(1)
                }
                // Built from a plain string: nothing in a message is interpreted as markup.
                ParagraphText(text, style: .body, color: colors.text)
                if Attachment.self != EmptyView.self {
                    attachment.padding(.top, MumcordSpace.s2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

extension MumcordMessageRow where Attachment == EmptyView {
    init(author: String, time: String, text: String, initials: String, colorSlot: Int) {
        self.init(author: author, time: time, text: text, initials: initials, colorSlot: colorSlot, attachment: { EmptyView() })
    }
}

/// Text input bar at the bottom of a chat (`.inputbar`): buttons before the field (`leading`,
/// such as image and GIF), the field, and buttons after it (`trailing`, such as send). Its
/// background fills the area of the home indicator when it is the last element of a screen.
struct MumcordInputBar<Leading: View, Trailing: View>: View {
    @Binding private var text: String
    private let placeholder: String
    private let leading: Leading
    private let trailing: Trailing
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    init(
        text: Binding<String>,
        placeholder: String,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        _text = text
        self.placeholder = placeholder
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        let colors = theme.colors
        HStack(spacing: 2) {
            leading
            TextField(text: $text, prompt: Text(placeholder).foregroundStyle(colors.textMuted), axis: .vertical) {
                Text(placeholder)
            }
            .lineLimit(1...4)
            .font(Font(MumcordFonts.font(.body, scale: scale)))
            .foregroundStyle(colors.text)
            .tint(colors.accent)
            .padding(.horizontal, MumcordSpace.s4)
            .frame(minHeight: MumcordSize.touchVisual)
            .background(colors.bgElevated, in: Capsule())
            .overlay { Capsule().strokeBorder(colors.border, lineWidth: 1) }
            trailing
        }
        // 1 pt of the top padding is the border line.
        .padding(EdgeInsets(top: MumcordSpace.s2 + 1, leading: MumcordSpace.s2, bottom: MumcordSpace.s2, trailing: MumcordSpace.s2))
        .overlay(alignment: .top) {
            Rectangle().fill(colors.border).frame(height: 1)
        }
        .background { colors.bgChat.ignoresSafeArea(edges: .bottom) }
    }
}
