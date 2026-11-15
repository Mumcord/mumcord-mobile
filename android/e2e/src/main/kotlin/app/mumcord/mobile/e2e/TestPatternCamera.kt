// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.mumcord.mobile.platform.camera.CameraFacing
import app.mumcord.mobile.platform.camera.CameraFrames
import app.mumcord.mobile.platform.camera.CameraSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * A camera that films a test pattern, for the camera test (the emulator's own camera shows a
 * scene nobody can check). 640 x 360 I420 pictures at 30 per second go through the app's real
 * camera input to the engine. The front camera shows a gradient from dark at the top left to
 * bright at the bottom right, the back camera the same mirrored (dark at the top right); a
 * white bar moves through the middle band. `e2e/stream-peer --viewer` checks for exactly this
 * (`pattern.rs`).
 */
class TestPatternCamera(private val frames: CameraFrames) : CameraSource {
    /** The pictures the engine took. */
    val sent = AtomicInteger()

    @Volatile
    var running: CameraFacing? = null
        private set

    private var thread: Thread? = null
    private val y = ByteBuffer.allocateDirect(WIDTH * HEIGHT)
    private val u = ByteBuffer.allocateDirect(WIDTH * HEIGHT / 4)
    private val v = ByteBuffer.allocateDirect(WIDTH * HEIGHT / 4)
    private val gradients = CameraFacing.entries.associateWith { gradient(it == CameraFacing.Back) }

    override val facings: StateFlow<Set<CameraFacing>> = MutableStateFlow(CameraFacing.entries.toSet())

    init {
        repeat(WIDTH * HEIGHT / 4) {
            u.put(NEUTRAL)
            v.put(NEUTRAL)
        }
    }

    override fun start(facing: CameraFacing) {
        running = facing
        if (thread != null) return
        thread = Thread(::film, "test-pattern-camera").apply { start() }
    }

    override fun stop() {
        running = null
        thread?.let {
            it.interrupt()
            it.join()
        }
        thread = null
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        val back = running == CameraFacing.Back
        Canvas(modifier) {
            val dark = if (back) Offset(size.width, 0f) else Offset.Zero
            val bright = if (back) Offset(0f, size.height) else Offset(size.width, size.height)
            drawRect(Brush.linearGradient(listOf(Color.Black, Color.White), start = dark, end = bright))
        }
    }

    private fun film() {
        var index = 0
        while (!Thread.currentThread().isInterrupted) {
            val facing = running ?: break
            draw(facing, index++)
            val result = frames.push(WIDTH, HEIGHT, 0, y, WIDTH, u, v, WIDTH / 2, 1)
            if (result == CameraFrames.PUSH_SENT) sent.incrementAndGet()
            try {
                Thread.sleep(FRAME_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun draw(facing: CameraFacing, index: Int) {
        y.clear()
        y.put(gradients.getValue(facing))
        val bar = (index * BAR_STEP) % (WIDTH - BAR_WIDTH)
        for (row in HEIGHT * 4 / 10 until HEIGHT * 6 / 10) {
            for (column in bar until bar + BAR_WIDTH) y.put(row * WIDTH + column, WHITE)
        }
        y.clear()
    }

    private fun gradient(back: Boolean): ByteArray = ByteArray(WIDTH * HEIGHT) { i ->
        val (x, row) = i % WIDTH to i / WIDTH
        val fromDark = if (back) WIDTH - 1 - x else x
        (16 + 100 * fromDark / WIDTH + 100 * row / HEIGHT).toByte()
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val FRAME_MILLIS = 33L
        const val BAR_WIDTH = 16
        const val BAR_STEP = 8
        const val WHITE: Byte = 235.toByte()
        const val NEUTRAL: Byte = 128.toByte()
    }
}
