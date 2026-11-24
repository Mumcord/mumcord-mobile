// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The badge of a row counts up to this; more is shown as "99+".
private let maxBadge = 99

/// The More tab (mockup `15-more`): the user's own profile with "Edit profile", the activity
/// log, the identities, the themes, the blocked people (not in the mockup; it has no other
/// place), the settings, and leaving the server. Left out until their features exist: "About
/// Mumcord" and "Server information".
struct MoreScreen: View {
    let state: MoreUiState
    let actions: MoreController
    let onBlocked: () -> Void
    let onIdentities: () -> Void
    let onDisconnect: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            MumcordAppBar(title: Strings.tabMore, large: true)
            ScrollView {
                VStack(spacing: MumcordSpace.s3) {
                    if let profile = state.profile {
                        ProfileCard(profile: profile, onEdit: actions.openProfile)
                    }
                    MumcordList {
                        activityRow
                        MumcordListRow(
                            title: Strings.identitiesTitle,
                            icon: MumcordIcons.key,
                            divider: true,
                            action: onIdentities,
                            trailing: { chevron }
                        )
                        .accessibilityIdentifier("more-identities")
                        MumcordListRow(
                            title: Strings.moreThemes,
                            icon: MumcordIcons.moon,
                            divider: true,
                            action: actions.openThemes,
                            trailing: {
                                if let themeName = state.themeName { MumcordListRowValue(text: themeName) }
                                chevron
                            }
                        )
                        .accessibilityIdentifier("more-themes")
                        MumcordListRow(
                            title: Strings.moreBlocked,
                            icon: MumcordIcons.shield,
                            divider: true,
                            action: onBlocked,
                            trailing: { chevron }
                        )
                        .accessibilityIdentifier("more-blocked")
                        MumcordListRow(
                            title: Strings.settingsTitle,
                            icon: MumcordIcons.settings,
                            divider: true,
                            action: actions.openSettings,
                            trailing: { chevron }
                        )
                        .accessibilityIdentifier("more-settings")
                    }
                    MumcordList {
                        MumcordListRow(
                            title: Strings.moreDisconnect(state.serverName),
                            icon: MumcordIcons.phoneOff,
                            danger: true,
                            action: onDisconnect
                        )
                        .accessibilityIdentifier("more-disconnect")
                    }
                }
                .padding(.top, MumcordSpace.s2)
            }
        }
    }

    private var activityRow: some View {
        let badge = state.activityBadge
        return MumcordListRow(
            title: Strings.moreActivity,
            icon: MumcordIcons.activity,
            action: actions.openActivity,
            trailing: {
                if badge > 0 {
                    MumcordBadge(text: badge > maxBadge ? "\(maxBadge)+" : String(badge))
                }
                chevron
            }
        )
        // The number alone says nothing to VoiceOver; the label says what it counts.
        .accessibilityLabel(badge > 0 ? Strings.moreActivityNew(badge) : Strings.moreActivity)
        .accessibilityIdentifier("more-activity")
    }

    private var chevron: some View {
        MumcordIcon(icon: MumcordIcons.chevronRight, label: nil, tint: theme.colors.textMuted)
    }
}

/// The user's own profile at the top of the More tab, as others see it, with "Edit profile".
private struct ProfileCard: View {
    let profile: OwnProfile
    let onEdit: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.list)
        HStack(spacing: MumcordSpace.s4) {
            AvatarPicture(bytes: profile.avatar, points: MumcordAvatarSize.tile.diameter) { picture in
                MumcordAvatar(
                    initials: profile.name.initials(1),
                    colorSlot: avatarSlot(profile.name),
                    size: .tile,
                    image: picture
                )
            }
            VStack(alignment: .leading, spacing: 0) {
                LineText(profile.name, style: .title, color: colors.text)
                    .accessibilityAddTraits(.isHeader)
                // The comment is the user's own text from the server: drawn as plain characters.
                if !profile.commentLine.isEmpty {
                    LineText(profile.commentLine, style: .small, color: colors.textMuted)
                }
                MumcordButton(
                    text: Strings.moreEditProfile,
                    style: .secondary,
                    small: true,
                    icon: MumcordIcons.edit,
                    action: onEdit
                )
                .accessibilityIdentifier("more-edit-profile")
                .padding(.top, MumcordSpace.s2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(MumcordSpace.s4)
        .background(colors.bgElevated, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .padding(.horizontal, MumcordSpace.s4)
    }
}
