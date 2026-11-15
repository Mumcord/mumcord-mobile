// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.PublicServer
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.servers.PublicPing
import app.mumcord.mobile.ui.servers.PublicServersActions
import app.mumcord.mobile.ui.servers.PublicServersLocal
import app.mumcord.mobile.ui.servers.PublicServersScreen
import app.mumcord.mobile.ui.servers.key
import app.mumcord.mobile.ui.servers.publicServersUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The public server browser in both themes at the size of the mockups. No mockup exists for it:
 * it is built from the design system in the look of the server list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class PublicServersScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private fun listed(name: String, host: String, country: String, code: String) =
        PublicServer(name = name, host = host, port = 64738u, country = country, countryCode = code)

    private val servers = listOf(
        listed("Alpine Lounge", "alps.example", "Switzerland", "CH"),
        listed("Berlin Gaming", "mumble.berlin.example", "Germany", "DE"),
        listed("Lantern Guild", "lanternguild.example", "Germany", "DE"),
        listed("Nordic Friends", "voice.nordic.example", "Sweden", "SE"),
        listed("Quiet Harbor", "quietharbor.example", "Netherlands", "NL"),
        listed("Tidepool", "voice.tidepool.example", "Canada", "CA"),
    )

    private val pings = mapOf(
        servers[0].key() to PublicPing.Answered(12, 50, 34),
        servers[1].key() to PublicPing.Answered(87, 100, 21),
        servers[2].key() to PublicPing.Answered(2, 20, 48),
        servers[3].key() to PublicPing.NoAnswer,
        servers[4].key() to PublicPing.Answered(0, 25, 63),
    )

    private val actions = object : PublicServersActions {
        override fun back() = Unit
        override fun refresh() = Unit
        override fun search(text: String) = Unit
        override fun selectCountry(code: String?) = Unit
        override fun showing(keys: List<String>) = Unit
        override fun add(key: String) = Unit
        override fun connect(serverId: Long) = Unit
    }

    private fun capture(name: String, dark: Boolean, local: PublicServersLocal) =
        composeRule.captureScreenshot("android-public-servers-$name-${if (dark) "dark" else "light"}") {
            MumcordTheme(darkTheme = dark) {
                PublicServersScreen(
                    // Lantern Guild is already in the server list: it offers Connect instead of Add.
                    publicServersUiState(local, listOf(savedServer(1, "Lantern Guild"))),
                    actions,
                    topInsets = top,
                    bottomInsets = bottom,
                )
            }
        }

    private fun list(dark: Boolean) = capture("list", dark, PublicServersLocal(servers = servers, pings = pings))

    private fun filtered(dark: Boolean) =
        capture("filtered", dark, PublicServersLocal(servers = servers, pings = pings, search = "voice", country = "SE"))

    private fun loading(dark: Boolean) = capture("loading", dark, PublicServersLocal(loading = true))

    private fun failed(dark: Boolean) = capture("failed", dark, PublicServersLocal(loadFailed = true))

    private fun refreshFailed(dark: Boolean) =
        capture("refresh-failed", dark, PublicServersLocal(servers = servers, pings = pings, refreshFailed = true))

    private fun noMatch(dark: Boolean) = capture("no-match", dark, PublicServersLocal(servers = servers, search = "zeppelin"))

    @Test fun listDark() = list(dark = true)

    @Test fun listLight() = list(dark = false)

    @Test fun filteredDark() = filtered(dark = true)

    @Test fun filteredLight() = filtered(dark = false)

    @Test fun loadingDark() = loading(dark = true)

    @Test fun loadingLight() = loading(dark = false)

    @Test fun failedDark() = failed(dark = true)

    @Test fun failedLight() = failed(dark = false)

    @Test fun refreshFailedDark() = refreshFailed(dark = true)

    @Test fun refreshFailedLight() = refreshFailed(dark = false)

    @Test fun noMatchDark() = noMatch(dark = true)

    @Test fun noMatchLight() = noMatch(dark = false)
}
