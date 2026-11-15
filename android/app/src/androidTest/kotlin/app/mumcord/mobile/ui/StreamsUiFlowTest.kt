// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import app.mumcord.mobile.bridge.StreamKind
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.video.VideoFrames
import app.mumcord.mobile.platform.video.VideoView
import app.mumcord.mobile.platform.video.packSize
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.mockupStream
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.streams.DIRECT_CONNECTIONS_YES_TEST_TAG
import app.mumcord.mobile.ui.streams.LocalStreamPicture
import app.mumcord.mobile.ui.streams.STREAM_COLLAPSE_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_FULL_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_MINI_WINDOW_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_PICTURE_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_STOP_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_TILE_TEST_TAG
import app.mumcord.mobile.ui.streams.StreamVideo
import app.mumcord.mobile.ui.streams.streamPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watching a stream on a device against a fake engine whose pictures are made here: synthetic
 * I420 pictures, red on the left and blue on the right, go through the real render path
 * (TextureView, OpenGL ES, the YUV shader) and must come out in those colours.
 */
class StreamsUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = SyntheticFrames()
    private val video = StreamVideo(frames)
    private lateinit var engine: FakeEngine

    // Plays the bridge's video thread: says that a picture is ready, 30 times a second.
    private val bridgeVideo = Executors.newSingleThreadScheduledExecutor()

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() {
        bridgeVideo.shutdownNow()
        scope.cancel()
    }

    /** Connected to Nordlicht on mobile data (streams wait for a tap), Mira sharing her screen. */
    private fun connect(): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            streamsAutoplay = flowOf(false),
            streamVideo = video,
        )
        compose.setContent {
            MumcordTheme {
                CompositionLocalProvider(LocalStreamPicture provides streamPicture(video)) {
                    AppRoute(
                        viewModel,
                        LocalNetworkSystem(ask = {}, openSettings = {}),
                        MicrophoneSystem(ask = {}, openSettings = {}),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { exists("Nordlicht · Voice connected") }
        engine.asksDirectConnections = true
        engine.showStreams(listOf(mockupStream()))
        bridgeVideo.scheduleAtFixedRate(
            {
                frames.ready.set(true)
                engine.lastVideoSink?.onFrameReady(mockupStream().videoId, WIDTH.toUInt(), HEIGHT.toUInt())
            },
            0,
            33,
            TimeUnit.MILLISECONDS,
        )
        return viewModel
    }

    @Test
    fun aTappedStreamPlaysInItsColoursFullScreenThenInTheMiniWindowAndStops() {
        connect()
        await { shown(STREAM_TILE_TEST_TAG) && exists("Tap to watch") }
        Thread.sleep(500)
        assertTrue("nothing is fetched before the tap", engine.watchCalls.isEmpty())
        assertEquals(0, pictures())

        compose.onNodeWithTag(STREAM_TILE_TEST_TAG).performClick()
        await { shown(STREAM_FULL_SCREEN_TEST_TAG) && shown(DIRECT_CONNECTIONS_YES_TEST_TAG) }
        assertTrue("the question says what the other person sees", exists("can see your IP address", substring = true))
        compose.onNodeWithTag(DIRECT_CONNECTIONS_YES_TEST_TAG).performClick()
        await { engine.directAnswers.toList() == listOf(true) }
        awaitColours()

        // Back shrinks it to the mini window, which keeps playing above the other screens.
        Espresso.pressBack()
        await { shown(STREAM_MINI_WINDOW_TEST_TAG) && !shown(STREAM_FULL_SCREEN_TEST_TAG) }
        awaitColours()
        compose.onNodeWithTag(STREAM_MINI_WINDOW_TEST_TAG).performClick()
        await { shown(STREAM_FULL_SCREEN_TEST_TAG) }
        compose.onNodeWithTag(STREAM_COLLAPSE_TEST_TAG).performClick()
        await { shown(STREAM_MINI_WINDOW_TEST_TAG) }

        compose.onNodeWithTag(STREAM_STOP_TEST_TAG).performClick()
        await { !shown(STREAM_MINI_WINDOW_TEST_TAG) && engine.stopCalls.toList() == listOf(MOCKUP_MIRA to StreamKind.SCREEN) }
        await { exists("Tap to watch") && pictures() == 0 }
    }

    /** Waits until the picture on screen is red on the left and blue on the right. */
    private fun awaitColours() = await {
        val bitmap = picture() ?: return@await false
        try {
            val left = pixelInPicture(bitmap, 0.25f)
            val right = pixelInPicture(bitmap, 0.75f)
            Color.red(left) > 200 && Color.blue(left) < 60 && Color.blue(right) > 200 && Color.red(right) < 60
        } finally {
            bitmap.recycle()
        }
    }

    /** A pixel of the 16:9 picture, letterboxed in the middle of the view, at [x] of its width. */
    private fun pixelInPicture(bitmap: Bitmap, x: Float): Int {
        val width = minOf(bitmap.width.toFloat(), bitmap.height * 16f / 9f)
        val left = (bitmap.width - width) / 2f
        return bitmap.getPixel((left + x * width).toInt(), bitmap.height / 2)
    }

    private fun picture(): Bitmap? {
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            bitmap = videoView(compose.activity.window.decorView)?.takeIf { it.isAvailable }?.bitmap
        }
        return bitmap
    }

    private fun videoView(view: View): VideoView? = when (view) {
        is VideoView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { videoView(view.getChildAt(it)) }
        else -> null
    }

    private fun pictures(): Int = compose.onAllNodesWithTag(STREAM_PICTURE_TEST_TAG).fetchSemanticsNodes().size

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    /**
     * I420 pictures (BT.601, limited range): pure red on the left half, pure blue on the right,
     * uploaded the way the engine's are, one plane per texture.
     */
    private class SyntheticFrames : VideoFrames {
        val ready = AtomicBoolean(false)
        private val y = plane(WIDTH, HEIGHT, RED_Y, BLUE_Y)
        private val u = plane(WIDTH / 2, HEIGHT / 2, RED_U, BLUE_U)
        private val v = plane(WIDTH / 2, HEIGHT / 2, RED_V, BLUE_V)

        override fun upload(videoId: Long, textures: IntArray, sizes: IntArray): Long {
            if (videoId != mockupStream().videoId.toLong() || !ready.getAndSet(false)) return 0L
            upload(textures[0], WIDTH, HEIGHT, y)
            upload(textures[1], WIDTH / 2, HEIGHT / 2, u)
            upload(textures[2], WIDTH / 2, HEIGHT / 2, v)
            return packSize(WIDTH, HEIGHT)
        }

        private fun upload(texture: Int, width: Int, height: Int, pixels: ByteBuffer) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, pixels)
        }

        private fun plane(width: Int, height: Int, left: Int, right: Int): ByteBuffer =
            ByteBuffer.allocateDirect(width * height).apply {
                for (row in 0 until height) for (column in 0 until width) put((if (column < width / 2) left else right).toByte())
                rewind()
            }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val WIDTH = 320
        const val HEIGHT = 180

        // Pure red and pure blue in BT.601 limited range.
        const val RED_Y = 81
        const val RED_U = 90
        const val RED_V = 240
        const val BLUE_Y = 41
        const val BLUE_U = 240
        const val BLUE_V = 110
    }
}
