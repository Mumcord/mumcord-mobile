// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val REMOVE_CONFIRM_TEST_TAG = "server-remove-confirm"

/** Removing a server is destructive (its saved password goes too), so it asks first. */
@Composable
fun RemoveServerSheet(
    serverName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.remove_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.remove_text, serverName))
        MumcordButton(
            text = stringResource(R.string.remove_confirm),
            onClick = onConfirm,
            style = MumcordButtonStyle.Danger,
            icon = MumcordIcons.Trash,
            modifier = Modifier.fillMaxWidth().testTag(REMOVE_CONFIRM_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
