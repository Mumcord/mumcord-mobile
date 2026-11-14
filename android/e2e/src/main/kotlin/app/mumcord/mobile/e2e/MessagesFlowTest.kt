// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Log
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.platform.audio.AudioProbe
import app.mumcord.mobile.ui.chat.CHAT_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_SENDER_ONLY_TEST_TAG
import app.mumcord.mobile.ui.messages.MESSAGES_SCREEN_TEST_TAG
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Private messages become system notifications only while connected (AC-5.18): a second person
 * on the host (`e2e/chat-peer --private`) writes to the phone whenever this test asks for it.
 * A message that arrives while another tab is shown is in the notification shade with sender
 * and text, and a tap opens the conversation; while the conversation is open there is no
 * notification; with "Show sender only" the text is absent; and after disconnecting the
 * notifications are gone and nothing new appears.
 * Expects fresh app data with local network access, the microphone and notifications granted.
 */
class MessagesFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()

    @Before
    fun silenceTheMicrophone() = AudioProbe.replaceMicrophone(AudioProbe.Signal.Silence)

    @After
    fun giveTheMicrophoneBack() = AudioProbe.replaceMicrophone(null)

    /** Asks e2e/run.sh to let the second person send the private message with this number. */
    private fun askForMessage(number: Int) {
        Log.i(LOG_TAG, "pm send $number")
    }

    /** A view of a notification in the open shade with exactly [text]; the app behind the shade does not count. */
    private fun notification(text: String, timeoutMillis: Long = SystemDialog.TIMEOUT_MILLIS) =
        phone.inShade(text, timeoutMillis, systemOnly = true)

    private fun onScreen(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun openTab(name: String) {
        // A tab with unread messages is named "<tab>, 2 unread messages" for screen readers.
        compose.onNode(hasText(name) or hasContentDescription("$name, ", substring = true)).performClick()
    }

    @Test
    fun aPrivateMessageIsNotifiedOnlyWhileConnectedAndWithoutItsTextWhenOnlyTheSenderMayShow() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.joinLobby()

        // The Channels tab is shown: a private message is announced by the system.
        askForMessage(1)
        phone.openShade()
        assertNotNull("The notification names the sender", notification(PEER_NAME))
        val first = checkNotNull(notification(text(1))) { "the notification does not show the message's text" }

        // Tapping it opens the conversation.
        first.click()
        app.awaitOrFail("the conversation after tapping the notification") {
            app.exists(CHAT_SCREEN_TEST_TAG) && onScreen(text(1))
        }

        // The conversation is on screen: the next message appears there and is not announced.
        askForMessage(2)
        app.awaitOrFail("the second message in the open conversation") { onScreen(text(2)) }
        phone.openShade()
        assertNull("No notification for the chat that is on screen", notification(text(2), GONE_TIMEOUT_MILLIS))
        assertNull("Opening the conversation removed its notification", notification(text(1), GONE_TIMEOUT_MILLIS))
        phone.closeShade()

        // "Show sender only": the next notification names the sender and leaves the text out.
        compose.onNodeWithContentDescription(BACK_TO_CONVERSATIONS).performClick()
        app.awaitOrFail("the conversation list") { app.exists(MESSAGES_SCREEN_TEST_TAG) }
        openTab(TAB_MORE)
        app.awaitOrFail("the More tab") { app.exists(MORE_SENDER_ONLY_TEST_TAG) }
        compose.onNodeWithTag(MORE_SENDER_ONLY_TEST_TAG).performClick()
        askForMessage(3)
        phone.openShade()
        assertNotNull("The notification names the sender", notification(PEER_NAME))
        assertNotNull("It says that there is a message", notification(NEW_MESSAGE))
        assertNull("With \"Show sender only\" the text is absent", notification(text(3), GONE_TIMEOUT_MILLIS))
        phone.closeShade()

        // Disconnecting removes the notification, and a later private message produces nothing.
        // The More tab is open already; the leave button of the voice bar is on every tab.
        app.leave()
        askForMessage(4)
        Thread.sleep(AFTER_DISCONNECT_MILLIS)
        phone.openShade()
        assertNull("No message notification after disconnecting", notification(PEER_NAME, GONE_TIMEOUT_MILLIS))
        assertNull("No message notification after disconnecting", notification(NEW_MESSAGE, GONE_TIMEOUT_MILLIS))
        assertNull("No message notification after disconnecting", notification(text(4), GONE_TIMEOUT_MILLIS))
        phone.closeShade()
    }

    private companion object {
        /** The user name of `e2e/chat-peer`. */
        const val PEER_NAME = "e2e-peer"
        const val NEW_MESSAGE = "New message"
        const val BACK_TO_CONVERSATIONS = "Back to the conversations"
        const val TAB_MORE = "More"
        const val LOG_TAG = "MumcordE2E"
        const val GONE_TIMEOUT_MILLIS = 2_000L

        /** Time for a message that the second person tried to send after the phone left. */
        const val AFTER_DISCONNECT_MILLIS = 5_000L

        /** What `e2e/chat-peer --private` writes as its message with that number. */
        fun text(number: Int) = "Private note $number"
    }
}
