// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_AFK
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.BAN_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.KICK_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_BAN_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_ERROR_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_KICK_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_MOVE_TEST_TAG
import app.mumcord.mobile.ui.connected.MODERATION_MUTE_TEST_TAG
import app.mumcord.mobile.ui.connected.MOVE_CHOICE_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.NotificationAccess
import app.mumcord.mobile.ui.connected.NotificationSystem
import app.mumcord.mobile.ui.connected.PERSON_ROW_TEST_TAG
import app.mumcord.mobile.ui.connected.PERSON_SHEET_TEST_TAG
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

/**
 * The moderation section of the person sheet on a device against a fake engine, on the server
 * of mockup `02-channels`: the user is Lea, Jonas is in Gaming.
 */
class ModerationUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resizeForTheKeyboard() = compose.resizeForTheKeyboardLikeTheApp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Every right on every channel, as for a server admin. */
    private fun admin(): ServerState = mockupServer().let { server ->
        server.copy(
            channels = server.channels.map {
                it.copy(permissions = it.permissions.copy(movePeople = true, muteDeafen = true, kick = true, ban = true))
            },
        )
    }

    /** Starts the app, connects to Nordlicht and opens Jonas's sheet. */
    private fun openJonas(server: ServerState = admin(), configure: (FakeEngine) -> Unit = {}) {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(savedServer(1, "Nordlicht")))
                    .also(configure)
                    .also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            NotificationAccess.Never,
        )
        compose.setContent {
            MumcordTheme {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                    NotificationSystem(ask = {}, openSettings = {}),
                    chatImages = ChatImageSystem(pickPhoto = {}, takePhoto = {}),
                    chatImageLoader = ChatImageLoader { _, _, _ -> null },
                )
            }
        }
        compose.connectToNordlicht()
        await("the connected screens") { shown(VOICE_BAR_TEST_TAG) }
        compose.onNode(hasTestTag(PERSON_ROW_TEST_TAG) and hasText("Jonas")).performClick()
        await("Jonas's sheet") { shown(PERSON_SHEET_TEST_TAG) }
    }

    @Test
    fun withoutAnyRightTheSheetHasNoModeration() {
        openJonas(server = mockupServer())
        assertTrue(!exists("Moderation".uppercase()) && !shown(MODERATION_MOVE_TEST_TAG) && !shown(MODERATION_KICK_TEST_TAG))
        assertTrue(!shown(MODERATION_MUTE_TEST_TAG) && !shown(MODERATION_BAN_TEST_TAG))
    }

    @Test
    fun serverMuteFollowsTheServerAndMovingClosesTheSheets() {
        openJonas()
        compose.onNodeWithTag(MODERATION_MUTE_TEST_TAG).performScrollTo().performClick()
        await("the entry that lifts the mute") { exists("Remove server mute") }
        compose.onNodeWithTag(MODERATION_MUTE_TEST_TAG).performScrollTo().performClick()
        await("the mute entry again") { exists("Server mute") }

        compose.onNodeWithTag(MODERATION_MOVE_TEST_TAG).performScrollTo().performClick()
        await("the channel picker") { shown(MOVE_CHOICE_TEST_TAG) }
        compose.onNode(hasTestTag(MOVE_CHOICE_TEST_TAG) and hasText("AFK")).performScrollTo().performClick()
        await("the sheets to close") { !shown(PERSON_SHEET_TEST_TAG) && !shown(MOVE_CHOICE_TEST_TAG) }
        assertEquals(
            listOf("mute $MOCKUP_JONAS true", "mute $MOCKUP_JONAS false", "move $MOCKUP_JONAS $MOCKUP_AFK"),
            engine.moderationCalls.toList(),
        )
    }

    @Test
    fun aRefusedKickSaysSoInItsSheetAndATryAgainGoesThrough() {
        openJonas { it.moderationFailure = BridgeException.PermissionDenied("Kicking is off tonight") }
        compose.onNodeWithTag(MODERATION_KICK_TEST_TAG).performScrollTo().performClick()
        await("the kick question") { exists("Kick Jonas?") }
        compose.onNodeWithContentDescription("Reason").performTextInput("Cool down")
        compose.onNodeWithTag(KICK_CONFIRM_TEST_TAG).performScrollTo().press()
        await("the server's refusal in the sheet") { shown(MODERATION_ERROR_TEST_TAG) && exists("Kicking is off tonight", substring = true) }

        compose.onNodeWithTag(KICK_CONFIRM_TEST_TAG).performScrollTo().performClick()
        await("the sheets to close after the kick") { !shown(KICK_CONFIRM_TEST_TAG) && !shown(PERSON_SHEET_TEST_TAG) }
        assertEquals(listOf<String?>("Cool down"), engine.kickReasons.toList())
    }

    @Test
    fun aBanTakesTheChosenLength() {
        openJonas()
        compose.onNodeWithTag(MODERATION_BAN_TEST_TAG).performScrollTo().performClick()
        await("the ban form") { shown(BAN_CONFIRM_TEST_TAG) }
        compose.onNodeWithText("7 days").performScrollTo().performClick()
        await("the button to name the length") { exists("Ban for 7 days") }
        compose.onNodeWithTag(BAN_CONFIRM_TEST_TAG).performScrollTo().performClick()
        await("the sheets to close after the ban") { !shown(BAN_CONFIRM_TEST_TAG) && !shown(PERSON_SHEET_TEST_TAG) }
        val ban = engine.banInputs.single()
        assertEquals(604_800u, ban.durationSecs)
        assertTrue(ban.byCertificate && ban.byAddress && ban.removeNow)
    }

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** Waits for [condition]; a wait that runs out says what it waited for and what the screen showed. */
    private fun await(what: String, condition: () -> Boolean) = try {
        compose.waitUntil(TIMEOUT_MILLIS, condition)
    } catch (error: ComposeTimeoutException) {
        throw AssertionError("Waited in vain for $what. The screen:\n${compose.onRoot().printToString()}", error)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
