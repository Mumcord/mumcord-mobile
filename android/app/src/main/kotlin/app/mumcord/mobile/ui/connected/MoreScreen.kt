// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAvatar
import app.mumcord.mobile.design.components.MumcordAvatarSize
import app.mumcord.mobile.design.components.MumcordBadge
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.profile.OwnProfile
import app.mumcord.mobile.ui.profile.rememberAvatarBitmap

const val MORE_ACTIVITY_TEST_TAG = "more-activity"
const val MORE_DISCONNECT_TEST_TAG = "more-disconnect"
const val MORE_BLOCKED_TEST_TAG = "more-blocked"
const val MORE_SETTINGS_TEST_TAG = "more-settings"
const val MORE_IDENTITIES_TEST_TAG = "more-identities"
const val MORE_THEMES_TEST_TAG = "more-themes"
const val MORE_EDIT_PROFILE_TEST_TAG = "more-edit-profile"
const val MORE_ABOUT_TEST_TAG = "more-about"

/**
 * The More tab (mockup `15-more`): the user's own profile with "Edit profile", the activity log,
 * the identities, the themes, the blocked people (not in the mockup; it has no other place),
 * the settings, "About Mumcord" with the app version, and leaving the server. Left out until
 * its feature exists: "Server information".
 */
@Composable
fun MoreScreen(
    state: MoreUiState,
    actions: MoreController,
    onBlocked: () -> Unit,
    onIdentities: () -> Unit,
    onDisconnect: () -> Unit,
    topInsets: WindowInsets,
) {
    Column(Modifier.fillMaxSize()) {
        MumcordAppBar(title = stringResource(R.string.tab_more), large = true, windowInsets = topInsets)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(MumcordSpace.s2))
            state.profile?.let { profile ->
                ProfileCard(profile, onEdit = actions::openProfile)
                Spacer(Modifier.height(MumcordSpace.s3))
            }
            MumcordList {
                val badge = state.activityBadge
                val title = stringResource(R.string.more_activity)
                val description = if (badge > 0) pluralStringResource(R.plurals.more_activity_new, badge, badge) else title
                MumcordListRow(
                    title = title,
                    icon = MumcordIcons.Activity,
                    trailing = {
                        if (badge > 0) MumcordBadge(badgeText(badge))
                        Chevron()
                    },
                    onClick = actions::openActivity,
                    modifier = Modifier
                        .testTag(MORE_ACTIVITY_TEST_TAG)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                )
                MumcordListRow(
                    title = stringResource(R.string.identities_title),
                    icon = MumcordIcons.Key,
                    divider = true,
                    trailing = { Chevron() },
                    onClick = onIdentities,
                    modifier = Modifier.testTag(MORE_IDENTITIES_TEST_TAG),
                )
                MumcordListRow(
                    title = stringResource(R.string.more_themes),
                    icon = MumcordIcons.Moon,
                    divider = true,
                    trailing = {
                        state.themeName?.let { MumcordListRowValue(it) }
                        Chevron()
                    },
                    onClick = actions::openThemes,
                    modifier = Modifier.testTag(MORE_THEMES_TEST_TAG),
                )
                MumcordListRow(
                    title = stringResource(R.string.more_blocked),
                    icon = MumcordIcons.Shield,
                    divider = true,
                    trailing = { Chevron() },
                    onClick = onBlocked,
                    modifier = Modifier.testTag(MORE_BLOCKED_TEST_TAG),
                )
                MumcordListRow(
                    title = stringResource(R.string.settings_title),
                    icon = MumcordIcons.Settings,
                    divider = true,
                    trailing = { Chevron() },
                    onClick = actions::openSettings,
                    modifier = Modifier.testTag(MORE_SETTINGS_TEST_TAG),
                )
            }
            Spacer(Modifier.height(MumcordSpace.s3))
            MumcordList {
                MumcordListRow(
                    title = stringResource(R.string.more_about),
                    icon = MumcordIcons.Star,
                    trailing = {
                        MumcordListRowValue(state.appVersion)
                        Chevron()
                    },
                    onClick = actions::openAbout,
                    modifier = Modifier.testTag(MORE_ABOUT_TEST_TAG),
                )
            }
            Spacer(Modifier.height(MumcordSpace.s3))
            MumcordList {
                MumcordListRow(
                    title = stringResource(R.string.more_disconnect, state.serverName),
                    icon = MumcordIcons.PhoneOff,
                    danger = true,
                    onClick = onDisconnect,
                    modifier = Modifier.testTag(MORE_DISCONNECT_TEST_TAG),
                )
            }
        }
    }
}

@Composable
private fun Chevron() {
    MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = MumcordTheme.colors.textMuted)
}

/** The user's own profile at the top of the More tab, as others see it, with "Edit profile". */
@Composable
private fun ProfileCard(profile: OwnProfile, onEdit: () -> Unit) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.list)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .fillMaxWidth()
            .background(colors.bgElevated, shape)
            .border(1.dp, colors.border, shape)
            .padding(MumcordSpace.s4),
    ) {
        MumcordAvatar(
            initials = profile.name.initials(1),
            colorSlot = avatarSlot(profile.name),
            size = MumcordAvatarSize.Tile,
            image = rememberAvatarBitmap(profile.avatar),
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                profile.name,
                style = MumcordTheme.typography.title.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            // The comment is the user's own text from the server: drawn as plain characters.
            if (profile.commentLine.isNotEmpty()) {
                BasicText(
                    profile.commentLine,
                    style = MumcordTheme.typography.small.copy(color = colors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(MumcordSpace.s2))
            MumcordButton(
                text = stringResource(R.string.more_edit_profile),
                onClick = onEdit,
                style = MumcordButtonStyle.Secondary,
                small = true,
                icon = MumcordIcons.Edit,
                modifier = Modifier.testTag(MORE_EDIT_PROFILE_TEST_TAG),
            )
        }
    }
}
