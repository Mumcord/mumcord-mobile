// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_AFK
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LEA
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MUSIC
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppScreen
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The connected screens' actions through the view model, against the fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A view model that is already connected to the mockup server. */
    private fun TestScope.connected(configure: (FakeEngine) -> Unit = {}): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht")))
                    .also(configure)
                    .also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway()).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
            viewModel.connect(1)
            runCurrent()
        }
    }

    private val AppViewModel.shell get() = uiState.value.connected!!

    private fun AppViewModel.channelOf(session: UInt): UInt? = uiState.value.session.server?.people?.get(session)?.channelId

    @Test
    fun connectingShowsTheChannelsTabOfTheServer() = runTest(dispatcher) {
        val viewModel = connected()
        assertEquals(AppScreen.Channels, viewModel.uiState.value.screen)
        assertEquals(ConnectedTab.Channels, viewModel.shell.tab)
        assertEquals("Nordlicht", viewModel.shell.serverName)
    }

    @Test
    fun tappingAChannelJoinsItAndTheOwnChannelIsLeftAlone() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.join(MOCKUP_LOBBY)
        runCurrent()
        assertEquals(emptyList<Pair<UInt, String?>>(), engine.joinCalls.toList())

        viewModel.connected.join(MOCKUP_GAMING)
        runCurrent()
        assertEquals(listOf<Pair<UInt, String?>>(MOCKUP_GAMING to null), engine.joinCalls.toList())
        assertEquals(MOCKUP_GAMING, viewModel.channelOf(MOCKUP_LEA))
        assertTrue(viewModel.shell.rows.filterIsInstance<ChannelListRow.ChannelItem>().first { it.id == MOCKUP_GAMING }.current)
    }

    @Test
    fun aChannelPasswordIsAskedForRefusedWhenWrongAndLetsInWhenRight() = runTest(dispatcher) {
        val viewModel = connected { it.channelPasswords[MOCKUP_MUSIC] = "secret" }
        viewModel.connected.join(MOCKUP_MUSIC)
        runCurrent()
        assertEquals(ConnectedSheet.ChannelPassword("Music", wrong = false, password = ""), viewModel.shell.sheet)

        viewModel.connected.submitPassword()
        runCurrent()
        assertEquals("An empty password is not sent", 1, engine.joinCalls.size)

        viewModel.connected.changePassword("guess")
        viewModel.connected.submitPassword()
        runCurrent()
        assertEquals(ConnectedSheet.ChannelPassword("Music", wrong = true, password = ""), viewModel.shell.sheet)

        viewModel.connected.changePassword("secret")
        viewModel.connected.submitPassword()
        runCurrent()
        assertNull(viewModel.shell.sheet)
        assertEquals(MOCKUP_MUSIC, viewModel.channelOf(MOCKUP_LEA))
        assertEquals(listOf(null, "guess", "secret"), engine.joinCalls.map { it.second })
    }

    @Test
    fun givingUpOnAChannelPasswordClosesTheSheetAndForgetsWhatWasTyped() = runTest(dispatcher) {
        val viewModel = connected { it.channelPasswords[MOCKUP_MUSIC] = "secret" }
        viewModel.connected.join(MOCKUP_MUSIC)
        runCurrent()
        viewModel.connected.changePassword("gue")
        viewModel.connected.dismissSheet()
        runCurrent()
        assertNull(viewModel.shell.sheet)
        assertEquals("", viewModel.connected.local.value.password)
        assertEquals(MOCKUP_LOBBY, viewModel.channelOf(MOCKUP_LEA))
    }

    @Test
    fun aRefusedJoinShowsABannerUntilItIsPutAwayOrTheNextJoin() = runTest(dispatcher) {
        val viewModel = connected { it.deniedChannels += MOCKUP_AFK }
        viewModel.connected.join(MOCKUP_AFK)
        runCurrent()
        assertEquals(DeniedBanner("AFK", "Permission denied."), viewModel.shell.denied)
        viewModel.connected.dismissDenied()
        runCurrent()
        assertNull(viewModel.shell.denied)

        viewModel.connected.join(MOCKUP_AFK)
        runCurrent()
        viewModel.connected.join(MOCKUP_GAMING)
        runCurrent()
        assertNull(viewModel.shell.denied)
    }

    @Test
    fun foldingAChannelTogglesAndClosesTheMenu() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openChannelMenu(MOCKUP_GAMING)
        runCurrent()
        assertEquals(ConnectedSheet.ChannelMenu(MOCKUP_GAMING, "Gaming", expanded = true, canJoin = true), viewModel.shell.sheet)
        viewModel.connected.toggleCollapsed(MOCKUP_GAMING)
        runCurrent()
        assertNull(viewModel.shell.sheet)
        assertEquals(setOf(MOCKUP_GAMING), viewModel.connected.local.value.collapsed)
        viewModel.connected.toggleCollapsed(MOCKUP_GAMING)
        assertEquals(emptySet<UInt>(), viewModel.connected.local.value.collapsed)
    }

    @Test
    fun thePersonSheetSetsVolumeAndMuteForTheUserOnly() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openPerson(MOCKUP_JONAS)
        viewModel.connected.changeVolume(250)
        viewModel.connected.setLocalMute(true)
        runCurrent()
        val sheet = viewModel.shell.sheet as ConnectedSheet.PersonSheet
        assertEquals(200, sheet.volumePercent)
        assertTrue(sheet.locallyMuted)
        assertEquals(listOf(MOCKUP_JONAS to 200), engine.volumeCalls.toList())

        viewModel.connected.dismissSheet()
        runCurrent()
        assertNull(viewModel.shell.sheet)
        assertNull(viewModel.connected.local.value.volumeDraft)
    }

    @Test
    fun theOwnEntryOpensNoPersonSheet() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openPerson(MOCKUP_LEA)
        runCurrent()
        assertNull(viewModel.shell.sheet)
    }

    @Test
    fun searchFiltersAndClosingItShowsEverythingAgain() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openSearch()
        viewModel.connected.changeQuery("ben")
        runCurrent()
        assertEquals(3, viewModel.shell.rows.size)
        viewModel.connected.closeSearch()
        runCurrent()
        assertEquals(10, viewModel.shell.rows.size)
        assertEquals("", viewModel.shell.query)
    }

    @Test
    fun disconnectingLeavesTheConnectedScreensAndStartsThemAfresh() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.selectTab(ConnectedTab.More)
        viewModel.connected.toggleCollapsed(MOCKUP_GAMING)
        runCurrent()
        assertEquals(ConnectedTab.More, viewModel.shell.tab)

        viewModel.disconnect()
        runCurrent()
        assertNull(viewModel.uiState.value.connected)
        assertEquals(AppScreen.ServerList, viewModel.uiState.value.screen)
        assertEquals(ConnectedLocal(), viewModel.connected.local.value)
    }
}
