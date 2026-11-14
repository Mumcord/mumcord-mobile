// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Section header above a list (`.section`): small capitals, with an optional text action at
/// the end such as "Edit".
struct MumcordSectionHeader: View {
    let title: String
    var action: String?
    var onAction: () -> Void = {}
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        HStack(spacing: MumcordSpace.s2) {
            LineText(title.uppercased(), style: .section, color: theme.colors.textMuted)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            if let action {
                Button(action: onAction) {
                    LineText(action, style: .smallStrong, color: theme.colors.accentText)
                        // The text is lower than a touch target; the area around it reacts too.
                        .contentShape(Rectangle().inset(by: -12))
                }
                .buttonStyle(.plain)
                .layoutPriority(1)
            }
        }
        .padding(EdgeInsets(top: MumcordSpace.s4, leading: MumcordSpace.s4, bottom: MumcordSpace.s2, trailing: MumcordSpace.s4))
    }
}

/// Card that groups list rows (`.list`), with a 16 pt margin at the sides. `flat` drops the
/// card and the margin (`.list--flat`). Inside a bottom sheet the card has no margin and uses
/// the hover colour as `background`.
struct MumcordList<Content: View>: View {
    private let flat: Bool
    private let horizontalMargin: CGFloat
    private let background: Color?
    private let content: Content
    @Environment(\.mumcordTheme) private var theme

    init(
        flat: Bool = false,
        horizontalMargin: CGFloat = MumcordSpace.s4,
        background: Color? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.flat = flat
        self.horizontalMargin = horizontalMargin
        self.background = background
        self.content = content()
    }

    var body: some View {
        let rows = VStack(spacing: 0) { content }.frame(maxWidth: .infinity)
        if flat {
            rows
        } else {
            let shape = RoundedRectangle(cornerRadius: MumcordRadius.list)
            rows
                .background(background ?? theme.colors.bgElevated)
                .clipShape(shape)
                .overlay { shape.strokeBorder(theme.colors.border, lineWidth: 1) }
                .padding(.horizontal, horizontalMargin)
        }
    }
}

/// Row of a list (`.row`). Every row but the first of a list sets `divider` to get the line
/// above it.
///
/// - `leading`: replaces `icon` with other content, such as an avatar or a server tile.
/// - `trailing`: content at the end: a value, a chevron, a toggle.
/// - `tall`: the taller variant for rows with a tile.
/// - `danger`: destructive action: title and icon in the danger colour.
struct MumcordListRow<Leading: View, Trailing: View>: View {
    private let title: String
    private let subtitle: String?
    private let icon: MumcordIconData?
    private let divider: Bool
    private let tall: Bool
    private let danger: Bool
    private let selected: Bool
    private let action: (() -> Void)?
    private let leading: Leading
    private let trailing: Trailing
    @Environment(\.mumcordTheme) private var theme

    init(
        title: String,
        subtitle: String? = nil,
        icon: MumcordIconData? = nil,
        divider: Bool = false,
        tall: Bool = false,
        danger: Bool = false,
        selected: Bool = false,
        action: (() -> Void)? = nil,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = title
        self.subtitle = subtitle
        self.icon = icon
        self.divider = divider
        self.tall = tall
        self.danger = danger
        self.selected = selected
        self.action = action
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        if let action {
            Button(action: action) { row.contentShape(Rectangle()) }
                .buttonStyle(.plain)
        } else {
            row.accessibilityElement(children: .combine)
        }
    }

    private var row: some View {
        let colors = theme.colors
        let vertical = tall ? MumcordSpace.s3 : MumcordSpace.s2
        return HStack(spacing: MumcordSpace.s3) {
            if Leading.self != EmptyView.self {
                leading
            } else if let icon {
                MumcordIcon(icon: icon, label: nil, tint: danger ? colors.danger : colors.textSecondary)
                    .frame(width: 24)
            }
            VStack(alignment: .leading, spacing: 0) {
                LineText(title, style: .bodyStrong, color: danger ? colors.danger : colors.text)
                if let subtitle {
                    LineText(subtitle, style: .small, color: colors.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: MumcordSpace.s2) { trailing }
                .layoutPriority(1)
        }
        // The divider line is part of the row's height, as with the CSS border.
        .padding(EdgeInsets(top: divider ? vertical + 1 : vertical, leading: MumcordSpace.s4, bottom: vertical, trailing: MumcordSpace.s4))
        .frame(minHeight: tall ? 76 : 56)
        .background(selected ? colors.bgSelected : .clear)
        .overlay(alignment: .top) {
            if divider {
                Rectangle().fill(colors.border).frame(height: 1)
            }
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension MumcordListRow where Leading == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        icon: MumcordIconData? = nil,
        divider: Bool = false,
        tall: Bool = false,
        danger: Bool = false,
        selected: Bool = false,
        action: (() -> Void)? = nil,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            icon: icon,
            divider: divider,
            tall: tall,
            danger: danger,
            selected: selected,
            action: action,
            leading: { EmptyView() },
            trailing: trailing
        )
    }
}

extension MumcordListRow where Trailing == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        divider: Bool = false,
        tall: Bool = false,
        danger: Bool = false,
        selected: Bool = false,
        action: (() -> Void)? = nil,
        @ViewBuilder leading: () -> Leading
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            icon: nil,
            divider: divider,
            tall: tall,
            danger: danger,
            selected: selected,
            action: action,
            leading: leading,
            trailing: { EmptyView() }
        )
    }
}

extension MumcordListRow where Leading == EmptyView, Trailing == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        icon: MumcordIconData? = nil,
        divider: Bool = false,
        tall: Bool = false,
        danger: Bool = false,
        selected: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            icon: icon,
            divider: divider,
            tall: tall,
            danger: danger,
            selected: selected,
            action: action,
            leading: { EmptyView() },
            trailing: { EmptyView() }
        )
    }
}

/// Small muted text for the end of a list row, such as the current value of a setting.
struct MumcordListRowValue: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        LineText(text, style: .small, color: theme.colors.textMuted)
    }
}
