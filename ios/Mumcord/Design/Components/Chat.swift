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
        MumcordMessage(author: author, time: time, initials: initials, colorSlot: colorSlot) {
            // Built from a plain string: nothing in a message is interpreted as markup.
            ParagraphText(text, style: .body, color: theme.colors.text)
            if Attachment.self != EmptyView.self {
                attachment.padding(.top, MumcordSpace.s2)
            }
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
/// `fieldIdentifier` names the text field for UI tests.
struct MumcordInputBar<Leading: View, Trailing: View>: View {
    @Binding private var text: String
    private let placeholder: String
    private let fieldIdentifier: String
    private let leading: Leading
    private let trailing: Trailing
    @Environment(\.mumcordTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    init(
        text: Binding<String>,
        placeholder: String,
        fieldIdentifier: String = "",
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        _text = text
        self.placeholder = placeholder
        self.fieldIdentifier = fieldIdentifier
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
            .accessibilityIdentifier(fieldIdentifier)
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

private let mentionRowTint = 0.12
private let accentEdge: CGFloat = 3
private let quoteNameMaxWidth: CGFloat = 120
private let pillHeight: CGFloat = 32
private let gifStarSize: CGFloat = 28

/// Chat message (`.msg`) with free `content` under the author line: text runs, a reply quote,
/// images. With `showHeader` off the message continues the one above it: no avatar and no
/// author line, the content stays in the same column. `highlighted` is the tinted row with the
/// accent edge of a message that mentions the user (`.msg.is-mention`).
struct MumcordMessage<Content: View>: View {
    private let author: String
    private let time: String
    private let initials: String
    private let colorSlot: Int
    private let showHeader: Bool
    private let highlighted: Bool
    private let content: Content
    @Environment(\.mumcordTheme) private var theme

    init(
        author: String,
        time: String,
        initials: String,
        colorSlot: Int,
        showHeader: Bool = true,
        highlighted: Bool = false,
        @ViewBuilder content: () -> Content
    ) {
        self.author = author
        self.time = time
        self.initials = initials
        self.colorSlot = colorSlot
        self.showHeader = showHeader
        self.highlighted = highlighted
        self.content = content()
    }

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        HStack(alignment: .top, spacing: MumcordSpace.s3) {
            if showHeader {
                MumcordAvatar(initials: initials, colorSlot: colorSlot, size: .large)
                    .padding(.top, 2)
            } else {
                Color.clear.frame(width: MumcordAvatarSize.large.diameter, height: 1)
            }
            VStack(alignment: .leading, spacing: 0) {
                if showHeader {
                    HStack(alignment: .firstTextBaseline, spacing: MumcordSpace.s2) {
                        LineText(author, style: .bodyStrong, color: colors.text)
                        LineText(time, style: .caption, color: colors.textMuted)
                            .layoutPriority(1)
                    }
                }
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // The highlighted row is 8 pt wider on both sides; the others line up with it.
        .padding(.horizontal, MumcordSpace.s2)
        .padding(.vertical, highlighted ? 6 : 0)
        .background {
            if highlighted {
                colors.accent.opacity(mentionRowTint)
                    .overlay(alignment: .leading) { colors.accent.frame(width: accentEdge) }
                    .clipShape(shape)
            }
        }
    }
}

/// Day separator of a chat (`.daysep`): the day between two hairlines.
struct MumcordDaySeparator: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s3) {
            Rectangle().fill(colors.border).frame(height: 1)
            LineText(text, style: .captionStrong, color: colors.textMuted)
                .fixedSize()
            Rectangle().fill(colors.border).frame(height: 1)
        }
        .padding(.horizontal, MumcordSpace.s2)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// The quoted original above a reply (`.quote`): who wrote it and one line of it, with the
/// accent edge. Both texts are plain.
struct MumcordReplyQuote: View {
    let name: String
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(alignment: .firstTextBaseline, spacing: MumcordSpace.s2) {
            // The name is capped at 64 characters by the engine; the quoted line gives way to it.
            LineText(name, style: .smallStrong, color: colors.text)
                .frame(maxWidth: quoteNameMaxWidth, alignment: .leading)
                .fixedSize(horizontal: true, vertical: false)
                .layoutPriority(1)
            LineText(text, style: .small, color: colors.textSecondary)
        }
        .padding(EdgeInsets(top: 6, leading: 10 + accentEdge, bottom: 6, trailing: 10))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.bgElevated)
        .overlay(alignment: .leading) { colors.accent.frame(width: accentEdge) }
        .clipShape(UnevenRoundedRectangle(bottomTrailingRadius: MumcordRadius.md, topTrailingRadius: MumcordRadius.md))
        .padding(.top, 2)
        .padding(.bottom, MumcordSpace.s1)
    }
}

/// A calm, centred line in a chat for something the app cannot show (`.hint`).
struct MumcordChatHint: View {
    let text: String
    var icon = MumcordIcons.paperclip
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: MumcordSpace.s2) {
            MumcordIcon(icon: icon, label: nil, tint: colors.textMuted, size: MumcordSize.iconSm)
            ParagraphText(text, style: .small, color: colors.textMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, MumcordSpace.s2)
        .accessibilityElement(children: .combine)
    }
}

/// The bar above the input bar while a reply is being written: `title` ("Replying to Anna"),
/// one line of the original and the button that gives the reply up.
struct MumcordReplyBar: View {
    let title: String
    let text: String
    let closeLabel: String
    let onClose: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                LineText(title, style: .smallStrong, color: colors.text)
                LineText(text, style: .small, color: colors.textSecondary)
            }
            .padding(.vertical, MumcordSpace.s2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            MumcordIconButton(icon: MumcordIcons.x, label: closeLabel, action: onClose)
        }
        .padding(.leading, MumcordSpace.s4)
        .padding(.trailing, MumcordSpace.s1)
        .background(colors.bgElevated)
        .overlay(alignment: .leading) { colors.accent.frame(width: accentEdge) }
    }
}

/// A small floating pill over a list, such as "New messages" in a chat.
struct MumcordPill: View {
    let text: String
    var icon: MumcordIconData?
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            HStack(spacing: MumcordSpace.s1) {
                if let icon {
                    MumcordIcon(icon: icon, label: nil, tint: colors.onAccent, size: MumcordSize.iconSm)
                }
                LineText(text, style: .smallStrong, color: colors.onAccent)
            }
            .padding(.horizontal, MumcordSpace.s3)
            .frame(minHeight: pillHeight)
            .background(colors.accent, in: Capsule())
            // Drawn 32 pt high; the touch area is the full 44 pt.
            .frame(minHeight: MumcordSize.touchTargetIos)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A GIF of the favourites grid (`.gif`): the picture (`content`) in a rounded square with the
/// "GIF" tag and the star that takes it out of the favourites. Tapping the tile picks it.
struct MumcordGifTile<Content: View>: View {
    private let label: String
    private let tag: String
    private let removeLabel: String
    private let action: () -> Void
    private let onRemove: () -> Void
    private let content: Content
    @Environment(\.mumcordTheme) private var theme

    init(
        label: String,
        tag: String,
        removeLabel: String,
        action: @escaping () -> Void,
        onRemove: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.label = label
        self.tag = tag
        self.removeLabel = removeLabel
        self.action = action
        self.onRemove = onRemove
        self.content = content()
    }

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.card)
        Button(action: action) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay { content }
                .overlay(alignment: .bottomLeading) {
                    LineText(tag, style: .captionStrong, color: colors.text)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(colors.bgElevated, in: RoundedRectangle(cornerRadius: MumcordRadius.sm))
                        .padding(MumcordSpace.s2)
                        .accessibilityHidden(true)
                }
                .background(colors.bgElevated)
                .clipShape(shape)
                .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .overlay(alignment: .topTrailing) {
            // The star is drawn at 28 pt as in the mockup; its touch area is the full 44 pt.
            Button(action: onRemove) {
                FilledIcon(icon: MumcordIcons.star, tint: colors.warning, size: MumcordSize.iconSm)
                    .frame(width: gifStarSize, height: gifStarSize)
                    .background(colors.bgElevated, in: Circle())
                    .frame(width: MumcordSize.touchTargetIos, height: MumcordSize.touchTargetIos)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(removeLabel)
        }
    }
}

/// An icon of the set with its shape filled in, such as the star of a favourite.
private struct FilledIcon: View {
    let icon: MumcordIconData
    let tint: Color
    let size: CGFloat

    var body: some View {
        let scale = size / MumcordIcons.viewBox
        let path = icon.path.applying(CGAffineTransform(scaleX: scale, y: scale))
        path.fill(tint)
            .overlay {
                path.stroke(tint, style: StrokeStyle(lineWidth: MumcordIcons.strokeWidth * scale, lineCap: .round, lineJoin: .round))
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
