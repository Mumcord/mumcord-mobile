// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.slice.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.slice.CHANNELS_DISCONNECT_TEST_TAG
import app.mumcord.mobile.ui.slice.CHANNEL_ROW_TEST_TAG
import app.mumcord.mobile.ui.slice.CONNECT_ACTION_TEST_TAG
import app.mumcord.mobile.ui.slice.CONNECT_ADDRESS_TEST_TAG
import app.mumcord.mobile.ui.slice.CONNECT_ERROR_TEST_TAG
import app.mumcord.mobile.ui.slice.CONNECT_PORT_TEST_TAG
import app.mumcord.mobile.ui.slice.CONNECT_USERNAME_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * The first slice in the real app with the real engine against the local test server. Expects
 * freshly installed app data (e2e/run.sh installs the app anew), so the server is unknown.
 */
class ConnectFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectTrustTheCertificateSeeTheChannelsAndConnectAgainWithoutBeingAsked() {
        connect()
        awaitOrFail("the certificate question") { exists(CERTIFICATE_TRUST_TEST_TAG) }
        compose.onNodeWithTag(CERTIFICATE_TRUST_TEST_TAG).performClick()
        awaitOrFail("the channel list") { channelNames().size > TestServer.SEEDED_CHANNELS.size }
        assertSeededChannels()

        compose.onNodeWithTag(CHANNELS_DISCONNECT_TEST_TAG).performClick()
        awaitOrFail("the connect form after disconnecting") { exists(CONNECT_ACTION_TEST_TAG) }

        // The certificate was trusted above and is remembered: no second question.
        connect()
        awaitOrFail("the channel list on the second connect") {
            exists(CERTIFICATE_TRUST_TEST_TAG) || channelNames().size > TestServer.SEEDED_CHANNELS.size
        }
        assertFalse("Asked again about a certificate that is already trusted", exists(CERTIFICATE_TRUST_TEST_TAG))
        assertSeededChannels()

        compose.onNodeWithTag(CHANNELS_DISCONNECT_TEST_TAG).performClick()
        awaitOrFail("the connect form after disconnecting") { exists(CONNECT_ACTION_TEST_TAG) }
    }

    private fun connect() {
        val server = requireServer.server
        compose.onNodeWithTag(CONNECT_ADDRESS_TEST_TAG).performTextReplacement(server.host)
        compose.onNodeWithTag(CONNECT_PORT_TEST_TAG).performTextReplacement(server.port.toString())
        compose.onNodeWithTag(CONNECT_USERNAME_TEST_TAG).performTextReplacement(USER_NAME)
        compose.onNodeWithTag(CONNECT_ACTION_TEST_TAG).performClick()
    }

    /** The first row is the root channel, whose name is the server's own; the rest is seeded. */
    private fun assertSeededChannels() {
        assertEquals(TestServer.SEEDED_CHANNELS, channelNames().drop(1).toSet())
    }

    private fun channelNames(): List<String> =
        compose.onAllNodesWithTag(CHANNEL_ROW_TEST_TAG).fetchSemanticsNodes().mapNotNull { row ->
            row.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        }

    private fun exists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits until [condition] holds. Fails at once with the app's own message when connecting
     * failed, and with what was waited for when time runs out, instead of hanging.
     */
    private fun awaitOrFail(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (!condition()) {
            connectError()?.let { message ->
                throw AssertionError("Connecting to ${requireServer.server} failed while waiting for $what: $message")
            }
            if (System.nanoTime() > deadline) {
                throw AssertionError("Timed out after ${TIMEOUT_MILLIS / 1000} s waiting for $what")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** The texts of the error card on the connect form, or null while there is none. */
    private fun connectError(): String? =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(CONNECT_ERROR_TEST_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")

    private companion object {
        const val USER_NAME = "e2e-phone"
        const val TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 100L
    }
}
