// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

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

const val LOCAL_NETWORK_CONTINUE_TEST_TAG = "local-network-continue"

/**
 * Says in one sentence why a server on the local network needs the permission, before the
 * system asks for it. "Not now" and going back leave everything as it was.
 */
@Composable
fun LocalNetworkSheet(
    serverName: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.local_network_title), onDismiss, topInsets, bottomInsets) {
        MumcordBanner(text = stringResource(R.string.local_network_text, serverName), horizontalMargin = 0.dp)
        MumcordButton(
            text = stringResource(R.string.local_network_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag(LOCAL_NETWORK_CONTINUE_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.local_network_not_now),
            onClick = onDismiss,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
