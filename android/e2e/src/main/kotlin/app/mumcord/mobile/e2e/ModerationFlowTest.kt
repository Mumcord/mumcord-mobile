// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.ui.connected.BAN_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.connected.KICK_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_BAN_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_KICK_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_MOVE_TEST_TAG
import app.mumcord.mobile.ui.connected.MOVE_CHOICE_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_SHEET_TEST_TAG
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Moderation from the person sheet in the real app (AC-5.27), connected as the local test
 * server's SuperUser, whose password e2e/run.sh passes as the runner argument `superuser` (it
 * is not written to any file or log). The person moderated is `e2e/moderation-target.py` on the
 * host, which reports what the server did to it; e2e/run.sh checks that report.
 *
 * A ban also covers the address, and every person of the test server comes from the same one
 * (the host). So the ban lasts only [BAN_SECONDS] (`TestSettings.banSeconds`), its scenario is
 * the last of a run, and the test waits until the person is let back in.
 * Expects fresh app data with local network access and the microphone granted.
 */
class ModerationFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @After
    fun banNormally() {
        TestSettings.banSeconds = null
    }

    /**
     * Server mute is not part of it: a standard server answers the SuperUser's rights only for
     * the root channel, and mute is offered only where the server confirmed it for the person's
     * channel (the instrumented tests cover it with a fake engine).
     */
    @Test
    fun moveAndKickReachTheOtherPerson() {
        connectAsAdmin()
        openTargetSheet()

        compose.onNodeWithTag(MODERATION_MOVE_TEST_TAG).performScrollTo().performClick()
        app.await("the channel picker") { app.exists(MOVE_CHOICE_TEST_TAG) }
        compose.onNode(hasTestTag(MOVE_CHOICE_TEST_TAG) and hasText(RAID)).performScrollTo().performClick()
        app.await("the sheets to close after the move") { !app.exists(PERSON_SHEET_TEST_TAG) }

        openTargetSheet()
        compose.onNodeWithTag(MODERATION_KICK_TEST_TAG).performScrollTo().performClick()
        app.await("the kick question") { app.exists(KICK_CONFIRM_TEST_TAG) }
        compose.onNodeWithContentDescription(REASON).performTextReplacement(KICK_REASON)
        compose.onNodeWithTag(KICK_CONFIRM_TEST_TAG).performScrollTo().performClick()
        app.await("the sheets to close after the kick") { !app.exists(KICK_CONFIRM_TEST_TAG) }
        // A kick does not keep anybody out: the person is back at once.
        app.await("the person back after the kick") { targetShown() }
    }

    @Test
    fun aShortBanRemovesThePersonUntilItEnds() {
        TestSettings.banSeconds = BAN_SECONDS
        connectAsAdmin()
        openTargetSheet()

        compose.onNodeWithTag(MODERATION_BAN_TEST_TAG).performScrollTo().performClick()
        app.await("the ban form with what the server said about the person") { app.exists(BAN_CONFIRM_TEST_TAG) }
        compose.onNodeWithContentDescription(REASON).performTextReplacement(BAN_REASON)
        compose.onNodeWithTag(BAN_CONFIRM_TEST_TAG).performScrollTo().performClick()
        app.await("the person to be gone after the ban") { !app.exists(BAN_CONFIRM_TEST_TAG) && !targetShown() }
        // Until the ban ends the server refuses the person; then it lets them in again, so the
        // shared address is free for whatever runs next.
        app.await("the person back once the ban ended") { targetShown() }
    }

    private fun connectAsAdmin() {
        val password = checkNotNull(InstrumentationRegistry.getArguments().getString(SUPERUSER_ARGUMENT)) {
            "e2e/run.sh passes the SuperUser password as the runner argument $SUPERUSER_ARGUMENT"
        }
        app.chooseTalkMode()
        app.addServer(userName = SUPERUSER, password = password)
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the other person $TARGET in the channel list") { targetShown() }
        // The server tells the rights of a channel once the user is in it; the other person
        // is in the Lobby.
        app.joinLobby()
    }

    private fun openTargetSheet() {
        app.awaitOrFail("the other person $TARGET in the channel list") { targetShown() }
        compose.onNode(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(TARGET)).performScrollTo().performClick()
        app.await("the moderation section of the person sheet") { app.exists(MODERATION_KICK_TEST_TAG) }
    }

    private fun targetShown(): Boolean =
        compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(TARGET)).fetchSemanticsNodes().isNotEmpty()


    private companion object {
        const val SUPERUSER_ARGUMENT = "superuser"
        const val SUPERUSER = "SuperUser"
        const val TARGET = "e2e-target"
        const val RAID = "Raid"
        const val REASON = "Reason"
        // e2e/run.sh looks for these in the other person's report.
        const val KICK_REASON = "e2e kick"
        const val BAN_REASON = "e2e ban"
        const val BAN_SECONDS = 15u
    }
}
