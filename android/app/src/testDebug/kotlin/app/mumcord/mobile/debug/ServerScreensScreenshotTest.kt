// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.CertificatePromptState
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.ui.connect.CertificateSheet
import app.mumcord.mobile.ui.connect.LocalNetworkSheet
import app.mumcord.mobile.ui.servers.ConnectFailure
import app.mumcord.mobile.ui.servers.LocalNetworkHint
import app.mumcord.mobile.ui.servers.PingState
import app.mumcord.mobile.ui.servers.ServerFormScreen
import app.mumcord.mobile.ui.servers.ServerFormState
import app.mumcord.mobile.ui.servers.ServerListScreen
import app.mumcord.mobile.ui.servers.ServerRow
import app.mumcord.mobile.ui.servers.initials
import app.mumcord.mobile.ui.setup.TalkModeChoiceScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the first-start and server screens in both themes at the size of the mockups, with the
 * mockups' sample content where a mockup exists (server list, talk mode choice).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ServerScreensScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private fun row(id: Long, name: String, address: String, ping: PingState, connecting: Boolean = false) =
        ServerRow(id, name, initials(name), address, ping, connecting = connecting, highlighted = id == 1L)

    private fun rows(connectingFirst: Boolean = false) = listOf(
        row(1, "Nordlicht", "nordlicht.example", PingState.Online(5, 24), connecting = connectingFirst),
        row(2, "Tidepool", "voice.tidepool.example", PingState.Online(12, 31)),
        row(3, "Lantern Guild", "lanternguild.example", PingState.Online(2, 48)),
        row(4, "Quiet Harbor", "quietharbor.example", PingState.Offline),
    )

    @Composable
    private fun ServerList(rows: List<ServerRow>, failure: ConnectFailure? = null) {
        ServerListScreen(
            rows = rows,
            loaded = true,
            editing = false,
            failure = failure,
            onConnect = {},
            onCancelConnect = {},
            onAdd = {},
            onToggleEditing = {},
            onEdit = {},
            onRemove = {},
            onRetry = {},
            onOpenSettings = {},
            onDismissError = {},
            topInsets = top,
            bottomInsets = bottom,
        )
    }

    @Composable
    private fun Certificate(previousFingerprint: String?) {
        Box {
            ServerList(rows(connectingFirst = true))
            CertificateSheet(
                prompt = CertificatePromptState(
                    fingerprint = "9F:86:D0:81:88:4C:7D:65:9A:2F:EA:A0:C5:5A:D0:15:A3:BF:4F:1B:2B:0B:82:2C:D1:5D:6C:15:B0:F0:0A:08",
                    previousFingerprint = previousFingerprint,
                ),
                onTrust = {},
                onReject = {},
                topInsets = top,
                bottomInsets = bottom,
            )
        }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) =
        composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
            MumcordTheme(darkTheme = dark) { content() }
        }

    private fun serverList(dark: Boolean) = capture("android-server-list", dark) { ServerList(rows()) }

    private fun serverListEmpty(dark: Boolean) = capture("android-server-list-empty", dark) { ServerList(emptyList()) }

    private fun talkModeChoice(dark: Boolean) = capture("android-talk-mode-choice", dark) {
        // Push-to-talk picked, as the mockup shows it; the app itself starts with nothing picked.
        TalkModeChoiceScreen(
            selected = TalkMode.PUSH_TO_TALK,
            saving = false,
            error = null,
            onSelect = {},
            onContinue = {},
            topInsets = top,
            bottomInsets = bottom,
        )
    }

    private fun serverForm(dark: Boolean) = capture("android-server-form", dark) {
        ServerFormScreen(
            form = ServerFormState(address = "nordlicht.example", port = "99999", name = "Nordlicht", showErrors = true),
            onChange = {},
            onSave = {},
            onBack = {},
            topInsets = top,
            bottomInsets = bottom,
        )
    }

    private fun certificateUnknown(dark: Boolean) = capture("android-certificate-unknown", dark) { Certificate(null) }

    private fun certificateChanged(dark: Boolean) = capture("android-certificate-changed", dark) {
        Certificate("2C:26:B4:6B:68:FF:C6:8F:F9:9B:45:3C:1D:30:41:34:13:42:2D:70:64:83:BF:A0:F9:8A:5E:88:62:66:E7:AE")
    }

    private fun connectError(dark: Boolean) = capture("android-connect-error", dark) {
        ServerList(rows(), ConnectFailure(ConnectionError.ServerUnreachable, "Nordlicht"))
    }

    // No mockup exists for the local network permission; both states need product review.
    private fun localNetworkExplain(dark: Boolean) = capture("android-localnet-explain", dark) {
        Box {
            ServerList(rows())
            LocalNetworkSheet(serverName = "Nordlicht", onContinue = {}, onDismiss = {}, topInsets = top, bottomInsets = bottom)
        }
    }

    private fun localNetworkDenied(dark: Boolean) = capture("android-localnet-denied", dark) {
        ServerList(rows(), ConnectFailure(ConnectionError.ServerUnreachable, "Nordlicht", LocalNetworkHint.Denied))
    }

    @Test fun serverListDark() = serverList(dark = true)

    @Test fun serverListLight() = serverList(dark = false)

    @Test fun serverListEmptyDark() = serverListEmpty(dark = true)

    @Test fun serverListEmptyLight() = serverListEmpty(dark = false)

    @Test fun talkModeChoiceDark() = talkModeChoice(dark = true)

    @Test fun talkModeChoiceLight() = talkModeChoice(dark = false)

    @Test fun serverFormDark() = serverForm(dark = true)

    @Test fun serverFormLight() = serverForm(dark = false)

    @Test fun certificateUnknownDark() = certificateUnknown(dark = true)

    @Test fun certificateUnknownLight() = certificateUnknown(dark = false)

    @Test fun certificateChangedDark() = certificateChanged(dark = true)

    @Test fun certificateChangedLight() = certificateChanged(dark = false)

    @Test fun connectErrorDark() = connectError(dark = true)

    @Test fun connectErrorLight() = connectError(dark = false)

    @Test fun localNetworkExplainDark() = localNetworkExplain(dark = true)

    @Test fun localNetworkExplainLight() = localNetworkExplain(dark = false)

    @Test fun localNetworkDeniedDark() = localNetworkDenied(dark = true)

    @Test fun localNetworkDeniedLight() = localNetworkDenied(dark = false)
}
