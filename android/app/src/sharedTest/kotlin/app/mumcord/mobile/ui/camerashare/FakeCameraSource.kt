// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camerashare

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.platform.camera.CameraSource
import app.mumcord.mobile.platform.camera.CameraTrouble
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val FAKE_CAMERA_PICTURE_TEST_TAG = "fake-camera-picture"

private const val BODY_ALPHA = 0.45f

/**
 * A camera without a camera: it records what it is asked to do, and its picture is a drawn
 * person like the mockup's. [trouble] makes it fail as a camera would.
 */
class FakeCameraSource(
    private val onTrouble: (CameraTrouble) -> Unit = {},
    available: Set<CameraFacing> = CameraFacing.entries.toSet(),
) : CameraSource {
    /** The camera that runs now, null while stopped. */
    @Volatile
    var running: CameraFacing? = null
        private set

    /** Every start (the camera) and stop (null), in order. */
    val calls = mutableListOf<CameraFacing?>()

    override val facings: StateFlow<Set<CameraFacing>> = MutableStateFlow(available)

    override fun start(facing: CameraFacing) {
        running = facing
        calls += facing
    }

    override fun stop() {
        running = null
        calls += null
    }

    fun trouble(trouble: CameraTrouble) = onTrouble(trouble)

    @Composable
    override fun Preview(modifier: Modifier) {
        val colors = MumcordTheme.colors
        Canvas(modifier.testTag(FAKE_CAMERA_PICTURE_TEST_TAG)) {
            val head = Size(size.width * 0.27f, size.height * 0.37f)
            val body = Size(size.width * 0.66f, size.height * 0.5f)
            drawRoundRect(
                color = colors.accent.copy(alpha = BODY_ALPHA),
                topLeft = Offset((size.width - body.width) / 2f, size.height * 0.58f),
                size = body,
                cornerRadius = CornerRadius(body.width / 2f, body.width / 2f),
            )
            drawOval(
                color = colors.textMuted,
                topLeft = Offset((size.width - head.width) / 2f, size.height * 0.2f),
                size = head,
            )
        }
    }
}
