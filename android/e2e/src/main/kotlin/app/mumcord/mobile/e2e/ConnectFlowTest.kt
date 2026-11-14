// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connect.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.servers.CONNECT_ERROR_TEST_TAG
import app.mumcord.mobile.ui.servers.FORM_SAVE_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_ADD_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_PUSH_TO_TALK_TEST_TAG
import app.mumcord.mobile.ui.slice.CHANNELS_DISCONNECT_TEST_TAG
import app.mumcord.mobile.ui.slice.CHANNEL_ROW_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * First start, the server list and connecting in the real app with the real engine against the
 * local test server. Expects freshly installed app data (e2e/run.sh installs the app anew), so
 * the setup has not run, no server is saved and the server's certificate is unknown.
 */
class ConnectFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstStartAddAServerConnectTrustTheCertificateSeeTheChannelsAndConnectAgainWithoutBeingAsked() {
        // First start: the talk mode has to be chosen, nothing is picked for the user.
        awaitOrFail("the talk mode choice") { exists(TALK_MODE_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(TALK_MODE_CONTINUE_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(TALK_MODE_PUSH_TO_TALK_TEST_TAG).performClick()
        compose.onNodeWithTag(TALK_MODE_CONTINUE_TEST_TAG).performClick()

        awaitOrFail("the empty server list") { exists(SERVER_ADD_TEST_TAG) }
        addServer()
        awaitOrFail("the saved server in the list") { exists(SERVER_CONNECT_TEST_TAG) }

        compose.onNodeWithTag(SERVER_CONNECT_TEST_TAG).performClick()
        awaitOrFail("the certificate question") { exists(CERTIFICATE_TRUST_TEST_TAG) }
        compose.onNodeWithTag(CERTIFICATE_TRUST_TEST_TAG).performClick()
        awaitOrFail("the channel list") { channelNames().size > TestServer.SEEDED_CHANNELS.size }
        assertSeededChannels()

        compose.onNodeWithTag(CHANNELS_DISCONNECT_TEST_TAG).performClick()
        awaitOrFail("the server list after disconnecting") { exists(SERVER_CONNECT_TEST_TAG) }

        // The certificate was trusted above and is remembered: no second question.
        compose.onNodeWithTag(SERVER_CONNECT_TEST_TAG).performClick()
        awaitOrFail("the channel list on the second connect") {
            exists(CERTIFICATE_TRUST_TEST_TAG) || channelNames().size > TestServer.SEEDED_CHANNELS.size
        }
        assertFalse("Asked again about a certificate that is already trusted", exists(CERTIFICATE_TRUST_TEST_TAG))
        assertSeededChannels()

        compose.onNodeWithTag(CHANNELS_DISCONNECT_TEST_TAG).performClick()
        awaitOrFail("the server list after disconnecting") { exists(SERVER_CONNECT_TEST_TAG) }
    }

    private fun addServer() {
        val server = requireServer.server
        compose.onNodeWithTag(SERVER_ADD_TEST_TAG).performClick()
        awaitOrFail("the add server form") { exists(FORM_SAVE_TEST_TAG) }
        compose.onNodeWithContentDescription(FIELD_ADDRESS).performTextReplacement(server.host)
        compose.onNodeWithContentDescription(FIELD_PORT).performTextReplacement(server.port.toString())
        compose.onNodeWithContentDescription(FIELD_USER_NAME).performTextReplacement(USER_NAME)
        compose.onNodeWithTag(FORM_SAVE_TEST_TAG).performScrollTo().performClick()
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

    /** The texts of the error banner on the server list, or null while there is none. */
    private fun connectError(): String? =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(CONNECT_ERROR_TEST_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")

    private companion object {
        const val USER_NAME = "e2e-phone"

        // The labels of the form fields, which is how a screen reader finds them too.
        const val FIELD_ADDRESS = "Address"
        const val FIELD_PORT = "Port"
        const val FIELD_USER_NAME = "User name"
        const val TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 100L
    }
}
