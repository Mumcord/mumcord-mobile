// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.publicServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVERS_REFRESH_FAILED_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVERS_RETRY_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVERS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVER_ADD_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVER_ROW_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_BROWSE_PUBLIC_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_ROW_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The public server browser on a device against a fake engine with a list made up for the
 * test: nothing is requested before it opens, search and the country filter narrow the list,
 * the rows on screen show their ping, a listed server is added and connected to, and a list
 * that cannot be loaded or refreshed is handled calmly.
 */
class PublicServersUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resizeForTheKeyboard() = compose.resizeForTheKeyboardLikeTheApp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private lateinit var repository: SessionRepository

    private val listed = listOf(
        publicServer("Nordic Friends", "voice.nordic.example", "Sweden", "SE"),
        publicServer("Berlin Gaming", "mumble.berlin.example", "Germany", "DE"),
        publicServer("Alpine Lounge", "alps.example", "Switzerland", "CH"),
        publicServer("Lantern Guild", "lantern.example", "Germany", "DE"),
    )

    private class Setup(override var firstStartDone: Boolean) : SetupStore {
        override fun markFirstStartDone() {
            firstStartDone = true
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun start(fontScale: Float = 1f, configure: (FakeEngine) -> Unit = {}) {
        repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Tidepool"))).also {
                    it.publicServers += listed
                    it.publicPings["voice.nordic.example:64738"] = ServerPing(latencyMs = 34u, peopleOnline = 12u, maxPeople = 50u)
                    configure(it)
                    engine = it
                }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        val viewModel = AppViewModel(repository, Setup(firstStartDone = true), FakeLocalNetworkGateway(), FakeMicrophoneAccess())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MumcordTheme {
                    AppRoute(
                        viewModel,
                        LocalNetworkSystem(ask = {}, openSettings = {}),
                        MicrophoneSystem(ask = {}, openSettings = {}),
                    )
                }
            }
        }
        await { shown(SERVER_ROW_TEST_TAG) }
    }

    private fun openBrowser() {
        compose.onNodeWithTag(SERVER_BROWSE_PUBLIC_TEST_TAG).performScrollTo().performClick()
        await { shown(PUBLIC_SERVERS_SCREEN_TEST_TAG) }
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun text(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun rows() = compose.onAllNodesWithTag(PUBLIC_SERVER_ROW_TEST_TAG).fetchSemanticsNodes().size

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    @Test
    fun theListIsLoadedOnlyWhenOpenedNarrowedBySearchAndCountryAndAListedServerIsAddedAndConnectedTo() {
        start()
        assertEquals("nothing is requested before the browser opens", 0, engine.publicDownloads)

        openBrowser()
        await { rows() == 4 }
        assertEquals(1, engine.publicDownloads)
        // Name, address and country of every row; the ping of the rows on screen.
        assertFalse(text("Loading"))
        await { text("voice.nordic.example · Sweden") }
        await { text("12 / 50 · 34 ms") }
        await { text("No answer") }

        compose.onNodeWithContentDescription(SEARCH).performTextInput("lantern")
        await { rows() == 1 && text("Lantern Guild") }
        compose.onNodeWithContentDescription(SEARCH).performTextClearance()
        await { rows() == 4 }

        compose.onNodeWithText("Germany").press()
        await { rows() == 2 && text("Berlin Gaming") && text("Lantern Guild") && !text("Nordic Friends") }
        compose.onNodeWithText("Any country").performClick()
        await { rows() == 4 }

        compose.onNodeWithContentDescription("Add Nordic Friends to your servers").performClick()
        await { described("Connect to Nordic Friends") }
        val added = engine.serverInputs.single()
        assertEquals(listOf("Nordic Friends", "voice.nordic.example", "Lea"), listOf(added.name, added.address, added.username))

        compose.onNodeWithContentDescription("Connect to Nordic Friends").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
        val connected = engine.connectCalls.single().serverId
        assertEquals(added.name, repository.state.value.servers.single { it.id == connected }.name)
    }

    @Test
    fun aListThatCannotBeLoadedOffersTryAgainAndAFailedRefreshKeepsTheLastList() {
        start { it.publicListFailure = BridgeException.ServerUnreachable("offline") }
        openBrowser()
        await { shown(PUBLIC_SERVERS_RETRY_TEST_TAG) }
        assertEquals(0, rows())

        engine.publicListFailure = null
        compose.onNodeWithTag(PUBLIC_SERVERS_RETRY_TEST_TAG).performClick()
        await { rows() == 4 }

        engine.publicListFailure = BridgeException.ServerUnreachable("offline")
        compose.onNodeWithText("Refresh").performClick()
        await { shown(PUBLIC_SERVERS_REFRESH_FAILED_TEST_TAG) }
        assertEquals("the last list stays", 4, rows())
        assertEquals(3, engine.publicDownloads)
    }

    @Test
    fun backLeavesTheBrowserAndPingsStop() {
        start()
        openBrowser()
        await { text("12 / 50 · 34 ms") }
        val asked = engine.publicPingRequests.size

        compose.onNodeWithContentDescription("Back to the server list").performClick()
        await { shown(SERVER_ROW_TEST_TAG) && !shown(PUBLIC_SERVERS_SCREEN_TEST_TAG) }
        compose.waitForIdle()
        assertEquals("no ping after leaving", asked, engine.publicPingRequests.size)
    }

    @Test
    fun atTwiceTheFontSizeTheBrowserIsReachableAndItsButtonsAreLargeEnoughToTap() {
        start(fontScale = 2f)
        compose.onNodeWithTag(SERVER_BROWSE_PUBLIC_TEST_TAG).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        openBrowser()
        // At this size each row wraps its texts and puts its button below them, so fewer rows fit.
        await { rows() > 0 }
        // The row's button is the small button of the server list, drawn at the design's touch size.
        compose.onAllNodesWithTag(PUBLIC_SERVER_ADD_TEST_TAG)[0].assertIsDisplayed().assertHeightIsAtLeast(MumcordSize.touchVisual)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val SEARCH = "Search by name, address or country"
    }
}
