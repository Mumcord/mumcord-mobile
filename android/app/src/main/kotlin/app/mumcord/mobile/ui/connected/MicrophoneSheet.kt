// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.ui.common.SheetOverlay

const val MICROPHONE_CONTINUE_TEST_TAG = "microphone-continue"
const val MICROPHONE_NOT_NOW_TEST_TAG = "microphone-not-now"
const val MICROPHONE_BANNER_TEST_TAG = "microphone-banner"

/**
 * Says in one sentence why the microphone is needed, before the system asks for it. "Not now"
 * and going back leave the user listening.
 */
@Composable
fun MicrophoneSheet(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.microphone_title), onDismiss, topInsets, bottomInsets) {
        MumcordBanner(text = stringResource(R.string.microphone_text), horizontalMargin = 0.dp)
        MumcordButton(
            text = stringResource(R.string.microphone_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag(MICROPHONE_CONTINUE_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.microphone_not_now),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(MICROPHONE_NOT_NOW_TEST_TAG),
        )
    }
}

/**
 * The calm notice while the user listens without the microphone. Tapping it lets the system ask
 * again, or opens the system settings once the system no longer asks ([blocked]).
 */
@Composable
fun MicrophoneBanner(blocked: Boolean, onClick: () -> Unit) {
    MumcordBanner(
        title = stringResource(R.string.microphone_denied_title),
        text = stringResource(if (blocked) R.string.microphone_denied_settings else R.string.microphone_denied_ask),
        tone = MumcordBannerTone.Warning,
        icon = MumcordIcons.Alert,
        modifier = Modifier
            .testTag(MICROPHONE_BANNER_TEST_TAG)
            .clickable(
                onClickLabel = stringResource(
                    if (blocked) R.string.microphone_open_settings else R.string.microphone_allow,
                ),
                onClick = onClick,
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
