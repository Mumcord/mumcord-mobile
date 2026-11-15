// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.CameraCapture
import app.mumcord.mobile.bridge.CaptureFailure
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.platform.camera.CameraSource
import app.mumcord.mobile.platform.camera.CameraTrouble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The phone's camera for sharing it (FR-5.74, FR-5.75). The engine decides when the camera is
 * open (from the preview until sharing ends) and calls [start] and [stop]; this runs the
 * camera only while the engine wants it and the app is on screen, so it pauses in the
 * background and resumes on return while sharing goes on (voice is unaffected). It follows the
 * session by itself and outlives the screens.
 *
 * @param newSource makes the camera when the engine opens it, with the callback for a camera
 *   that cannot run; on the main thread.
 * @param scope runs on the main thread, where the camera is started and stopped.
 */
class CameraShare(
    private val repository: SessionRepository,
    private val newSource: (onTrouble: (CameraTrouble) -> Unit) -> CameraSource,
    private val scope: CoroutineScope,
) : CameraCapture {
    private val engineWants = MutableStateFlow(false)
    private val onScreen = MutableStateFlow(false)
    private val facingState = MutableStateFlow(CameraFacing.Front)
    private val sourceState = MutableStateFlow<CameraSource?>(null)

    /** The camera the user picked in the preview. */
    val facing: StateFlow<CameraFacing> = facingState.asStateFlow()

    /** The camera while the engine has it open (also while paused), for the preview. */
    val source: StateFlow<CameraSource?> = sourceState.asStateFlow()

    /** The cameras of the phone; empty until the camera was opened once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val facings: Flow<Set<CameraFacing>> = sourceState.flatMapLatest { it?.facings ?: flowOf(emptySet()) }

    init {
        scope.launch {
            combine(engineWants, onScreen, facingState) { wanted, shown, facing -> Triple(wanted, shown, facing) }
                .collect { (wanted, shown, facing) -> follow(wanted, shown, facing) }
        }
    }

    /** Called by the engine on its own thread: only noted here. */
    override fun start() {
        engineWants.value = true
    }

    /** Called by the engine on its own thread: only noted here. */
    override fun stop() {
        engineWants.value = false
    }

    /** The app came on screen or left it (background, or the system's picture-in-picture window). */
    fun setOnScreen(shown: Boolean) {
        onScreen.value = shown
    }

    /** Opens the preview with the front camera; the engine then starts the camera. */
    suspend fun openPreview(): Outcome<Unit> {
        facingState.value = CameraFacing.Front
        repository.setCameraCapture(this)
        return repository.openCameraPreview()
    }

    /** Front to back and back; also while sharing. */
    fun switchCamera() = facingState.update { if (it == CameraFacing.Front) CameraFacing.Back else CameraFacing.Front }

    private fun follow(wanted: Boolean, shown: Boolean, facing: CameraFacing) {
        val running = sourceState.value
        when {
            wanted && shown -> (running ?: newSource(::trouble).also { sourceState.value = it }).start(facing)
            // Paused: the camera is released while the app is not seen, sharing stays on.
            wanted -> running?.stop()
            else -> {
                running?.stop()
                sourceState.value = null
            }
        }
    }

    /** Either trouble ends sharing; the engine tells the user that the camera stopped. */
    @Suppress("UNUSED_PARAMETER")
    private fun trouble(trouble: CameraTrouble) {
        repository.cameraCaptureFailed(CaptureFailure.CAMERA_FAILED)
    }
}
