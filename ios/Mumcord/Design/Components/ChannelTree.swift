// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Container of the channel tree (`.tree`).
struct MumcordTree<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: MumcordSpace.s1, leading: MumcordSpace.s2, bottom: MumcordSpace.s3, trailing: MumcordSpace.s2))
    }
}

/// The indented children of a channel: people, stream tiles and sub-channels (`.channel-body`).
struct MumcordChannelBody<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 28)
    }
}

/// Channel row (`.channel`).
///
/// - `expanded`: true or false for a channel that can be folded, nil for one without children
///   (the chevron's space stays empty so that names line up).
/// - `current`: the channel the user is in: highlighted, with `meta` such as "you are here".
/// - `empty`: nobody is inside: the name is muted.
/// - `lockedLabel`: set for a password-protected channel; read by VoiceOver for the lock.
/// - `toggleLabel`: what VoiceOver reads for the chevron, such as "Collapse Lobby".
struct MumcordChannelRow: View {
    let name: String
    var icon = MumcordIcons.hash
    var expanded: Bool?
    var toggleLabel = ""
    var memberCount: Int?
    var current = false
    var empty = false
    var meta: String?
    var lockedLabel: String?
    var onToggleExpanded: () -> Void = {}
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.md)
        HStack(spacing: 6) {
            chevron
            Button(action: action) {
                HStack(spacing: 6) {
                    MumcordIcon(
                        icon: icon,
                        label: nil,
                        tint: current ? colors.talking : colors.textMuted,
                        size: MumcordSize.iconMd
                    )
                    LineText(name, style: current ? .channelStrong : .channel, color: empty ? colors.textMuted : colors.text)
                    if let memberCount {
                        LineText(String(memberCount), style: metaStyle, color: colors.textMuted, tabularNumbers: true)
                            .layoutPriority(1)
                    }
                    Spacer(minLength: 0)
                    if let meta {
                        LineText(meta, style: metaStyle, color: colors.textMuted)
                            .layoutPriority(1)
                    }
                    if let lockedLabel {
                        MumcordIcon(icon: MumcordIcons.lock, label: lockedLabel, tint: colors.textMuted, size: MumcordSize.iconSm)
                    }
                }
                .frame(minHeight: MumcordSize.touchVisual)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(current ? .isSelected : [])
        }
        // 2 pt of each side is the border of the current channel.
        .padding(.leading, 6)
        .padding(.trailing, 10)
        .background {
            if current {
                shape.fill(colors.bgSelected)
            }
        }
        .overlay {
            if current {
                shape.strokeBorder(colors.talking, lineWidth: 2).allowsHitTesting(false)
            }
        }
    }

    private var metaStyle: TextToken {
        TextToken.small.with(weight: current ? 600 : 400)
    }

    @ViewBuilder private var chevron: some View {
        if let expanded {
            Button(action: onToggleExpanded) {
                MumcordIcon(icon: MumcordIcons.chevronDown, label: nil, tint: theme.colors.textMuted, size: 20)
                    .rotationEffect(.degrees(expanded ? 0 : -90))
                    .frame(width: 20, height: MumcordSize.touchVisual)
                    // 44 pt wide: the row's inset and the gap to the channel icon belong to it.
                    .contentShape(Rectangle().size(width: MumcordSize.touchTargetIos, height: MumcordSize.touchTargetIos).offset(x: -18, y: 0))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(toggleLabel)
        } else {
            Color.clear.frame(width: 20, height: 20)
        }
    }
}

enum MumcordAvatarSize: CaseIterable {
    case small
    case medium
    case large
    case extraLarge

    var diameter: CGFloat {
        switch self {
        case .small: 24
        case .medium: 32
        case .large: 40
        case .extraLarge: 88
        }
    }

    var fontSize: CGFloat {
        switch self {
        case .small: 11
        case .medium: 14
        case .large: 16
        case .extraLarge: 32
        }
    }
}

/// Round avatar with initials (`.avatar`). `colorSlot` picks one of the eight avatar colours;
/// `talking` adds the green ring and glow. The ring is drawn outside the avatar and does not
/// change its size.
struct MumcordAvatar: View {
    let initials: String
    let colorSlot: Int
    var size = MumcordAvatarSize.medium
    var talking = false
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        LineText(initials, style: TextToken.smallStrong.with(size: size.fontSize), color: colors.avatarText)
            .frame(width: size.diameter, height: size.diameter)
            .background(colors.avatar(colorSlot), in: Circle())
            .background {
                if talking {
                    // A 2 pt gap in the screen colour, then a 2 pt ring, both outside the avatar.
                    Circle().fill(colors.bgSidebar).padding(-2)
                }
            }
            .background {
                if talking {
                    Circle().fill(colors.talking).padding(-4)
                }
            }
            .tokenShadow(talking ? theme.shadows.talking : nil, shape: Circle())
            .accessibilityHidden(true)
    }
}

enum MumcordStatusTone {
    case muted
    case live
    case off
}

/// A state icon at the end of a person row: muted, deafened, sharing the screen and so on.
struct MumcordPersonStatus {
    let icon: MumcordIconData
    let label: String
    var tone = MumcordStatusTone.muted
}

/// Person row (`.person`): avatar, name, an optional `note` such as "(you)", and state icons at
/// the end. `talkingLabel` is read by VoiceOver while the person talks.
struct MumcordPersonRow: View {
    let name: String
    let initials: String
    let colorSlot: Int
    var talking = false
    var talkingLabel = ""
    var locallyMuted = false
    var note: String?
    var status: [MumcordPersonStatus] = []
    let action: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        Button(action: action) {
            HStack(spacing: MumcordSpace.s3) {
                MumcordAvatar(initials: initials, colorSlot: colorSlot, talking: talking)
                LineText(name, style: talking ? .bodyStrong : .body, color: nameColor)
                if let note {
                    LineText(note, style: .small, color: colors.textMuted)
                        .layoutPriority(1)
                }
                Spacer(minLength: 0)
                if !status.isEmpty {
                    HStack(spacing: MumcordSpace.s2) {
                        ForEach(status.indices, id: \.self) { index in
                            MumcordIcon(
                                icon: status[index].icon,
                                label: status[index].label,
                                tint: tint(of: status[index].tone),
                                size: MumcordSize.iconMd
                            )
                        }
                    }
                }
            }
            .padding(.leading, 6)
            .padding(.trailing, MumcordSpace.s2)
            .frame(minHeight: MumcordSize.touchVisual)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(talking ? talkingLabel : "")
    }

    private var nameColor: Color {
        if locallyMuted { return theme.colors.localMuted }
        return talking ? theme.colors.text : theme.colors.textSecondary
    }

    private func tint(of tone: MumcordStatusTone) -> Color {
        switch tone {
        case .muted: theme.colors.textMuted
        case .live: theme.colors.accentText
        case .off: theme.colors.danger
        }
    }
}
