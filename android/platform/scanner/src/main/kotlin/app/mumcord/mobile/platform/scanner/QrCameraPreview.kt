// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.scanner

import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

// Large enough for the dense codes of a transfer, small enough to look at every frame.
private val ANALYSIS_SIZE = Size(1280, 720)

/**
 * The picture of the back camera, with [onText] called on the main thread for every frame in
 * which a QR code is read (so many times for a code that stays in view). The camera runs while
 * this is on screen and the app is in front. The caller has the camera permission.
 * [onUnavailable] is called when the phone has no camera that can be used.
 */
@Composable
fun QrCameraPreview(onText: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    DisposableEffect(lifecycleOwner) {
        val main = ContextCompat.getMainExecutor(context)
        val frames = Executors.newSingleThreadExecutor()
        val decoder = QrDecoder()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .build()
        var disposed = false
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(frames) { frame ->
            val text = frame.use { decoder.decode(it) }
            if (text != null) main.execute { if (!disposed) currentOnText(text) }
        }
        val starting = ProcessCameraProvider.getInstance(context)
        starting.addListener(
            {
                if (!disposed) {
                    try {
                        val camera = starting.get()
                        camera.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        provider = camera
                    } catch (_: ExecutionException) {
                        currentOnUnavailable()
                    } catch (_: IllegalArgumentException) {
                        // The phone has no back camera.
                        currentOnUnavailable()
                    } catch (_: IllegalStateException) {
                        currentOnUnavailable()
                    }
                }
            },
            main,
        )
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            surfaceRequest = null
            frames.shutdown()
        }
    }
    Box(modifier) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(surfaceRequest = request, contentScale = ContentScale.Crop)
        }
    }
}

/** The brightness plane of a camera frame is all a QR code needs. */
private fun QrDecoder.decode(frame: ImageProxy): String? {
    val plane = frame.planes.firstOrNull() ?: return null
    val luma = ByteArray(plane.buffer.remaining())
    plane.buffer.get(luma)
    return decode(luma, frame.width, frame.height, plane.rowStride)
}
