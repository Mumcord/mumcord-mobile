// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camerashare

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mumcord.mobile.bridge.CameraProblem
import app.mumcord.mobile.bridge.OwnCamera
import app.mumcord.mobile.bridge.OwnCameraState
import app.mumcord.mobile.platform.camera.CameraFacing

/** What the camera sharing screens show (FR-5.74, FR-5.75). */
data class CameraShareUiState(
    /** The preview sheet "Share your camera", while it is open. */
    val sheet: CameraSheetState? = null,
    /** The camera is shared with the channel: the "Camera on" bar. */
    val sharing: CameraSharingState? = null,
    /** Why the camera stopped by itself or could not be opened, until the user dismisses it. */
    val notice: CameraProblem? = null,
) {
    /** Whether the camera is in use for sharing: the voice view's camera button then stops it. */
    val on: Boolean get() = sheet != null || sharing != null
}

data class CameraSheetState(
    val facing: CameraFacing,
    /** The phone has a front and a back camera. */
    val canSwitch: Boolean,
    /** "Start sharing" was tapped and the camera is not shared yet. */
    val starting: Boolean,
    /** Why the last start failed. */
    val problem: CameraProblem?,
)

data class CameraSharingState(
    val viewers: Int,
    val facing: CameraFacing,
    val canSwitch: Boolean,
)

/** What the user can do with the camera; the screens only call these. */
interface CameraShareActions {
    /** The voice view's camera button while the camera is off (the permission is granted). */
    fun open()

    fun switchCamera()

    /** "Start sharing" in the preview. */
    fun start()

    /** Cancel in the preview, or Stop while sharing. */
    fun stop()

    fun dismissNotice()
}

/**
 * The camera sharing parts of the connected screens: the state, the actions, the camera's
 * picture for the preview and what the camera button does (it asks for the permission first).
 */
class CameraShareUi(
    val state: CameraShareUiState,
    val actions: CameraShareActions,
    val onCameraButton: () -> Unit,
    val preview: @Composable (Modifier) -> Unit,
)

/** The screens' state from the engine's report of the own camera and the chosen camera. */
fun cameraShareUiState(
    camera: OwnCamera,
    facing: CameraFacing,
    facings: Set<CameraFacing>,
    notice: CameraProblem?,
): CameraShareUiState {
    val canSwitch = facings.size > 1
    return when (camera.state) {
        OwnCameraState.PREVIEW, OwnCameraState.STARTING -> CameraShareUiState(
            sheet = CameraSheetState(
                facing = facing,
                canSwitch = canSwitch,
                starting = camera.state == OwnCameraState.STARTING,
                problem = camera.problem,
            ),
            notice = notice,
        )
        OwnCameraState.SHARING -> CameraShareUiState(
            sharing = CameraSharingState(camera.viewers.toInt(), facing, canSwitch),
            notice = notice,
        )
        OwnCameraState.OFF -> CameraShareUiState(notice = notice)
    }
}

/**
 * The notice a change of the own camera leaves: a camera that was starting or shared and is
 * now off with a reason stopped by itself. Null keeps the notice as it is.
 */
fun stoppedNotice(before: OwnCamera, after: OwnCamera): CameraProblem? =
    after.problem.takeIf {
        after.state == OwnCameraState.OFF &&
            (before.state == OwnCameraState.STARTING || before.state == OwnCameraState.SHARING)
    }
