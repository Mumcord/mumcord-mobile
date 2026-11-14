// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.session.ConnectCall
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.channel
import app.mumcord.mobile.session.person
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connect.LocalNetworkAnswer
import app.mumcord.mobile.ui.connect.LocalNetworkPermission
import app.mumcord.mobile.ui.servers.ConnectFailure
import app.mumcord.mobile.ui.servers.LocalNetworkHint
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connecting to a server on the local network (FR-5.8): the explanation, the system's question
 * and what follows from its answer, from the server list, "Try again" and `mumble://` links.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(channel(0u, "Root")),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )

    // One server at home, one behind a second router or VPN, one on the internet.
    private val home = savedServer(1, "Home", address = "192.168.1.20")
    private val office = savedServer(2, "Office", address = "office.example")
    private val public = savedServer(3, "Public", address = "public.example")
    private val localNetwork = FakeLocalNetworkGateway(LocalNetworkPermission.Missing).apply {
        names["office.example"] = listOf("10.8.0.1")
        names["public.example"] = listOf("203.0.113.7")
    }
    private lateinit var engine: FakeEngine

    private object DoneSetup : SetupStore {
        override val firstStartDone = true

        override fun markFirstStartDone() = Unit
    }

    private val allowed = LocalNetworkAnswer(granted = true, refusedBefore = false, refusedAfter = false)
    private val refused = LocalNetworkAnswer(granted = false, refusedBefore = false, refusedAfter = true)
    private val refusedForGood = LocalNetworkAnswer(granted = false, refusedBefore = true, refusedAfter = false)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(home, office, public)).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
            linkParser = { url ->
                if (url != HOME_LINK) throw BridgeException.InvalidInput("not a mumble link")
                MumbleLink(home.address, home.port, home.username, listOf("Lobby"))
            },
        )
        return AppViewModel(repository, DoneSetup, localNetwork, FakeMicrophoneAccess()).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
        }
    }

    private val AppViewModel.ui get() = uiState.value

    /** The user answers the system's question; allowing it is what grants the permission. */
    private fun TestScope.answer(viewModel: AppViewModel, answer: LocalNetworkAnswer) {
        if (answer.granted) localNetwork.permission = LocalNetworkPermission.Granted
        viewModel.answerLocalNetworkPrompt(answer)
        runCurrent()
    }

    private val deniedFailure = ConnectFailure(ConnectionError.ServerUnreachable, "Home", LocalNetworkHint.Denied)

    @Test
    fun aLocalServerIsExplainedFirstAndConnectsByItselfOnceAllowed() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.connect(1)
        runCurrent()
        assertEquals(Overlay.LocalNetwork(ConnectTarget(1), "Home"), viewModel.ui.overlay)
        assertTrue(engine.connectCalls.isEmpty())
        assertNull(viewModel.ui.failure)

        answer(viewModel, allowed)
        assertNull(viewModel.ui.overlay)
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
        assertEquals(listOf(ConnectCall(1, null, emptyList())), engine.connectCalls.toList())
    }

    @Test
    fun notNowLeavesTheServerListAsItWas() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(1)
        runCurrent()

        viewModel.dismissOverlay()
        runCurrent()

        assertNull(viewModel.ui.overlay)
        assertNull(viewModel.ui.failure)
        assertEquals(AppScreen.ServerList, viewModel.ui.screen)
        assertFalse(viewModel.ui.serverRows.any { it.connecting })
        assertTrue(engine.connectCalls.isEmpty())
    }

    @Test
    fun aRefusalIsShownAsTheErrorAndTryAgainAsksAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(1)
        runCurrent()

        answer(viewModel, refused)
        assertNull(viewModel.ui.overlay)
        assertEquals(deniedFailure, viewModel.ui.failure)
        assertFalse(localNetwork.promptBlocked)
        assertTrue(engine.connectCalls.isEmpty())

        viewModel.retry()
        runCurrent()
        assertEquals(Overlay.LocalNetwork(ConnectTarget(1), "Home"), viewModel.ui.overlay)
        assertNull(viewModel.ui.failure)
    }

    @Test
    fun onceTheSystemNoLongerAsksTheRefusalIsShownStraightAway() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(1)
        runCurrent()
        answer(viewModel, refusedForGood)
        assertTrue(localNetwork.promptBlocked)

        viewModel.retry()
        runCurrent()
        assertNull(viewModel.ui.overlay)
        assertEquals(deniedFailure, viewModel.ui.failure)

        // The same from the server list, also after the error was dismissed.
        viewModel.dismissError()
        runCurrent()
        assertNull(viewModel.ui.failure)
        viewModel.connect(1)
        runCurrent()
        assertNull(viewModel.ui.overlay)
        assertEquals(deniedFailure, viewModel.ui.failure)
        assertTrue(engine.connectCalls.isEmpty())
    }

    @Test
    fun afterAllowingItInTheSystemSettingsTryAgainConnects() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(1)
        runCurrent()
        answer(viewModel, refusedForGood)

        localNetwork.permission = LocalNetworkPermission.Granted
        viewModel.retry()
        runCurrent()

        assertNull(viewModel.ui.failure)
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
        assertFalse(localNetwork.promptBlocked)
    }

    @Test
    fun anAndroidVersionWithoutThePermissionNeverAsks() = runTest(dispatcher) {
        localNetwork.permission = LocalNetworkPermission.NotRequired
        val viewModel = viewModel()

        viewModel.connect(1)
        runCurrent()

        assertNull(viewModel.ui.overlay)
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
        assertTrue(localNetwork.resolved.isEmpty())
    }

    @Test
    fun anInternetServerConnectsWithoutAnyQuestion() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.connect(3)
        runCurrent()

        assertNull(viewModel.ui.overlay)
        assertEquals(listOf(ConnectCall(3, null, emptyList())), engine.connectCalls.toList())
    }

    @Test
    fun anInternetServerThatCannotBeReachedDoesNotMentionThePermission() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.connectFailure = BridgeException.ServerUnreachable("timeout")

        viewModel.connect(3)
        runCurrent()

        assertEquals(ConnectFailure(ConnectionError.ServerUnreachable, "Public"), viewModel.ui.failure)
    }

    @Test
    fun aPrivateAddressBehindARouterConnectsWithoutAnyQuestion() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.connect(2)
        runCurrent()

        assertNull(viewModel.ui.overlay)
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
    }

    @Test
    fun aPrivateAddressThatCannotBeReachedMentionsThePermissionAndTryAgainAsksForIt() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.connectFailure = BridgeException.ServerUnreachable("timeout")
        viewModel.connect(2)
        runCurrent()
        assertEquals(
            ConnectFailure(ConnectionError.ServerUnreachable, "Office", LocalNetworkHint.Suspected),
            viewModel.ui.failure,
        )

        viewModel.retry()
        runCurrent()
        assertEquals(Overlay.LocalNetwork(ConnectTarget(2), "Office"), viewModel.ui.overlay)
        assertNull(viewModel.ui.failure)

        answer(viewModel, allowed)
        assertEquals(listOf(ConnectCall(2, null, emptyList())), engine.connectCalls.toList())
    }

    @Test
    fun anotherErrorOfAPrivateAddressDoesNotMentionThePermission() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.connectFailure = BridgeException.Banned("reason")

        viewModel.connect(2)
        runCurrent()

        assertEquals(ConnectFailure(ConnectionError.Banned, "Office"), viewModel.ui.failure)
    }

    @Test
    fun aLinkToALocalServerIsExplainedAndThenJoinsItsChannel() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openLink(HOME_LINK)
        runCurrent()

        viewModel.confirmLink()
        runCurrent()
        assertEquals(Overlay.LocalNetwork(ConnectTarget(1, channelPath = listOf("Lobby")), "Home"), viewModel.ui.overlay)

        answer(viewModel, allowed)
        assertEquals(listOf(ConnectCall(1, null, listOf("Lobby"))), engine.connectCalls.toList())
    }

    @Test
    fun aRefusedLinkKeepsItsChannelForTryAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openLink(HOME_LINK)
        runCurrent()
        viewModel.confirmLink()
        runCurrent()
        answer(viewModel, refused)

        viewModel.retry()
        runCurrent()
        answer(viewModel, allowed)

        assertEquals(listOf(ConnectCall(1, null, listOf("Lobby"))), engine.connectCalls.toList())
    }

    @Test
    fun switchingToALocalServerAsksAfterTheSwitchWasConfirmed() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(3)
        runCurrent()

        viewModel.connect(1)
        runCurrent()
        assertTrue(viewModel.ui.overlay is Overlay.ConfirmSwitch)
        viewModel.confirmSwitch()
        runCurrent()
        assertEquals(Overlay.LocalNetwork(ConnectTarget(1), "Home"), viewModel.ui.overlay)

        answer(viewModel, allowed)
        assertEquals(ConnectCall(1, null, emptyList()), engine.connectCalls.last())
    }

    @Test
    fun aNameThatDoesNotResolveConnectsAsBeforeAndShowsTheNormalError() = runTest(dispatcher) {
        localNetwork.names.remove("public.example")
        val viewModel = viewModel()
        engine.connectFailure = BridgeException.ServerUnreachable("no such host")

        viewModel.connect(3)
        runCurrent()

        assertNull(viewModel.ui.overlay)
        assertEquals(ConnectionPhase.Failed(ConnectionError.ServerUnreachable), viewModel.ui.session.phase)
        assertEquals(ConnectFailure(ConnectionError.ServerUnreachable, "Public"), viewModel.ui.failure)
    }

    private companion object {
        const val HOME_LINK = "mumble://Me@192.168.1.20/Lobby"
    }
}
