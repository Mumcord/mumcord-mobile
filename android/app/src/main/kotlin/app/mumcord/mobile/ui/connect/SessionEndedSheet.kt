// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connect

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val SESSION_ENDED_RECONNECT_TEST_TAG = "session-ended-reconnect"
const val SESSION_ENDED_CLOSE_TEST_TAG = "session-ended-close"

/**
 * Shown at the start after the system closed the app in the middle of a voice session
 * (FR-5.37). Reconnecting is offered, never done by itself.
 */
@Composable
fun SessionEndedSheet(
    serverName: String,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.session_ended_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.session_ended_text, serverName))
        MumcordButton(
            text = stringResource(R.string.session_ended_reconnect),
            onClick = onReconnect,
            modifier = Modifier.fillMaxWidth().testTag(SESSION_ENDED_RECONNECT_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.session_ended_close),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(SESSION_ENDED_CLOSE_TEST_TAG),
        )
    }
}
