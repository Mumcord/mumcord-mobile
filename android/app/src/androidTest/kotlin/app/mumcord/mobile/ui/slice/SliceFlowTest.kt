// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.ConnectRequest
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.session.channel
import app.mumcord.mobile.session.person
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The whole slice on a device against a fake engine: form, certificate question, channels, disconnect. */
class SliceFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val fingerprint = "AB:CD:EF:01:23:45"
    private val server = ServerState(
        selfSession = 7u,
        channels = listOf(
            channel(0u, "Test Root"),
            channel(1u, "Lobby", parentId = 0u),
            // Markup in a name must show up as the characters it is made of.
            channel(2u, "<b>Games</b>", parentId = 0u, position = 1),
        ),
        people = listOf(
            person(7u, "Tester", channelId = 1u),
            person(8u, "Alice", channelId = 1u),
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val repository = SessionRepository(
        engineFactory = { listener ->
            FakeEngine(listener, server, CertificatePrompt(fingerprint, null)).also { engine = it }
        },
        scope = scope,
        engineDispatcher = Dispatchers.IO,
        log = {},
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun connectTrustTheCertificateSeeTheChannelsAndDisconnect() {
        compose.setContent {
            MumcordTheme {
                SliceRoute(viewModel = SliceViewModel(repository), engineVersion = "test")
            }
        }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Server address").performTextInput("10.0.2.2")
        compose.onNodeWithText("Port").performTextReplacement("40000")
        compose.onNodeWithText("Your name").performTextInput("Tester")
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { exists("Trust this server?") }
        compose.onNodeWithTag(FINGERPRINT_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(fingerprint).assertIsDisplayed()
        compose.onNodeWithText("Trust and connect").performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { exists("Channels") }
        compose.onNodeWithText("Test Root").assertIsDisplayed()
        compose.onNodeWithText("Lobby").assertIsDisplayed()
        compose.onNodeWithText("<b>Games</b>").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("Tester").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tester, you").assertIsDisplayed()
        compose.onNodeWithContentDescription("Channel Lobby, 2 people").assertIsDisplayed()
        assertEquals(listOf(ConnectRequest("10.0.2.2", 40000u, "Tester", null)), engine.connectRequests.toList())
        assertEquals(listOf(fingerprint to true), engine.certificateAnswers.toList())

        compose.onNodeWithText("Disconnect").performClick()
        compose.waitUntil(TIMEOUT_MILLIS) { exists("Connect to a server") }
        compose.onNodeWithText("Connect").assertIsEnabled()
    }

    private fun exists(text: String): Boolean =
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
