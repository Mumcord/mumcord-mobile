// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camera

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.ui.common.SheetOverlay

const val CAMERA_CONTINUE_TEST_TAG = "camera-continue"
const val CAMERA_NOT_NOW_TEST_TAG = "camera-not-now"

/**
 * Says in one sentence ([reason]) why the camera is needed, before the system asks for it.
 * Once the system no longer asks ([blocked]), it points to the system settings instead.
 */
@Composable
fun CameraSheet(
    reason: String,
    blocked: Boolean,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.camera_title), onDismiss, topInsets, bottomInsets) {
        MumcordBanner(
            text = if (blocked) "$reason ${stringResource(R.string.camera_blocked_text)}" else reason,
            horizontalMargin = 0.dp,
        )
        MumcordButton(
            text = stringResource(if (blocked) R.string.camera_open_settings else R.string.camera_continue),
            onClick = if (blocked) onOpenSettings else onContinue,
            modifier = Modifier.fillMaxWidth().testTag(CAMERA_CONTINUE_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.camera_not_now),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(CAMERA_NOT_NOW_TEST_TAG),
        )
    }
}
