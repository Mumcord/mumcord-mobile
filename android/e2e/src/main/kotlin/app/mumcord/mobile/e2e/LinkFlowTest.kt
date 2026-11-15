// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connected.CHANNEL_ROW_TEST_TAG
import app.mumcord.mobile.ui.link.LINK_CONNECT_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * A `mumble://` link to the local test server (AC-5.29), opened the way another app opens it:
 * the system hands the running app a VIEW intent. The app shows where the link leads and
 * connects only after the confirmation, straight into the channel the link names. Expects
 * fresh app data with local network access granted.
 */
class LinkFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()

    @Test
    fun aLinkShowsItsTargetAndConnectsIntoItsChannelOnlyAfterTheConfirmation() {
        app.chooseTalkMode()
        val server = requireServer.server
        val link = "mumble://${AppDriver.USER_NAME}@${server.host}:${server.port}/${AppDriver.LOBBY}"
        phone.shell("am start -a android.intent.action.VIEW -d $link $APP_PACKAGE")

        app.awaitOrFail("the link's confirmation") { app.exists(LINK_CONNECT_TEST_TAG) }
        compose.onNode(hasText("${server.host}:${server.port}")).assertExists()
        compose.onNode(hasText(AppDriver.LOBBY)).assertExists()
        // Nothing happens before the user confirms.
        Thread.sleep(CONFIRMATION_PAUSE_MILLIS)
        assertFalse("Connected before the link was confirmed", app.connected())

        compose.onNodeWithTag(LINK_CONNECT_TEST_TAG).performClick()
        app.trustCertificate()
        app.awaitChannels()
        app.awaitOrFail("the own user in ${AppDriver.LOBBY}, the channel of the link") {
            ownChannel() == AppDriver.LOBBY
        }

        app.disconnect()
    }

    /** The name of the channel marked "you are here". */
    private fun ownChannel(): String? =
        compose.onAllNodesWithTag(CHANNEL_ROW_TEST_TAG).fetchSemanticsNodes()
            .map { row -> row.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .firstOrNull { OWN_CHANNEL_MARK in it }
            ?.firstOrNull()

    private companion object {
        const val APP_PACKAGE = "app.mumcord.mobile"
        const val OWN_CHANNEL_MARK = "you are here"
        /** Long enough for a connection to the local server to start if the app did not wait. */
        const val CONFIRMATION_PAUSE_MILLIS = 2_000L
    }
}
