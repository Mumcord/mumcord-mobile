// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camerashare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.CameraProblem
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordStageButton
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.platform.camera.CameraSource
import app.mumcord.mobile.ui.common.SheetOverlay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

const val CAMERA_SHARE_PREVIEW_TEST_TAG = "camera-share-preview"
const val CAMERA_SHARE_SWITCH_TEST_TAG = "camera-share-switch"
const val CAMERA_SHARE_START_TEST_TAG = "camera-share-start"
const val CAMERA_SHARE_CANCEL_TEST_TAG = "camera-share-cancel"
const val CAMERA_BAR_TEST_TAG = "camera-bar"
const val CAMERA_BAR_SWITCH_TEST_TAG = "camera-bar-switch"
const val CAMERA_BAR_STOP_TEST_TAG = "camera-bar-stop"
const val CAMERA_NOTICE_TEST_TAG = "camera-notice"
const val CAMERA_NOTICE_DISMISS_TEST_TAG = "camera-notice-dismiss"

/** Height of the camera picture in the preview sheet, as in the mockup. */
private val PREVIEW_HEIGHT = 340.dp

/** Inset of the camera chip and the switch button from the picture's edges. */
private val PREVIEW_INSET = 12.dp

/**
 * The preview sheet "Share your camera" (mockup `13-camera-share`): the camera's picture with
 * the camera's name and the switch between front and back, the note that the camera pauses in
 * the background, "Start sharing" and "Cancel". Nothing is sent before "Start sharing";
 * dismissing the sheet cancels. The mockup's "Blur background" row is left out: it could not
 * be used on phones (no dead controls).
 */
@Composable
fun CameraShareSheet(
    sheet: CameraSheetState,
    camera: CameraShareUi,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val actions = camera.actions
    SheetOverlay(stringResource(R.string.camera_share_title), actions::stop, topInsets, bottomInsets) {
        CameraPreviewCard(sheet, camera.preview, actions::switchCamera)
        sheet.problem?.let { problem ->
            MumcordBanner(
                text = problem.text(),
                tone = MumcordBannerTone.Danger,
                icon = MumcordIcons.Alert,
                horizontalMargin = 0.dp,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            MumcordIcon(MumcordIcons.Info, contentDescription = null, tint = MumcordTheme.colors.accent, size = MumcordSize.iconMd)
            BasicText(
                text = stringResource(R.string.camera_share_pauses),
                style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textSecondary),
            )
        }
        MumcordButton(
            text = stringResource(if (sheet.starting) R.string.camera_share_starting else R.string.camera_share_start),
            onClick = actions::start,
            enabled = !sheet.starting,
            modifier = Modifier.fillMaxWidth().testTag(CAMERA_SHARE_START_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.camera_share_cancel),
            onClick = actions::stop,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(CAMERA_SHARE_CANCEL_TEST_TAG),
        )
    }
}

/** The camera's picture (`.cam`) with the camera's name and, on a phone with two, the switch. */
@Composable
private fun CameraPreviewCard(sheet: CameraSheetState, preview: @Composable (Modifier) -> Unit, onSwitch: () -> Unit) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    val description = stringResource(R.string.camera_share_preview)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .clip(shape)
            .background(colors.mediaBg)
            .border(1.dp, colors.border, shape)
            .testTag(CAMERA_SHARE_PREVIEW_TEST_TAG)
            .semantics { contentDescription = description },
    ) {
        preview(Modifier.fillMaxSize())
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(PREVIEW_INSET)
                .background(colors.mediaScrim, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            MumcordIcon(MumcordIcons.Camera, contentDescription = null, tint = colors.mediaText, size = MumcordSize.iconSm)
            BasicText(
                text = sheet.facing.label(),
                style = MumcordTheme.typography.captionStrong.copy(color = colors.mediaText),
            )
        }
        if (sheet.canSwitch) {
            MumcordStageButton(
                icon = MumcordIcons.SwitchCamera,
                contentDescription = stringResource(R.string.camera_share_switch),
                onClick = onSwitch,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(PREVIEW_INSET)
                    .testTag(CAMERA_SHARE_SWITCH_TEST_TAG),
            )
        }
    }
}

/**
 * Above the voice bar and the voice view's buttons: while the camera is shared, that it is on
 * and how many watch, with the switch and Stop; after it stopped by itself, why. No mockup.
 */
@Composable
fun CameraBar(state: CameraShareUiState, actions: CameraShareActions) {
    val sharing = state.sharing
    val notice = state.notice
    when {
        sharing != null -> SharingBar(sharing, actions)
        notice != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = MumcordSpace.s2).testTag(CAMERA_NOTICE_TEST_TAG),
        ) {
            MumcordBanner(
                title = stringResource(R.string.camera_stopped),
                text = notice.text(),
                icon = MumcordIcons.CameraOff,
                tone = MumcordBannerTone.Warning,
                horizontalMargin = MumcordSpace.s2,
                modifier = Modifier.weight(1f),
            )
            MumcordIconButton(
                icon = MumcordIcons.X,
                contentDescription = stringResource(R.string.camera_notice_dismiss),
                onClick = actions::dismissNotice,
                modifier = Modifier.testTag(CAMERA_NOTICE_DISMISS_TEST_TAG),
            )
        }
    }
}

@Composable
private fun SharingBar(sharing: CameraSharingState, actions: CameraShareActions) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.card)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MumcordSpace.s2, end = MumcordSpace.s2, bottom = MumcordSpace.s2)
            .background(colors.bgSelected, shape)
            .border(1.dp, colors.border, shape)
            .padding(start = MumcordSpace.s3, end = MumcordSpace.s2, top = MumcordSpace.s1, bottom = MumcordSpace.s1)
            .testTag(CAMERA_BAR_TEST_TAG),
    ) {
        MumcordIcon(MumcordIcons.Camera, contentDescription = null, tint = colors.live)
        BasicText(
            text = pluralStringResource(R.plurals.camera_on_watching, sharing.viewers, sharing.viewers),
            style = MumcordTheme.typography.smallStrong.copy(color = colors.text),
            modifier = Modifier.weight(1f),
        )
        if (sharing.canSwitch) {
            MumcordIconButton(
                icon = MumcordIcons.SwitchCamera,
                contentDescription = stringResource(R.string.camera_share_switch),
                onClick = actions::switchCamera,
                modifier = Modifier.testTag(CAMERA_BAR_SWITCH_TEST_TAG),
            )
        }
        MumcordButton(
            text = stringResource(R.string.camera_stop),
            onClick = actions::stop,
            style = MumcordButtonStyle.Danger,
            small = true,
            modifier = Modifier.testTag(CAMERA_BAR_STOP_TEST_TAG),
        )
    }
}

/** The voice view's camera button: opens the preview, or stops the camera while it is in use. */
@Composable
fun cameraButtonLabel(state: CameraShareUiState): String =
    stringResource(if (state.on) R.string.camera_stop_sharing else R.string.camera_share_button)

@Composable
private fun CameraFacing.label(): String = stringResource(
    when (this) {
        CameraFacing.Front -> R.string.camera_share_front
        CameraFacing.Back -> R.string.camera_share_back
    },
)

@Composable
fun CameraProblem.text(): String = stringResource(
    when (this) {
        CameraProblem.NOT_CONNECTED -> R.string.camera_problem_not_connected
        CameraProblem.BUSY -> R.string.camera_problem_busy
        CameraProblem.PERMISSION_DENIED -> R.string.camera_problem_permission
        CameraProblem.CAMERA_FAILED -> R.string.camera_problem_failed
        CameraProblem.DIRECT_CONNECTIONS_OFF -> R.string.camera_problem_direct_off
        CameraProblem.NO_CERTIFICATE -> R.string.camera_problem_no_certificate
        CameraProblem.FULL -> R.string.camera_problem_full
        CameraProblem.RELAY_LOST -> R.string.camera_problem_relay_lost
        CameraProblem.REFUSED -> R.string.camera_problem_refused
    },
)

/** The running camera's picture; nothing while the camera is off. */
@Composable
fun CameraSourcePreview(source: StateFlow<CameraSource?>, modifier: Modifier) {
    val camera by source.collectAsStateWithLifecycle()
    camera?.Preview(modifier)
}
