// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.link

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay
import app.mumcord.mobile.ui.connect.messageRes
import app.mumcord.mobile.ui.servers.displayAddress

const val LINK_CONNECT_TEST_TAG = "link-connect"

/**
 * Shows where an opened `mumble://` link leads (server, channel, user name) and connects only
 * when the user confirms. Everything in a link comes from outside and is drawn as plain text.
 */
@Composable
fun LinkConfirmSheet(
    state: LinkConfirmState,
    onUsernameChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val link = state.link
    val address = displayAddress(link.address, link.port)
    SheetOverlay(stringResource(R.string.link_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.link_text))
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            MumcordListRow(
                title = stringResource(R.string.link_server),
                subtitle = state.savedServer?.let { "${it.name} · $address" } ?: address,
                icon = MumcordIcons.Globe,
            )
            if (link.channelPath.isNotEmpty()) {
                MumcordListRow(
                    title = stringResource(R.string.link_channel),
                    subtitle = link.channelPath.joinToString(" / "),
                    icon = MumcordIcons.Hash,
                    divider = true,
                )
            }
            if (!state.asksForUsername) {
                MumcordListRow(
                    title = stringResource(R.string.link_username),
                    subtitle = state.username,
                    icon = MumcordIcons.User,
                    divider = true,
                )
            }
        }
        if (state.asksForUsername) {
            MumcordTextField(
                value = state.typedUsername,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.link_username),
                hint = stringResource(R.string.form_username_hint),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        }
        if (state.willAddServer) {
            MumcordBanner(text = stringResource(R.string.link_will_add), horizontalMargin = 0.dp)
        }
        if (state.error != null) {
            MumcordBanner(
                text = stringResource(state.error.messageRes()),
                icon = MumcordIcons.Alert,
                tone = MumcordBannerTone.Danger,
                horizontalMargin = 0.dp,
            )
        }
        MumcordButton(
            text = stringResource(R.string.servers_connect),
            onClick = onConnect,
            enabled = state.canConnect,
            modifier = Modifier.fillMaxWidth().testTag(LINK_CONNECT_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** An opened link that is not a usable `mumble://` link. */
@Composable
fun InvalidLinkSheet(onDismiss: () -> Unit, topInsets: WindowInsets, bottomInsets: WindowInsets) {
    SheetOverlay(stringResource(R.string.link_invalid_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.link_invalid_text))
        MumcordButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
