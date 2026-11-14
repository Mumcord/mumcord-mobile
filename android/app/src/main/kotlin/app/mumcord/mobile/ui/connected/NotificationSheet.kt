// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.ui.common.SheetOverlay

const val NOTIFICATIONS_CONTINUE_TEST_TAG = "notifications-continue"
const val NOTIFICATIONS_NOT_NOW_TEST_TAG = "notifications-not-now"

/** The one thing only the system can do for the notification permission: [ask] shows its question. */
class NotificationSystem(val ask: () -> Unit)

/** The real system prompt; whether the user allowed notifications goes to [onAnswer]. */
@Composable
fun rememberNotificationSystem(onAnswer: (Boolean) -> Unit): NotificationSystem {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onAnswer)
    return remember(launcher) {
        NotificationSystem(
            ask = {
                // Before Android 13 there is nothing to ask; the question is then never shown.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}

/**
 * Says in one sentence what the notification is for, before the system asks for it. "Not now"
 * and going back change nothing about the voice session.
 */
@Composable
fun NotificationSheet(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.notifications_title), onDismiss, topInsets, bottomInsets) {
        MumcordBanner(text = stringResource(R.string.notifications_text), horizontalMargin = 0.dp)
        MumcordButton(
            text = stringResource(R.string.notifications_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag(NOTIFICATIONS_CONTINUE_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.notifications_not_now),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(NOTIFICATIONS_NOT_NOW_TEST_TAG),
        )
    }
}
