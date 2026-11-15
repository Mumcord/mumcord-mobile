// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The two things only the system can do for the local network permission: [ask] shows the
 * system's question, [openSettings] the app's page in the system settings.
 */
class LocalNetworkSystem(val ask: () -> Unit, val openSettings: () -> Unit)

/** The real system prompt and settings page; the answer to the prompt goes to [onAnswer]. */
@Composable
fun rememberLocalNetworkSystem(onAnswer: (LocalNetworkAnswer) -> Unit): LocalNetworkSystem {
    val activity = checkNotNull(LocalActivity.current) { "The app's screens run inside an Activity" }
    // Kept across a rotation while the system's question is open.
    var refusedBefore by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onAnswer(
            LocalNetworkAnswer(
                granted = granted,
                refusedBefore = refusedBefore,
                refusedAfter = activity.shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK),
            ),
        )
    }
    return remember(activity, launcher) {
        LocalNetworkSystem(
            ask = {
                refusedBefore = activity.shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK)
                launcher.launch(ACCESS_LOCAL_NETWORK)
            },
            openSettings = {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null),
                    ),
                )
            },
        )
    }
}
