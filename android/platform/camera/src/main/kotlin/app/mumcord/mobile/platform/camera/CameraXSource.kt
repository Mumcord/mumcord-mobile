// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.camera

import android.content.Context
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The size asked of the camera for the frames: the size the engine sends (16:9, 360 lines,
 * software VP9). A camera without it gives the next larger one, which the bridge scales down.
 */
private val FRAME_SIZE = Size(640, 360)

/**
 * The phone's camera through CameraX: the viewfinder for [Preview] and every frame for the
 * engine through [frames], on a thread of its own. Only the latest frame is kept when the
 * engine is behind, so the camera never waits. The camera is bound to a lifecycle of this
 * source, so it runs exactly between [start] and [stop] and is released by [stop].
 *
 * @param onTrouble called on the main thread when the camera cannot run.
 */
class CameraXSource(
    context: Context,
    private val frames: CameraFrames,
    private val onTrouble: (CameraTrouble) -> Unit,
) : CameraSource {
    private val context = context.applicationContext
    private val main = ContextCompat.getMainExecutor(this.context)
    private val frameThread = Executors.newSingleThreadExecutor { task -> Thread(task, "mumcord-camera-frames") }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    private val available = MutableStateFlow<Set<CameraFacing>>(emptySet())
    private var provider: ProcessCameraProvider? = null
    private var wanted: CameraFacing? = null
    private var bound: CameraFacing? = null
    private var camera: Camera? = null

    // CameraX starts no frames before every bound use case has its surface, so the viewfinder is
    // only bound while a [Preview] is on screen to take it.
    private var previews = 0
    private var previewBound = false

    private val preview = Preview.Builder().build().apply {
        setSurfaceProvider { request -> surfaceRequest.value = request }
    }
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(FRAME_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .build()
        .apply { setAnalyzer(frameThread) { image -> image.use(::push) } }

    override val facings: StateFlow<Set<CameraFacing>> = available

    init {
        owner.registry.currentState = Lifecycle.State.RESUMED
        val starting = ProcessCameraProvider.getInstance(this.context)
        starting.addListener(
            {
                try {
                    val ready = starting.get()
                    provider = ready
                    available.value = CameraFacing.entries.filterTo(mutableSetOf()) { ready.hasCamera(it.selector) }
                    wanted?.let(::bind)
                } catch (_: ExecutionException) {
                    onTrouble(CameraTrouble.Failed)
                }
            },
            main,
        )
    }

    override fun start(facing: CameraFacing) {
        wanted = facing
        if (provider != null && (bound != facing || previewBound != previews > 0)) bind(facing)
    }

    override fun stop() {
        wanted = null
        bound = null
        release()
        surfaceRequest.value = null
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        val request by surfaceRequest.collectAsState()
        DisposableEffect(Unit) {
            previewShown(1)
            onDispose { previewShown(-1) }
        }
        Box(modifier) {
            request?.let { CameraXViewfinder(surfaceRequest = it, contentScale = ContentScale.Crop) }
        }
    }

    private fun previewShown(change: Int) {
        previews += change
        wanted?.let(::start)
    }

    private fun bind(facing: CameraFacing) {
        val cameras = provider ?: return
        val use = facing.takeIf { it in available.value } ?: available.value.firstOrNull()
        if (use == null) {
            onTrouble(CameraTrouble.NoCamera)
            return
        }
        release()
        try {
            val withPreview = previews > 0
            val opened = if (withPreview) {
                cameras.bindToLifecycle(owner, use.selector, preview, analysis)
            } else {
                cameras.bindToLifecycle(owner, use.selector, analysis)
            }
            previewBound = withPreview
            opened.cameraInfo.cameraState.observe(owner) { state ->
                if (state.error?.type == CameraState.ErrorType.CRITICAL) onTrouble(CameraTrouble.Failed)
            }
            camera = opened
            bound = facing
        } catch (_: IllegalArgumentException) {
            onTrouble(CameraTrouble.NoCamera)
        } catch (_: IllegalStateException) {
            onTrouble(CameraTrouble.Failed)
        }
    }

    private fun release() {
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        camera = null
        provider?.unbindAll()
    }

    private fun push(image: ImageProxy) {
        val (y, u, v) = image.planes
        frames.push(
            image.width,
            image.height,
            image.imageInfo.rotationDegrees,
            y.buffer,
            y.rowStride,
            u.buffer,
            v.buffer,
            u.rowStride,
            u.pixelStride,
        )
    }
}

private val CameraFacing.selector: CameraSelector
    get() = when (this) {
        CameraFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
        CameraFacing.Back -> CameraSelector.DEFAULT_BACK_CAMERA
    }
