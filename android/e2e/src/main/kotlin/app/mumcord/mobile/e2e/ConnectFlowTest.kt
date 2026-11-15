// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connect.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.connected.CHANNEL_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * First start, the server list and connecting in the real app with the real engine against the
 * local test server. Expects fresh app data (e2e/run.sh clears it before every scenario), so
 * the setup has not run, no server is saved and the server's certificate is unknown, and local
 * network access already granted (the scenario "granted" of e2e/run.sh).
 */
class ConnectFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Test
    fun firstStartAddAServerConnectTrustTheCertificateSeeTheChannelsAndConnectAgainWithoutBeingAsked() {
        app.chooseTalkMode()
        app.addServer()

        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        // Everybody arrives in the root channel; tapping Lobby moves the user there.
        compose.onNode(hasTestTag(CHANNEL_ROW_TEST_TAG) and hasText(LOBBY)).performClick()
        app.awaitOrFail("the own user in $LOBBY") {
            ownChannel() == LOBBY && personBelow(LOBBY) == AppDriver.USER_NAME
        }

        app.disconnect()

        // The certificate was trusted above and is remembered: no second question.
        app.tapConnect()
        app.awaitOrFail("the channel list on the second connect") {
            app.exists(CERTIFICATE_TRUST_TEST_TAG) || app.connected()
        }
        assertFalse("Asked again about a certificate that is already trusted", app.exists(CERTIFICATE_TRUST_TEST_TAG))
        app.assertSeededChannels()

        app.disconnect()
    }

    /** The name of the channel marked "you are here". */
    private fun ownChannel(): String? =
        compose.onAllNodesWithTag(CHANNEL_ROW_TEST_TAG).fetchSemanticsNodes()
            .map { row -> row.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .firstOrNull { OWN_CHANNEL_MARK in it }
            ?.firstOrNull()

    /** The name of the first person listed directly below the channel [name]. */
    private fun personBelow(name: String): String? {
        val rows = compose.onAllNodes(hasTestTag(CHANNEL_ROW_TEST_TAG) or hasTestTag(PERSON_ROW_TEST_TAG))
            .fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.TestTag) to it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        val index = rows.indexOfFirst { it.first == CHANNEL_ROW_TEST_TAG && it.second == name }
        return rows.getOrNull(index + 1)?.takeIf { index >= 0 && it.first == PERSON_ROW_TEST_TAG }?.second
    }

    private companion object {
        const val LOBBY = "Lobby"
        const val OWN_CHANNEL_MARK = "you are here"
    }
}
