// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChannelPasswordState
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_MUSIC
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedSheetHost
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
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

/**
 * Draws the connected screens in both themes at the size of the mockups, with the people and
 * channels of mockup `02-channels`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ConnectedScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val actions = ConnectedController(
        SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {}),
        scope,
    )

    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    @After
    fun tearDown() = scope.cancel()

    private fun capture(name: String, dark: Boolean, local: ConnectedLocal, session: SessionState = this.session) =
        composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
            val state = checkNotNull(connectedUiState(session, local, "Nordlicht"))
            MumcordTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize()) {
                    ConnectedShell(state, actions, onDisconnect = {}, topInsets = top, bottomInsets = bottom)
                    state.sheet?.let { ConnectedSheetHost(it, actions, top, bottom) }
                }
            }
        }

    private fun channels(dark: Boolean) = capture("android-channels", dark, ConnectedLocal())

    private fun personSheet(dark: Boolean) =
        capture("android-person-sheet", dark, ConnectedLocal(personSession = MOCKUP_JONAS))

    private fun channelPassword(dark: Boolean) = capture(
        "android-channel-password",
        dark,
        ConnectedLocal(),
        session.fold(BridgeEvent.ChannelPasswordChanged(MOCKUP_MUSIC, ChannelPasswordState.REQUIRED)),
    )

    private fun search(dark: Boolean) =
        capture("android-channel-search", dark, ConnectedLocal(searching = true, query = "mi"))

    private fun more(dark: Boolean) = capture("android-more", dark, ConnectedLocal(tab = ConnectedTab.More))

    private fun emptyTab(dark: Boolean) = capture("android-tab-empty", dark, ConnectedLocal(tab = ConnectedTab.Chat))

    @Test fun channelsDark() = channels(dark = true)

    @Test fun channelsLight() = channels(dark = false)

    @Test fun personSheetDark() = personSheet(dark = true)

    @Test fun personSheetLight() = personSheet(dark = false)

    @Test fun channelPasswordDark() = channelPassword(dark = true)

    @Test fun channelPasswordLight() = channelPassword(dark = false)

    @Test fun searchDark() = search(dark = true)

    @Test fun searchLight() = search(dark = false)

    @Test fun moreDark() = more(dark = true)

    @Test fun moreLight() = more(dark = false)

    @Test fun emptyTabDark() = emptyTab(dark = true)

    @Test fun emptyTabLight() = emptyTab(dark = false)
}
