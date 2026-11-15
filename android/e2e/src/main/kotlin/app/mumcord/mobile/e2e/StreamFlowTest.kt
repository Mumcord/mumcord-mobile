// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.video.VideoView
import app.mumcord.mobile.ui.streams.DIRECT_CONNECTIONS_YES_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_COLLAPSE_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_FULL_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_MINI_WINDOW_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_PICTURE_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_ROTATE_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_STOP_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_TILE_TEST_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Watching a screen share (FR-5.70 … FR-5.73, AC-5.22, AC-5.23): a second person on the host
 * (`e2e/stream-peer`) shares the engine's moving test pattern with VP9. The emulator is on
 * mobile data, so with the default settings the tile waits for a tap and nothing is fetched
 * before it (the sharer writes down its viewers when this test asks). The tap opens the stream
 * full screen; the direct connection question is answered with yes; the decoded picture must
 * show the pattern (dark at the top left, bright at the bottom right, moving). It turns to
 * landscape, shrinks to the mini window above the chat, and stops.
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class StreamFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    /** Asks e2e/run.sh to have the sharer write down its viewers now. */
    private fun mark(word: String) {
        Log.i(LOG_TAG, "stream mark $word")
    }

    private fun onScreen(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun pictures(): Int = compose.onAllNodesWithTag(STREAM_PICTURE_TEST_TAG).fetchSemanticsNodes().size

    @Test
    fun aShareWaitsForTheTapOnMobileDataThenPlaysFullScreenInLandscapeAndInTheMiniWindow() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        app.awaitOrFail("the sharer's tile") { app.exists(STREAM_TILE_TEST_TAG) && onScreen(TILE_TITLE) }
        // AC-5.23: mobile data, default settings: the tile asks for a tap and nothing loads.
        app.awaitOrFail("tap to watch on the tile") { onScreen(TAP_TO_WATCH) }
        SystemClock.sleep(UNTOUCHED_MILLIS)
        assertTrue("still waiting for the tap", onScreen(TAP_TO_WATCH))
        assertTrue("no picture before the tap", pictures() == 0)
        mark("before-tap")

        compose.onNodeWithTag(STREAM_TILE_TEST_TAG).performClick()
        val tapped = SystemClock.elapsedRealtime()
        app.awaitOrFail("the full screen") { app.exists(STREAM_FULL_SCREEN_TEST_TAG) }
        // The engine asks before its first direct connection, unless it already has an answer.
        var asked = false
        val first = awaitPattern("the test pattern full screen", poll = {
            if (!asked && app.exists(DIRECT_CONNECTIONS_YES_TEST_TAG)) {
                asked = true
                assertTrue("the question names what the other person sees", onScreen("IP address"))
                compose.onNodeWithTag(DIRECT_CONNECTIONS_YES_TEST_TAG).performClick()
            }
        })
        report("stream: first_frame_ms=${SystemClock.elapsedRealtime() - tapped} asked=$asked")
        val later = awaitPattern("the moving test pattern") { !it.sameAs(first) }
        first.recycle()
        later.recycle()

        // AC-5.22: rotating gives landscape.
        compose.onNodeWithTag(STREAM_ROTATE_TEST_TAG).performClick()
        app.awaitOrFail("landscape") { compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        awaitPattern("the test pattern in landscape").recycle()
        compose.onNodeWithTag(STREAM_ROTATE_TEST_TAG).performClick()

        // Back shrinks it to the mini window, which stays above the chat.
        compose.onNodeWithTag(STREAM_COLLAPSE_TEST_TAG).performClick()
        app.awaitOrFail("the mini window") { app.exists(STREAM_MINI_WINDOW_TEST_TAG) }
        assertFalse(app.exists(STREAM_FULL_SCREEN_TEST_TAG))
        app.openChatTab()
        app.awaitOrFail("the mini window above the chat") { app.exists(STREAM_MINI_WINDOW_TEST_TAG) }
        awaitPattern("the test pattern in the mini window").recycle()

        compose.onNodeWithTag(STREAM_STOP_TEST_TAG).performClick()
        app.awaitOrFail("the mini window closed") { !app.exists(STREAM_MINI_WINDOW_TEST_TAG) && pictures() == 0 }
        mark("after-stop")
        app.disconnect()
    }

    /**
     * Waits until the stream's picture on screen shows the engine's test pattern: a gradient
     * from dark at the top left to bright at the bottom right (BT.601 luma 16 to 216), with a
     * moving white bar. The picture is letterboxed on black, so it is sampled inside its 16:9
     * area. Returns the matching picture; [also] can ask for more (that it moved), and [poll]
     * runs at every look.
     */
    private fun awaitPattern(what: String, poll: () -> Unit = {}, also: (Bitmap) -> Boolean = { true }): Bitmap {
        var found: Bitmap? = null
        app.awaitOrFail(what) {
            poll()
            val bitmap = picture() ?: return@awaitOrFail false
            if (showsPattern(bitmap) && also(bitmap)) {
                found = bitmap
                true
            } else {
                bitmap.recycle()
                false
            }
        }
        return checkNotNull(found)
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

    private fun showsPattern(bitmap: Bitmap): Boolean {
        val width = minOf(bitmap.width.toFloat(), bitmap.height * 16f / 9f)
        val height = width * 9f / 16f
        val left = (bitmap.width - width) / 2f
        val top = (bitmap.height - height) / 2f
        fun luma(fx: Float, fy: Float): Int {
            // The darkest of a small cross, so the moving white bar does not count.
            return listOf(-3, 0, 3).minOf { d ->
                val x = (left + fx * width + d).toInt().coerceIn(0, bitmap.width - 1)
                val y = (top + fy * height).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            }
        }
        val topLeft = luma(0.08f, 0.1f)
        val bottomRight = luma(0.92f, 0.9f)
        val middle = luma(0.5f, 0.5f)
        return bottomRight - topLeft > MIN_GRADIENT && middle in (topLeft + 1) until bottomRight
    }

    private fun report(line: String) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, android.os.Bundle().apply { putString("stream", "\n$line\n") })
    }

    private companion object {
        /** The user name of `e2e/stream-peer`. */
        const val TILE_TITLE = "e2e-sharer's screen"
        const val TAP_TO_WATCH = "Tap to watch"
        /** How long the tile is left alone before the tap, to see that nothing starts by itself. */
        const val UNTOUCHED_MILLIS = 5_000L
        /** Of 0 to 255; the pattern spans about 200 from corner to corner. */
        const val MIN_GRADIENT = 80
        const val LOG_TAG = "MumcordE2E"
    }
}
