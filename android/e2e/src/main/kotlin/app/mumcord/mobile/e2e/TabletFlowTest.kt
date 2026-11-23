// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.platform.video.VideoView
import app.mumcord.mobile.ui.chat.CHAT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_SEND_TEST_TAG
import app.mumcord.mobile.ui.connected.MAIN_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.connected.NAVIGATION_RAIL_TEST_TAG
import app.mumcord.mobile.ui.connected.SIDE_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.streams.DIRECT_CONNECTIONS_YES_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_CHAT_STRIP_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_COLUMN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_FULL_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_STOP_TEST_TAG
import app.mumcord.mobile.ui.streams.STREAM_TILE_TEST_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/**
 * The tablet layout (AC-5.31, FR-5.71) in the real app: the emulator's screen is made the size
 * of a 10 inch tablet (800 x 1280 dp) before the app starts and put back afterwards, also after a
 * failure (e2e/run.sh puts it back once more when the scenario's shell ends). Channels and the
 * chat are side by side and the chat is usable; the share of `e2e/stream-peer` opens in the
 * right column with the chat strip below while the channels stay visible, and stops again.
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class TabletFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val tabletScreen = TabletScreen()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    @Test
    fun channelsAndChatSideBySideAndAStreamPlaysInTheRightColumnWithTheChatStripBelow() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        // AC-5.31: the rail, the channels on the left and the chat on the right, side by side.
        app.awaitOrFail("two columns") {
            app.exists(NAVIGATION_RAIL_TEST_TAG) && app.exists(SIDE_COLUMN_TEST_TAG) && app.exists(CHAT_SCREEN_TEST_TAG)
        }
        assertTrue("the chat is right of the channels", bounds(SIDE_COLUMN_TEST_TAG).right <= bounds(MAIN_COLUMN_TEST_TAG).left + 1f)
        compose.onNode(hasSetTextAction()).performTextInput(MESSAGE)
        compose.onNodeWithTag(CHAT_SEND_TEST_TAG).performClick()
        app.awaitOrFail("the sent message beside the channels") {
            compose.onAllNodes(hasText(MESSAGE)).fetchSemanticsNodes().isNotEmpty() && app.exists(SIDE_COLUMN_TEST_TAG)
        }

        // FR-5.71: on a tablet the stream fills the right column, with the chat strip below.
        app.awaitOrFail("the sharer's tile") { app.exists(STREAM_TILE_TEST_TAG) }
        compose.onNodeWithTag(STREAM_TILE_TEST_TAG).performClick()
        var asked = false
        app.awaitOrFail("the stream's picture in the right column") {
            if (!asked && app.exists(DIRECT_CONNECTIONS_YES_TEST_TAG)) {
                asked = true
                compose.onNodeWithTag(DIRECT_CONNECTIONS_YES_TEST_TAG).performClick()
            }
            app.exists(STREAM_COLUMN_TEST_TAG) && showsPicture()
        }
        assertTrue("the chat strip is below the stream", app.exists(STREAM_CHAT_STRIP_TEST_TAG))
        assertTrue("the channels stay on the left", app.exists(SIDE_COLUMN_TEST_TAG) && app.exists(NAVIGATION_RAIL_TEST_TAG))
        assertFalse("nothing covers the whole screen", app.exists(STREAM_FULL_SCREEN_TEST_TAG))
        assertTrue("the stream is in the right column", bounds(STREAM_COLUMN_TEST_TAG).left >= bounds(SIDE_COLUMN_TEST_TAG).right - 1f)
        // The picture keeps coming while the chat is next to it.
        SystemClock.sleep(PLAYING_MILLIS)
        assertTrue("still playing", showsPicture())

        compose.onNodeWithTag(STREAM_STOP_TEST_TAG).performClick()
        app.awaitOrFail("the chat back in the right column") { !app.exists(STREAM_COLUMN_TEST_TAG) && app.exists(CHAT_SCREEN_TEST_TAG) }
        app.disconnect()
    }

    private fun bounds(tag: String): Rect = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().first().boundsInRoot

    /** The stream's video view has a picture with more than one colour. */
    private fun showsPicture(): Boolean {
        var shows = false
        compose.runOnUiThread {
            val bitmap = videoView(compose.activity.window.decorView)?.takeIf { it.isAvailable }?.bitmap ?: return@runOnUiThread
            val first = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
            shows = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2) != first ||
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 4) != first
            bitmap.recycle()
        }
        return shows
    }

    private fun videoView(view: View): VideoView? = when (view) {
        is VideoView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { videoView(view.getChildAt(it)) }
        else -> null
    }

    /**
     * Gives the emulator the screen of a tablet before the app starts and the phone's own screen
     * back afterwards, whatever happened in between.
     */
    class TabletScreen : ExternalResource() {
        override fun before() {
            shell("wm size $TABLET_PIXELS")
            shell("wm density $TABLET_DENSITY")
        }

        override fun after() {
            shell("wm size reset")
            shell("wm density reset")
        }

        private fun shell(command: String) {
            val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            // Reading to the end waits until the command finished.
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        }
    }

    private companion object {
        /** 800 x 1280 dp: a 10 inch tablet in portrait. */
        const val TABLET_PIXELS = "1600x2560"
        const val TABLET_DENSITY = 320
        const val MESSAGE = "Hello from the tablet"
        const val PLAYING_MILLIS = 2_000L
    }
}
