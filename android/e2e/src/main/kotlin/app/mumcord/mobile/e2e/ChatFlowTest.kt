// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.chat.CHAT_IMAGE_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_LIST_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_MESSAGE_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_PENDING_TEST_TAG
import app.mumcord.mobile.ui.chat.CHAT_SEND_TEST_TAG
import app.mumcord.mobile.ui.chat.IMAGE_VIEWER_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The channel chat in the real app against the local test server, with two more people on the
 * host (started by e2e/run.sh): `e2e/chat-peer`, the bridge itself writing as the desktop app
 * does, and `e2e/raw-chat.py`, a foreign client that sends raw script and markup.
 *
 * The first test expects fresh app data; the second one runs after it with the same app data
 * and a new app process, which is what a restart of the app is. e2e/run.sh also checks the
 * second person's report of what the phone wrote.
 */
class ChatFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Test
    fun messagesOfOthersAppearAndThePhonesMessagesReachThem() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        for (person in listOf(PEER_NAME, RAW_NAME)) {
            app.awaitOrFail("$person in the channel list") {
                compose.onAllNodes(hasTestTag(PERSON_ROW_TEST_TAG) and hasText(person)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        // The others wait in the Lobby and write once the phone is there.
        app.joinLobby()
        app.openChatTab()

        // AC-5.16: text, a link, the address of a GIF and a picture of the second person.
        awaitMessage("the second person's text", hasText(PEER_TEXT))
        awaitMessage("the second person's link", hasText(PEER_LINK_TEXT))
        awaitMessage("the second person's GIF link", hasText(PEER_GIF))
        awaitMessage("the second person's picture", hasTestTag(CHAT_IMAGE_TEST_TAG))
        // The picture takes taps only once it is loaded, which on a slow emulator is later
        // than its place in the list appears: tapped again until it opens.
        app.await("the picture on the whole screen") {
            try {
                compose.onNodeWithTag(CHAT_IMAGE_TEST_TAG).performClick()
                compose.waitUntil(OPEN_MILLIS) { app.exists(IMAGE_VIEWER_TEST_TAG) }
            } catch (_: AssertionError) {
                // The picture's place is gone: the viewer covers it, or the list just moved.
            } catch (_: ComposeTimeoutException) {
                // Not open yet; the next round taps again.
            }
            app.exists(IMAGE_VIEWER_TEST_TAG)
        }
        Espresso.pressBack()
        app.await("the chat after closing the picture") { !app.exists(IMAGE_VIEWER_TEST_TAG) }

        // AC-5.16: the message that mentions the phone's user is highlighted.
        awaitMessage(
            "the highlighted mention of the phone's user",
            hasTestTag(CHAT_MESSAGE_TEST_TAG) and hasStateDescription(MENTIONS_YOU) and hasText(MENTION_TEXT, substring = true),
        )

        // AC-5.17: raw script and markup of a foreign client is shown as harmless text. The
        // escaped part must come out as the characters it stands for, not as formatting.
        awaitMessage("the raw message's text", hasText(RAW_MARKER, substring = true))
        awaitMessage("the raw message's escaped markup as plain characters", hasText(RAW_ESCAPED, substring = true))
        awaitMessage("the text inside the raw message's markup", hasText(RAW_BOLD, substring = true))

        // The other way round: text, a link, the address of a GIF and a picture from the phone.
        send(PHONE_TEXT)
        send(PHONE_LINK_TEXT)
        send(PHONE_GIF)
        val sentBefore = ownImages()
        compose.runOnUiThread {
            ViewModelProvider(compose.activity, AppViewModel.Factory)[AppViewModel::class.java]
                .chat.sendImage(picture(), "image/png")
        }
        app.await("the phone's picture in the chat") { ownImages() > sentBefore && !app.exists(CHAT_PENDING_TEST_TAG) }
        awaitMessage("the phone's own text", hasText(PHONE_TEXT))

        app.disconnect()
    }

    @Test
    fun theHistoryIsStillThereAfterTheAppWasKilledAndStartedAgain() {
        // The same app data, a new process: the server is saved and its certificate trusted.
        app.awaitOrFail("the saved server after the restart") { app.exists(SERVER_CONNECT_TEST_TAG) }
        app.tapConnect()
        app.awaitChannels()
        app.joinLobby()
        app.openChatTab()

        // AC-5.19: what was received and what was sent before the restart.
        awaitMessage("the second person's text from before the restart", hasText(PEER_TEXT))
        awaitMessage("the phone's own text from before the restart", hasText(PHONE_TEXT))
        awaitMessage("the phone's own link from before the restart", hasText(PHONE_LINK_TEXT))
        awaitMessage("the raw message from before the restart", hasText(RAW_MARKER, substring = true))
        awaitMessage("a picture from before the restart", hasTestTag(CHAT_IMAGE_TEST_TAG))

        app.disconnect()
    }

    /** Waits for a message in the chat, scrolling the list to it when it is not on screen. */
    private fun awaitMessage(what: String, matcher: SemanticsMatcher) = app.await(what) {
        try {
            compose.onNodeWithTag(CHAT_LIST_TEST_TAG).performScrollToNode(matcher)
            true
        } catch (_: AssertionError) {
            false
        }
    }

    private fun send(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithTag(CHAT_SEND_TEST_TAG).performClick()
        awaitMessage("the sent message \"$text\"", hasText(text))
        app.await("\"$text\" to be out") { !app.exists(CHAT_PENDING_TEST_TAG) }
    }

    private fun ownImages(): Int = compose.onAllNodes(hasTestTag(CHAT_IMAGE_TEST_TAG)).fetchSemanticsNodes().size

    /** A small picture, as the photo picker would hand it to the app. */
    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        /** How long one tap on the picture is given to open it before it is tapped again. */
        const val OPEN_MILLIS = 3_000L

        /** The names `e2e/chat-peer` and `e2e/raw-chat.py` connect with, and what they write. */
        const val PEER_NAME = "e2e-peer"
        const val RAW_NAME = "e2e-raw"
        const val PEER_TEXT = "Hello from the desk"
        const val PEER_LINK_TEXT = "Rules are here: https://nordlicht.example/rules"
        const val PEER_GIF = "https://gifs.example/party.gif"
        const val MENTION_TEXT = "@${AppDriver.USER_NAME} can you host the first round?"
        const val MENTIONS_YOU = "Mentions you"
        const val RAW_MARKER = "raw-marker"
        const val RAW_ESCAPED = "<i>not italic</i>"
        const val RAW_BOLD = "raw-bold"

        /** What the phone writes; e2e/run.sh looks for it in the second person's report. */
        const val PHONE_TEXT = "Hello from the phone"
        const val PHONE_LINK_TEXT = "Look: https://phone.example/page"
        const val PHONE_GIF = "https://gifs.example/phone.gif"
    }
}
