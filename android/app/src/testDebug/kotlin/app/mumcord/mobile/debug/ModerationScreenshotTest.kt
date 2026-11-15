// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BanTarget
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.ui.connected.BanForm
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedSheetHost
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ModerationFailure
import app.mumcord.mobile.ui.connected.ModerationLocal
import app.mumcord.mobile.ui.connected.connectedUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The moderation section of the person sheet (mockup `06-person-sheet`) and the steps without a
 * mockup (move, kick, ban), as server admin of the server of mockup `02-channels`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ModerationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val chatActions = screenshotChatController(repository, scope)
    private val messages = ScreenshotMessages(repository, actions, scope)

    // Every right on every channel: what a server admin sees.
    private val session = mockupServer().let { server ->
        server.copy(
            channels = server.channels.map {
                it.copy(permissions = it.permissions.copy(movePeople = true, muteDeafen = true, kick = true, ban = true))
            },
        )
    }.let { server ->
        listOf(BridgeEvent.ConnectionChanged(ConnectionState.Connected), BridgeEvent.ServerSynced(server))
            .fold(SessionState()) { state, event -> state.fold(event) }
    }

    // A fixed moment, so "Ends on" is the same on every day the screenshots are drawn.
    private val opened = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()

    private val target = BanTarget(
        address = "192.0.2.7",
        onlineSecs = 5_400u,
        banListReadable = true,
        hasCertificate = true,
        canKick = true,
        canBan = true,
    )

    @After
    fun tearDown() = scope.cancel()

    private fun capture(name: String, dark: Boolean, moderation: ModerationLocal? = null) =
        composeRule.captureScreenshot("android-moderation-$name-${if (dark) "dark" else "light"}") {
            val local = ConnectedLocal(personSession = MOCKUP_JONAS, moderation = moderation)
            val state = checkNotNull(connectedUiState(session, local, "Nordlicht"))
            MumcordTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    ConnectedShell(
                        state,
                        actions,
                        onDisconnect = {},
                        onMicrophone = {},
                        chat = null,
                        chatBadge = 0,
                        chatActions = chatActions,
                        chatEnvironment = screenshotChatEnvironment(),
                        messages = messages.state(session),
                        messagesActions = messages.actions,
                        conversation = null,
                        conversationActions = messages.conversationActions,
                        onNotificationSettings = {},
                        onIdentities = {},
                        topInsets = top,
                        bottomInsets = bottom,
                    )
                    state.sheet?.let {
                        ConnectedSheetHost(
                            it,
                            actions,
                            top,
                            bottom,
                            onMicrophoneContinue = {},
                            onMicrophoneNotNow = {},
                            messages = messages.state(session),
                            messagesActions = messages.actions,
                        )
                    }
                }
            }
        }

    private fun ban(target: BanTarget = this.target, conflict: Boolean = false) = ModerationLocal.Ban(
        MOCKUP_JONAS,
        openedAtMs = opened,
        target = target,
        form = BanForm(reason = "Spam links", durationSecs = 86_400u),
        conflict = conflict,
    )

    @Test fun personSheetDark() = capture("person-sheet", dark = true)

    @Test fun personSheetLight() = capture("person-sheet", dark = false)

    @Test fun moveDark() = capture("move", dark = true, ModerationLocal.Move(MOCKUP_JONAS))

    @Test fun moveLight() = capture("move", dark = false, ModerationLocal.Move(MOCKUP_JONAS))

    @Test fun kickDark() = capture("kick", dark = true, ModerationLocal.Kick(MOCKUP_JONAS, reason = "Cool down"))

    @Test fun kickLight() = capture("kick", dark = false, ModerationLocal.Kick(MOCKUP_JONAS, reason = "Cool down"))

    @Test fun kickRefusedDark() =
        capture("kick-refused", dark = true, ModerationLocal.Kick(MOCKUP_JONAS, failure = ModerationFailure.Refused("")))

    @Test fun kickRefusedLight() =
        capture("kick-refused", dark = false, ModerationLocal.Kick(MOCKUP_JONAS, failure = ModerationFailure.Refused("")))

    @Test fun banDark() = capture("ban", dark = true, ban())

    @Test fun banLight() = capture("ban", dark = false, ban())

    @Test fun banQuickDark() = capture("ban-quick", dark = true, ban(target.copy(banListReadable = false)))

    @Test fun banQuickLight() = capture("ban-quick", dark = false, ban(target.copy(banListReadable = false)))

    @Test fun banConflictDark() = capture("ban-conflict", dark = true, ban(conflict = true))

    @Test fun banConflictLight() = capture("ban-conflict", dark = false, ban(conflict = true))
}
