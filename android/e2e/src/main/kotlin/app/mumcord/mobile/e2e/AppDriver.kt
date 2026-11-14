// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import app.mumcord.mobile.ui.chat.CHAT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connect.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.connected.CHANNEL_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_DISCONNECT_TEST_TAG
import app.mumcord.mobile.ui.servers.CONNECT_ERROR_TEST_TAG
import app.mumcord.mobile.ui.servers.FORM_SAVE_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_ADD_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_PUSH_TO_TALK_TEST_TAG
import org.junit.Assert.assertEquals

/** The steps in the real app that several end-to-end tests share. */
class AppDriver(private val compose: ComposeTestRule, private val server: TestServer) {
    /** First start: the talk mode has to be chosen, nothing is picked for the user. */
    fun chooseTalkMode(mode: String = TALK_MODE_PUSH_TO_TALK_TEST_TAG) {
        awaitOrFail("the talk mode choice") { exists(TALK_MODE_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(TALK_MODE_CONTINUE_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(mode).performClick()
        compose.onNodeWithTag(TALK_MODE_CONTINUE_TEST_TAG).performClick()
        awaitOrFail("the empty server list") { exists(SERVER_ADD_TEST_TAG) }
    }

    fun addServer() {
        compose.onNodeWithTag(SERVER_ADD_TEST_TAG).performClick()
        awaitOrFail("the add server form") { exists(FORM_SAVE_TEST_TAG) }
        compose.onNodeWithContentDescription(FIELD_ADDRESS).performTextReplacement(server.host)
        compose.onNodeWithContentDescription(FIELD_PORT).performTextReplacement(server.port.toString())
        compose.onNodeWithContentDescription(FIELD_USER_NAME).performTextReplacement(USER_NAME)
        compose.onNodeWithTag(FORM_SAVE_TEST_TAG).performScrollTo().performClick()
        awaitOrFail("the saved server in the list") { exists(SERVER_CONNECT_TEST_TAG) }
    }

    fun tapConnect() {
        compose.onNodeWithTag(SERVER_CONNECT_TEST_TAG).performClick()
    }

    /** The server's certificate is unknown to freshly installed app data: trust it. */
    fun trustCertificate() {
        awaitOrFail("the certificate question") { exists(CERTIFICATE_TRUST_TEST_TAG) }
        compose.onNodeWithTag(CERTIFICATE_TRUST_TEST_TAG).performClick()
    }

    fun awaitChannels(what: String = "the channel list") {
        awaitOrFail(what) { connected() }
        assertSeededChannels()
    }

    /**
     * Everybody arrives in the root channel, where the test server lets nobody talk. In the
     * Lobby talking is allowed, so there the engine wants the microphone.
     */
    fun joinLobby() {
        compose.onNode(hasTestTag(CHANNEL_ROW_TEST_TAG) and hasText(LOBBY)).performClick()
    }

    /** Opens the Chat tab: the chat of the channel the user is in. */
    fun openChatTab() {
        // With unread messages the tab is named "Chat, 2 unread messages" for screen readers.
        compose.onNode(hasText(TAB_CHAT) or hasContentDescription("$TAB_CHAT, ", substring = true)).performClick()
        awaitOrFail("the Chat tab") { exists(CHAT_SCREEN_TEST_TAG) }
    }

    fun connected(): Boolean = channelNames().size > TestServer.SEEDED_CHANNELS.size

    /** Leaves the server with the leave button of the voice bar, which is on every tab. */
    fun leave() {
        compose.onNodeWithContentDescription(LEAVE_SERVER).performClick()
        awaitOrFail("the server list after leaving") { exists(SERVER_CONNECT_TEST_TAG) }
    }

    /** Leaves the server through the More tab. */
    fun disconnect() {
        compose.onNodeWithText(TAB_MORE).performClick()
        awaitOrFail("the More tab") { exists(MORE_DISCONNECT_TEST_TAG) }
        compose.onNodeWithTag(MORE_DISCONNECT_TEST_TAG).performClick()
        awaitOrFail("the server list after disconnecting") { exists(SERVER_CONNECT_TEST_TAG) }
    }

    /** The first row is the root channel, whose name is the server's own; the rest is seeded. */
    fun assertSeededChannels() {
        assertEquals(TestServer.SEEDED_CHANNELS, channelNames().drop(1).toSet())
    }

    private fun channelNames(): List<String> =
        compose.onAllNodesWithTag(CHANNEL_ROW_TEST_TAG).fetchSemanticsNodes().mapNotNull { row ->
            row.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        }

    fun exists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits until [condition] holds. Fails at once with the app's own message when connecting
     * failed, and with what was waited for when time runs out, instead of hanging.
     */
    fun awaitOrFail(what: String, condition: () -> Boolean) = await(what) {
        connectError()?.let { message ->
            throw AssertionError("Connecting to $server failed while waiting for $what: $message")
        }
        condition()
    }

    /** Waits until [condition] holds, also while an error is shown (the error may be the point). */
    fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("Timed out after ${TIMEOUT_MILLIS / 1000} s waiting for $what. On the screen:\n${screen()}")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** What the app shows, window by window, to diagnose a failure from the test output. */
    private fun screen(): String = runCatching {
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") { index ->
            compose.onAllNodes(isRoot())[index].printToString()
        }
    }.getOrElse { error -> "(the screen could not be read: $error)" }

    /** The texts of the error banner on the server list, or null while there is none. */
    fun connectError(): String? =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(CONNECT_ERROR_TEST_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")

    companion object {
        const val USER_NAME = "e2e-phone"
        const val LOBBY = "Lobby"
        /**
         * The longest any step may take. Generous, because the hosted CI emulator is several
         * times slower than a developer's; a wait ends as soon as what it waits for is there.
         */
        const val TIMEOUT_MILLIS = 60_000L
        private const val TAB_CHAT = "Chat"
        private const val TAB_MORE = "More"
        private const val LEAVE_SERVER = "Leave server"

        // The labels of the form fields, which is how a screen reader finds them too.
        private const val FIELD_ADDRESS = "Address"
        private const val FIELD_PORT = "Port"
        private const val FIELD_USER_NAME = "User name"
        private const val POLL_MILLIS = 100L
    }
}
