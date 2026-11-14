// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.ConnectCall
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.channel
import app.mumcord.mobile.session.person
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.CERTIFICATE_REJECT_TEST_TAG
import app.mumcord.mobile.ui.connect.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.connect.FINGERPRINT_TEST_TAG
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connect.LOCAL_NETWORK_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.connect.LocalNetworkAnswer
import app.mumcord.mobile.ui.connect.LocalNetworkPermission
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connect.PASSWORD_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.connect.SWITCH_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.link.LINK_CONNECT_TEST_TAG
import app.mumcord.mobile.ui.servers.CONNECT_ERROR_TEST_TAG
import app.mumcord.mobile.ui.servers.CONNECT_RETRY_TEST_TAG
import app.mumcord.mobile.ui.servers.FORM_SAVE_TEST_TAG
import app.mumcord.mobile.ui.servers.LOCAL_NETWORK_SETTINGS_TEST_TAG
import app.mumcord.mobile.ui.servers.REMOVE_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_ADD_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_ROW_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.setup.TALK_MODE_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_PUSH_TO_TALK_TEST_TAG
import app.mumcord.mobile.ui.connected.CHANNEL_PASSWORD_JOIN_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_DISCONNECT_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_MUTE_TEST_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The flows of first start, the server list and connecting on a device against a fake engine. */
class AppFlowTest {
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
    private val nordlicht = savedServer(1, "Nordlicht")
    private val tidepool = savedServer(2, "Tidepool")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private val localNetwork = FakeLocalNetworkGateway()

    /** What the user answers when the system asks for local network access. */
    private var promptAnswer = LocalNetworkAnswer(granted = true, refusedBefore = false, refusedAfter = false)
    private var settingsOpened = 0

    /** Stands in for the system: its question is answered at once with [promptAnswer]. */
    private fun fakeSystem(viewModel: AppViewModel) = LocalNetworkSystem(
        ask = {
            if (promptAnswer.granted) localNetwork.permission = LocalNetworkPermission.Granted
            viewModel.answerLocalNetworkPrompt(promptAnswer)
        },
        openSettings = { settingsOpened++ },
    )

    /** Android 17 without the permission, and Nordlicht at home on the phone's own network. */
    private fun nordlichtOnTheLocalNetwork() {
        localNetwork.permission = LocalNetworkPermission.Missing
        localNetwork.names[nordlicht.address] = listOf("192.168.1.20")
    }

    private class FakeSetupStore(override var firstStartDone: Boolean) : SetupStore {
        override fun markFirstStartDone() {
            firstStartDone = true
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun start(
        setupDone: Boolean = true,
        servers: List<SavedServer> = listOf(nordlicht, tidepool),
        prompt: CertificatePrompt? = null,
        configure: (FakeEngine) -> Unit = {},
    ): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, prompt, servers).also(configure).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
            linkParser = { url ->
                if (url != LINK) throw BridgeException.InvalidInput("not a mumble link")
                MumbleLink("new.example", 40000u, "Ben", listOf("Games", "Chess"))
            },
        )
        val viewModel = AppViewModel(repository, FakeSetupStore(setupDone), localNetwork, FakeMicrophoneAccess())
        compose.setContent {
            MumcordTheme { AppRoute(viewModel, fakeSystem(viewModel)) }
        }
        return viewModel
    }

    @Test
    fun firstStartChooseTheTalkModeAddAServerConnectTrustSeeTheChannelsAndDisconnect() {
        start(setupDone = false, servers = emptyList(), prompt = CertificatePrompt(fingerprint, null))

        // Nothing is picked for the user.
        compose.onNodeWithTag(TALK_MODE_CONTINUE_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(TALK_MODE_PUSH_TO_TALK_TEST_TAG).performClick()
        compose.onNodeWithTag(TALK_MODE_CONTINUE_TEST_TAG).assertIsEnabled().performClick()

        await { exists("No servers yet") }
        assertEquals(TalkMode.PUSH_TO_TALK, engine.talkMode)
        compose.onNodeWithTag(SERVER_ADD_TEST_TAG).performClick()

        // An incomplete form says what is missing instead of saving.
        compose.onNodeWithTag(FORM_SAVE_TEST_TAG).performScrollTo().performClick()
        await { hasFieldError("Enter the address of the server.") }
        compose.onNodeWithContentDescription("Address").performTextInput("10.0.2.2")
        compose.onNodeWithContentDescription("Port").performTextReplacement("40000")
        compose.onNodeWithContentDescription("Name").performTextInput("Home")
        compose.onNodeWithContentDescription("User name").performTextInput("Tester")
        compose.onNodeWithTag(FORM_SAVE_TEST_TAG).performScrollTo().performClick()

        await { exists("Home") && exists("10.0.2.2:40000") }
        compose.onNodeWithContentDescription("Connect to Home").performClick()

        await { exists("Trust this server?") }
        compose.onNodeWithTag(FINGERPRINT_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText(fingerprint).assertIsDisplayed()
        compose.onNodeWithTag(CERTIFICATE_TRUST_TEST_TAG).performClick()

        await { exists("Channels") }
        compose.onNodeWithText("Test Root").assertIsDisplayed()
        compose.onNodeWithText("<b>Games</b>").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertIsDisplayed()

        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag(MORE_DISCONNECT_TEST_TAG).performClick()
        await { exists("Home") && !exists("Channels") }
    }

    @Test
    fun connectedJoinAChannelOpenAPersonSearchSwitchTabsAndDisconnect() {
        val viewModel = start()
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { exists("Test Root") && exists("you are here") }

        // Tapping a channel joins it.
        compose.onNodeWithText("<b>Games</b>").performClick()
        await { engine.joinCalls.toList() == listOf<Pair<UInt, String?>>(2u to null) }

        // Tapping a person opens the sheet with the settings for the user only.
        compose.onNodeWithText("Alice").performClick()
        await { exists("Volume for you") && exists("Mute for me") }
        compose.onNodeWithTag(PERSON_MUTE_TEST_TAG).performClick()
        await { viewModel.uiState.value.session.server?.people?.get(8u)?.locallyMuted == true }
        compose.runOnUiThread { viewModel.connected.dismissSheet() }
        await { !exists("Volume for you") }

        // Search filters channels and people by name.
        compose.onNodeWithContentDescription("Search channels and people").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ali")
        await { exists("Alice") && !exists("<b>Games</b>") }
        compose.onNodeWithContentDescription("Close search").performClick()
        await { exists("<b>Games</b>") }

        compose.onNodeWithText("Chat").performClick()
        await { exists("No chat messages yet.") }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag(MORE_DISCONNECT_TEST_TAG).performClick()
        await { !exists("More") }
    }

    @Test
    fun aLockedChannelAsksForItsPasswordAndARefusalShowsABanner() {
        start(configure = {
            it.channelPasswords[1u] = "secret"
            it.deniedChannels += 2u
        })
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { exists("Test Root") }
        // The user starts in Lobby; move to the root first so Lobby can be joined.
        compose.onNodeWithText("Test Root").performClick()
        await { engine.joinCalls.size == 1 }

        compose.onNodeWithText("Lobby").performClick()
        await { exists("Channel password") }
        compose.onNode(hasSetTextAction()).performTextInput("wrong")
        compose.onNodeWithTag(CHANNEL_PASSWORD_JOIN_TEST_TAG).performClick()
        await { hasFieldError("That password was not accepted. Try again.") }
        compose.onNode(hasSetTextAction()).performTextInput("secret")
        compose.onNodeWithTag(CHANNEL_PASSWORD_JOIN_TEST_TAG).performClick()
        await { !exists("Channel password") }

        compose.onNodeWithText("<b>Games</b>").performClick()
        await { exists("You cannot join <b>Games</b> Permission denied.") }
    }

    @Test
    fun theListShowsPeopleOnlineAndLatencyOrOffline() {
        start(configure = { it.pings[1] = ServerPing(latencyMs = 24u, peopleOnline = 5u, maxPeople = 50u) })

        await { exists("5 online · 24 ms") && exists("Offline") }
        compose.onNodeWithText("nordlicht.example").assertIsDisplayed()
    }

    @Test
    fun aChangedCertificateWarnsStronglyAndNotTrustingItEndsInAnErrorWithRetry() {
        start(prompt = CertificatePrompt(fingerprint, previousFingerprint = "11:22:33"))
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()

        await { exists("Server identity changed") }
        compose.onNodeWithText("11:22:33").assertIsDisplayed()
        compose.onNodeWithText(fingerprint).assertIsDisplayed()
        compose.onNodeWithTag(CERTIFICATE_REJECT_TEST_TAG).performClick()

        await { compose.onAllNodesWithTag(CONNECT_ERROR_TEST_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).assertIsDisplayed().performClick()
        await { exists("Server identity changed") }
        assertEquals(2, engine.connectCalls.size)
    }

    @Test
    fun aWrongPasswordOffersToEnterItAndTryAgain() {
        start(configure = { it.requiredPassword = "open sesame" })
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()

        await { exists("Enter password") }
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        await { exists("Server password") }
        compose.onNodeWithTag(PASSWORD_CONNECT_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Password").performTextInput("open sesame")
        compose.onNodeWithTag(PASSWORD_CONNECT_TEST_TAG).performClick()

        await { exists("Channels") }
        assertEquals(ConnectCall(1, "open sesame", emptyList()), engine.connectCalls.last())
    }

    @Test
    fun editModeChangesAServerAndRemovesOneAfterAConfirmation() {
        start()
        await { rowCount() == 2 }

        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("Edit Tidepool").performClick()
        await { exists("Edit server") }
        compose.onNodeWithContentDescription("Name").performTextReplacement("Tide")
        compose.onNodeWithTag(FORM_SAVE_TEST_TAG).performScrollTo().performClick()
        await { exists("Tide") && !exists("Edit server") }

        compose.onNodeWithContentDescription("Remove Nordlicht").performClick()
        await { exists("Remove server?") }
        assertEquals(2, engine.snapshot().servers.size)
        compose.onNodeWithTag(REMOVE_CONFIRM_TEST_TAG).performClick()
        await { rowCount() == 1 }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Connect to Tide").assertIsDisplayed()
    }

    @Test
    fun aLinkShowsItsTargetAndConnectsOnlyAfterConfirmationAlsoWhenConnectedElsewhere() {
        val viewModel = start()
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { exists("Channels") }

        viewModel.openLink(LINK)
        await { exists("Open this server?") }
        compose.onNodeWithText("new.example:40000").assertIsDisplayed()
        compose.onNodeWithText("Games / Chess").assertIsDisplayed()
        compose.onNodeWithText("Ben").assertIsDisplayed()
        compose.onNodeWithText("This server will be added to your list.").assertIsDisplayed()
        assertTrue(engine.serverInputs.isEmpty())
        assertEquals(1, engine.connectCalls.size)

        compose.onNodeWithTag(LINK_CONNECT_TEST_TAG).performClick()
        // One server at a time: the running connection is only ended after a confirmation.
        await { exists("Switch server?") }
        assertEquals(0, engine.disconnects)
        compose.onNodeWithTag(SWITCH_CONFIRM_TEST_TAG).performClick()

        await { engine.connectCalls.size == 2 && exists("Channels") }
        assertEquals(1, engine.disconnects)
        assertEquals(ConnectCall(3, null, listOf("Games", "Chess")), engine.connectCalls.last())
    }

    // ----- local network permission (FR-5.8) -----

    @Test
    fun aServerOnTheLocalNetworkIsExplainedAndConnectsByItselfOnceAllowed() {
        nordlichtOnTheLocalNetwork()
        start()
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()

        await { exists("Local network access") }
        assertTrue(engine.connectCalls.isEmpty())
        compose.onNodeWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).performClick()

        await { exists("Channels") }
        assertEquals(listOf(ConnectCall(1, null, emptyList())), engine.connectCalls.toList())
    }

    @Test
    fun notNowClosesTheExplanationAndLeavesTheListWithoutAnError() {
        nordlichtOnTheLocalNetwork()
        start()
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { exists("Local network access") }

        compose.onNodeWithText("Not now").performClick()

        await { !exists("Local network access") }
        assertTrue(compose.onAllNodesWithTag(CONNECT_ERROR_TEST_TAG).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Connect to Nordlicht").assertIsDisplayed()
        assertTrue(engine.connectCalls.isEmpty())
    }

    @Test
    fun refusedLocalNetworkAccessIsNamedOffersTheSettingsAndConnectsOnceAllowedThere() {
        nordlichtOnTheLocalNetwork()
        promptAnswer = LocalNetworkAnswer(granted = false, refusedBefore = false, refusedAfter = true)
        start()
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { exists("Local network access") }
        compose.onNodeWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).performClick()

        await { hasText("may not access your local network") }
        compose.onNodeWithTag(LOCAL_NETWORK_SETTINGS_TEST_TAG).assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)

        // The system still asks: "Try again" explains again. This time it is refused for good.
        promptAnswer = LocalNetworkAnswer(granted = false, refusedBefore = true, refusedAfter = false)
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        await { exists("Local network access") }
        compose.onNodeWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).performClick()
        await { hasText("may not access your local network") }

        // Now the system no longer asks, so nothing is explained: the error stays, with its way out.
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(LOCAL_NETWORK_CONTINUE_TEST_TAG).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(LOCAL_NETWORK_SETTINGS_TEST_TAG).assertIsDisplayed()
        assertTrue(engine.connectCalls.isEmpty())

        // Allowed in the system settings: "Try again" connects.
        localNetwork.permission = LocalNetworkPermission.Granted
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        await { exists("Channels") }
        assertEquals(1, engine.connectCalls.size)
    }

    private fun hasText(part: String): Boolean =
        compose.onAllNodesWithText(part, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The error of a form field, which a screen reader reads together with the field. */
    private fun hasFieldError(message: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
            .fetchSemanticsNodes().isNotEmpty()

    private fun rowCount(): Int =
        compose.onAllNodesWithTag(SERVER_ROW_TEST_TAG).fetchSemanticsNodes().size

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val LINK = "mumble://Ben@new.example:40000/Games/Chess"
    }
}
