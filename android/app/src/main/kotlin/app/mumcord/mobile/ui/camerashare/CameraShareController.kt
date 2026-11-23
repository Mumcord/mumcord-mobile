// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camerashare

import app.mumcord.mobile.bridge.CameraProblem
import app.mumcord.mobile.platform.camera.CameraSource
import app.mumcord.mobile.session.CameraShare
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sharing the own camera from the screens: the preview sheet, the "Camera on" bar and the
 * notice of a camera that stopped by itself. The camera itself belongs to [camera], which
 * outlives the screens.
 */
class CameraShareController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val camera: CameraShare,
) : CameraShareActions {
    private val notice = MutableStateFlow<CameraProblem?>(null)
    private val ownCamera = repository.state.map { it.ownCamera }.distinctUntilChanged()

    val state: StateFlow<CameraShareUiState> =
        combine(ownCamera, camera.facing, camera.facings, notice, ::cameraShareUiState)
            .stateIn(scope, SharingStarted.Eagerly, CameraShareUiState())

    /** The camera's picture for the preview; null while the camera is off. */
    val source: StateFlow<CameraSource?> = camera.source

    init {
        scope.launch {
            var before = repository.state.value.ownCamera
            ownCamera.collect { after ->
                stoppedNotice(before, after)?.let { notice.value = it }
                before = after
            }
        }
    }

    override fun open() {
        notice.value = null
        scope.launch {
            if (camera.openPreview() is Outcome.Failed) notice.value = CameraProblem.CAMERA_FAILED
        }
    }

    override fun switchCamera() = camera.switchCamera()

    override fun start() {
        scope.launch { repository.startCameraSharing() }
    }

    override fun stop() {
        scope.launch { repository.stopCamera() }
    }

    override fun dismissNotice() {
        notice.value = null
    }
}
