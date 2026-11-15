// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.connected.ACTIVITY_ENTRY_TEST_TAG
import app.mumcord.mobile.ui.connected.ACTIVITY_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_ACTIVITY_TEST_TAG
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The activity log (FR-5.94, AC-5.28 without kicks, which the bridge reports as leaving): a
 * second person on the host (`e2e/chat-peer --visitor`) joins the server, moves to "Raid" and
 * leaves whenever this test asks for it, and each step appears in the open log at once, with
 * the filters working on them.
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class ActivityFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    /** Asks e2e/run.sh to let the visitor do this step. */
    private fun visitor(step: String) {
        Log.i(LOG_TAG, "visitor $step")
    }

    private fun onScreen(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun entries(): Int = compose.onAllNodesWithTag(ACTIVITY_ENTRY_TEST_TAG).fetchSemanticsNodes().size

    @Test
    fun aSecondPersonJoiningMovingAndLeavingAppearsInTheOpenLog() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.joinLobby()

        compose.onNodeWithText(TAB_MORE).performClick()
        app.awaitOrFail("the More tab") { app.exists(MORE_ACTIVITY_TEST_TAG) }
        compose.onNodeWithTag(MORE_ACTIVITY_TEST_TAG).performClick()
        app.awaitOrFail("the activity log with the own connection") {
            app.exists(ACTIVITY_SCREEN_TEST_TAG) && onScreen("You connected to")
        }

        visitor("join")
        app.awaitOrFail("the visitor's join") { onScreen("$VISITOR joined") }
        visitor("move")
        app.awaitOrFail("the visitor's move") { onScreen("$VISITOR moved to Raid") }
        visitor("leave")
        app.awaitOrFail("the visitor's leaving") { onScreen("$VISITOR left the server") }
        val all = entries()

        // All of it is coming and going; none of it is a screen share.
        compose.onNodeWithText(FILTER_STREAMS).performClick()
        app.awaitOrFail("the empty stream filter") { onScreen(NOTHING_OF_THIS_KIND) }
        compose.onNodeWithText(FILTER_JOINS).performClick()
        app.awaitOrFail("every entry under joins") { entries() == all && onScreen("$VISITOR left the server") }

        compose.onNodeWithContentDescription(BACK_TO_MORE).performClick()
        app.disconnect()
    }

    private companion object {
        /** The user name of `e2e/chat-peer --visitor`. */
        const val VISITOR = "e2e-visitor"
        const val TAB_MORE = "More"
        const val FILTER_STREAMS = "Streams"
        const val FILTER_JOINS = "Joins"
        const val NOTHING_OF_THIS_KIND = "Nothing of this kind yet."
        const val BACK_TO_MORE = "Back to More"
        const val LOG_TAG = "MumcordE2E"
    }
}
