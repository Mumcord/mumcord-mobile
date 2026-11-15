// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBadge
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace

const val MORE_ACTIVITY_TEST_TAG = "more-activity"
const val MORE_DISCONNECT_TEST_TAG = "more-disconnect"
const val MORE_BLOCKED_TEST_TAG = "more-blocked"
const val MORE_SETTINGS_TEST_TAG = "more-settings"
const val MORE_IDENTITIES_TEST_TAG = "more-identities"

/**
 * The More tab (mockup `15-more`): the activity log, the identities, the blocked people (not in
 * the mockup; it has no other place), the settings, and leaving the server. Left out until
 * their features exist: the profile with "Edit profile" and "Themes" (task 8.3), "About
 * Mumcord" (task 10.2) and "Server information".
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
