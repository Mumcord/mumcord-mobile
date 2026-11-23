// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The badge of a row counts up to this; more is shown as "99+".
private let maxBadge = 99

/// The More tab (mockup `15-more`): the activity log, the identities, the blocked people (not in
/// the mockup; it has no other place), the settings, and leaving the server. Left out until
/// their features exist: the profile with "Edit profile", "Themes", "About Mumcord" and
/// "Server information".
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
