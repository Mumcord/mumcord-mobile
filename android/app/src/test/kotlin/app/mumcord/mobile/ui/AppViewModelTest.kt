// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.TalkMode
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
import app.mumcord.mobile.ui.servers.PingState
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

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(channel(0u, "Root")),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )
    private val nordlicht = savedServer(1, "Nordlicht")
    private val tidepool = savedServer(2, "Tidepool", username = "Anna")
    private lateinit var engine: FakeEngine
    private val setupStore = FakeSetupStore(done = true)
    private val localNetwork = FakeLocalNetworkGateway()

    private class FakeSetupStore(done: Boolean) : SetupStore {
        override var firstStartDone = done
            private set

        override fun markFirstStartDone() {
            firstStartDone = true
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        prompt: CertificatePrompt? = null,
        configure: (FakeEngine) -> Unit = {},
    ): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, prompt, servers = listOf(nordlicht, tidepool))
                    .also(configure)
                    .also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
            linkParser = ::parseTestLink,
        )
        return AppViewModel(repository, setupStore, localNetwork, FakeMicrophoneAccess()).also { viewModel ->
            // The state is only kept up to date while somebody watches it, as a screen would.
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
        }
    }

    /** `mumble://[user@]host[:port][/channel/…]`, enough of the real parser for these tests. */
    private fun parseTestLink(url: String): MumbleLink {
        if (!url.startsWith("mumble://")) throw BridgeException.InvalidInput("not a mumble link")
        val rest = url.removePrefix("mumble://")
        val authority = rest.substringBefore('/')
        val hostPort = authority.substringAfter('@')
        return MumbleLink(
            address = hostPort.substringBefore(':'),
            port = hostPort.substringAfter(':', "64738").toUShort(),
            username = authority.substringBefore('@', "").ifEmpty { null },
            channelPath = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() },
        )
    }

    private val AppViewModel.ui get() = uiState.value

    // ----- first-start setup -----

    @Test
    fun firstStartShowsTheTalkModeChoiceWithNothingPicked() = runTest(dispatcher) {
        val viewModel = AppViewModel(
            SessionRepository({ FakeEngine(it, server).also { e -> engine = e } }, backgroundScope, dispatcher, {}),
            FakeSetupStore(done = false),
            localNetwork,
            FakeMicrophoneAccess(),
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        assertEquals(AppScreen.TalkModeChoice, viewModel.ui.screen)
        assertNull(viewModel.ui.talkModeChoice)

        // Without a choice "Continue" does nothing.
        viewModel.confirmTalkMode()
        runCurrent()
        assertEquals(AppScreen.TalkModeChoice, viewModel.ui.screen)
        assertEquals(TalkMode.VOICE_ACTIVITY, engine.talkMode)
    }

    @Test
    fun theChosenTalkModeIsSavedAndTheSetupIsDoneForGood() = runTest(dispatcher) {
        val store = FakeSetupStore(done = false)
        val viewModel = AppViewModel(
            SessionRepository({ FakeEngine(it, server).also { e -> engine = e } }, backgroundScope, dispatcher, {}),
            store,
            localNetwork,
            FakeMicrophoneAccess(),
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        viewModel.selectTalkMode(TalkMode.PUSH_TO_TALK)
        viewModel.confirmTalkMode()
        runCurrent()

        assertEquals(TalkMode.PUSH_TO_TALK, engine.talkMode)
        assertTrue(store.firstStartDone)
        assertEquals(AppScreen.ServerList, viewModel.ui.screen)
    }

    @Test
    fun aTalkModeThatCouldNotBeSavedKeepsTheSetupOpenAndSaysWhy() = runTest(dispatcher) {
        val store = FakeSetupStore(done = false)
        val viewModel = AppViewModel(
            SessionRepository(
                { FakeEngine(it, server).also { e -> e.talkModeFailure = BridgeException.Io("disk full") } },
                backgroundScope,
                dispatcher,
                {},
            ),
            store,
            localNetwork,
            FakeMicrophoneAccess(),
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()

        viewModel.selectTalkMode(TalkMode.VOICE_ACTIVITY)
        viewModel.confirmTalkMode()
        runCurrent()

        assertFalse(store.firstStartDone)
        assertEquals(AppScreen.TalkModeChoice, viewModel.ui.screen)
        assertEquals(ConnectionError.Io, viewModel.ui.setupError)
    }

    // ----- server list -----

    @Test
    fun theListShowsTheSavedServersWithTheirPingAnswers() = runTest(dispatcher) {
        val viewModel = viewModel(configure = { it.pings[1] = ServerPing(24u, 5u, 50u) })

        assertEquals(AppScreen.ServerList, viewModel.ui.screen)
        assertEquals(listOf("Nordlicht", "Tidepool"), viewModel.ui.serverRows.map { it.name })
        assertEquals(
            listOf(PingState.Online(peopleOnline = 5, latencyMs = 24), PingState.Offline),
            viewModel.ui.serverRows.map { it.ping },
        )
    }

    @Test
    fun comingBackToTheListAsksTheServersAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.pings[2] = ServerPing(31u, 12u, 50u)

        viewModel.refreshPings()
        runCurrent()

        assertEquals(PingState.Online(12, 31), viewModel.ui.serverRows[1].ping)
        assertEquals(listOf(1L, 2L, 1L, 2L), engine.pingRequests.toList())
    }

    @Test
    fun addingAServerValidatesFirstAndThenSavesAndClosesTheForm() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openAddServer()
        runCurrent()
        assertEquals(AppScreen.ServerForm, viewModel.ui.screen)

        viewModel.saveForm()
        runCurrent()
        assertTrue(viewModel.ui.form?.showErrors == true)
        assertTrue(engine.serverInputs.isEmpty())

        viewModel.changeForm { it.copy(address = "quietharbor.example", username = "Me", password = "secret") }
        viewModel.saveForm()
        runCurrent()

        assertEquals(
            listOf(ServerInput("", "quietharbor.example", 64738u, "Me", PasswordInput.Set("secret"), null)),
            engine.serverInputs.toList(),
        )
        assertEquals(AppScreen.ServerList, viewModel.ui.screen)
        assertEquals(listOf("Nordlicht", "Tidepool", "quietharbor.example"), viewModel.ui.serverRows.map { it.name })
        // The new server is asked for its people online, too.
        assertTrue(3L in engine.pingRequests)
    }

    @Test
    fun editingAServerSavesTheChangeUnderTheSameEntry() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleEditing()
        runCurrent()
        assertTrue(viewModel.ui.editing)

        viewModel.openEditServer(2)
        viewModel.changeForm { it.copy(name = "Tide") }
        viewModel.saveForm()
        runCurrent()

        assertEquals(listOf("Nordlicht", "Tide"), viewModel.ui.serverRows.map { it.name })
        assertEquals(listOf(1L, 2L), viewModel.ui.serverRows.map { it.id })
    }

    @Test
    fun aSaveTheEngineRefusesStaysInTheFormWithTheError() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.saveFailure = BridgeException.SecureStorageUnavailable("locked")
        viewModel.openAddServer()
        viewModel.changeForm { it.copy(address = "a.example", username = "Me") }

        viewModel.saveForm()
        runCurrent()

        assertEquals(AppScreen.ServerForm, viewModel.ui.screen)
        assertEquals(ConnectionError.SecureStorageUnavailable, viewModel.ui.form?.saveError)
        assertFalse(viewModel.ui.form?.saving == true)
        assertEquals(ConnectionPhase.Idle, viewModel.ui.session.phase)
    }

    @Test
    fun removingAServerAsksFirst() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.requestRemove(2)
        runCurrent()
        assertEquals(Overlay.ConfirmRemove(tidepool), viewModel.ui.overlay)
        assertEquals(2, viewModel.ui.serverRows.size)

        viewModel.dismissOverlay()
        runCurrent()
        assertEquals(2, viewModel.ui.serverRows.size)

        viewModel.requestRemove(2)
        viewModel.confirmRemove()
        runCurrent()
        assertNull(viewModel.ui.overlay)
        assertEquals(listOf("Nordlicht"), viewModel.ui.serverRows.map { it.name })
    }

    // ----- connecting -----

    @Test
    fun connectingAsksTheCertificateQuestionAndThenShowsTheChannels() = runTest(dispatcher) {
        val viewModel = viewModel(prompt = CertificatePrompt("AA:BB", null))

        viewModel.connect(1)
        runCurrent()
        assertEquals(listOf(true, false), viewModel.ui.serverRows.map { it.connecting })
        assertEquals("AA:BB", viewModel.ui.session.certificatePrompt?.fingerprint)

        viewModel.answerCertificatePrompt("AA:BB", trust = true)
        runCurrent()
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
        assertEquals(listOf(ConnectCall(1, null, emptyList())), engine.connectCalls.toList())

        viewModel.disconnect()
        runCurrent()
        assertEquals(AppScreen.ServerList, viewModel.ui.screen)
    }

    @Test
    fun connectingElsewhereAsksFirstAndDisconnectsBeforeItConnects() = runTest(dispatcher) {
        val viewModel = viewModel(prompt = CertificatePrompt("AA:BB", null))
        viewModel.connect(1)
        runCurrent()

        // Nordlicht is still connecting (it waits for the certificate answer).
        viewModel.connect(2)
        runCurrent()
        assertEquals(Overlay.ConfirmSwitch(ConnectTarget(2), "Nordlicht", "Tidepool"), viewModel.ui.overlay)
        assertEquals(1, engine.connectCalls.size)
        assertEquals(0, engine.disconnects)

        viewModel.confirmSwitch()
        runCurrent()
        assertNull(viewModel.ui.overlay)
        assertEquals(1, engine.disconnects)
        assertEquals(listOf(1L, 2L), engine.connectCalls.map { it.serverId })
        assertEquals(2L, viewModel.ui.session.activeServerId)
    }

    @Test
    fun decliningTheSwitchKeepsTheCurrentConnection() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(1)
        runCurrent()
        assertEquals(AppScreen.Channels, viewModel.ui.screen)

        viewModel.connect(2)
        viewModel.dismissOverlay()
        runCurrent()

        assertEquals(AppScreen.Channels, viewModel.ui.screen)
        assertEquals(0, engine.disconnects)
        assertEquals(1, engine.connectCalls.size)
    }

    @Test
    fun aFailedConnectionNamesTheServerAndTryAgainConnectsToItAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.connectFailure = BridgeException.ServerUnreachable("timeout")

        viewModel.connect(2)
        runCurrent()
        assertEquals(ConnectionPhase.Failed(ConnectionError.ServerUnreachable), viewModel.ui.session.phase)
        assertEquals("Tidepool", viewModel.ui.activeServerName)
        assertEquals(AppScreen.ServerList, viewModel.ui.screen)

        viewModel.retry()
        runCurrent()
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
        assertEquals(listOf(ConnectCall(2, null, emptyList())), engine.connectCalls.toList())
    }

    @Test
    fun aWrongPasswordOffersToEnterItAndConnectsWithIt() = runTest(dispatcher) {
        val viewModel = viewModel(configure = { it.requiredPassword = "open sesame" })
        viewModel.connect(1)
        runCurrent()
        assertEquals(ConnectionPhase.Failed(ConnectionError.WrongPassword), viewModel.ui.session.phase)

        viewModel.retry()
        runCurrent()
        assertEquals(Overlay.EnterPassword(ConnectTarget(1), "Nordlicht"), viewModel.ui.overlay)

        // Nothing is sent without a password.
        viewModel.submitPassword()
        runCurrent()
        assertEquals(1, engine.connectCalls.size)

        viewModel.changePassword("open sesame")
        viewModel.submitPassword()
        runCurrent()
        assertNull(viewModel.ui.overlay)
        assertEquals(ConnectCall(1, "open sesame", emptyList()), engine.connectCalls.last())
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
    }

    @Test
    fun dismissingAnErrorClearsIt() = runTest(dispatcher) {
        val viewModel = viewModel()
        engine.connectFailure = BridgeException.Banned("reason")
        viewModel.connect(1)
        runCurrent()

        viewModel.dismissError()
        runCurrent()

        assertEquals(ConnectionPhase.Idle, viewModel.ui.session.phase)
        assertNull(viewModel.ui.activeServerName)
    }

    // ----- mumble:// links -----

    @Test
    fun aLinkOnlyShowsItsTargetUntilTheUserConfirms() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.openLink("mumble://Ben@new.example:40000/Games/Chess")
        runCurrent()

        val state = (viewModel.ui.overlay as Overlay.Link).state
        assertEquals(MumbleLink("new.example", 40000u, "Ben", listOf("Games", "Chess")), state.link)
        assertTrue(state.willAddServer)
        assertTrue(engine.serverInputs.isEmpty())
        assertTrue(engine.connectCalls.isEmpty())

        viewModel.dismissOverlay()
        runCurrent()
        assertTrue(engine.serverInputs.isEmpty())
        assertTrue(engine.connectCalls.isEmpty())
        assertEquals(2, viewModel.ui.serverRows.size)
    }

    @Test
    fun confirmingALinkToANewServerAddsItAndJoinsTheChannel() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openLink("mumble://Ben@new.example:40000/Games/Chess")
        runCurrent()

        viewModel.confirmLink()
        runCurrent()

        assertEquals(
            listOf(ServerInput("", "new.example", 40000u, "Ben", PasswordInput.Keep, null)),
            engine.serverInputs.toList(),
        )
        assertEquals(listOf(ConnectCall(3, null, listOf("Games", "Chess"))), engine.connectCalls.toList())
        assertNull(viewModel.ui.overlay)
        assertEquals(AppScreen.Channels, viewModel.ui.screen)
    }

    @Test
    fun aLinkToASavedServerConnectsThroughItsEntryWithoutAddingAnything() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openLink("mumble://Someone@tidepool.example/Lobby")
        runCurrent()
        assertEquals(tidepool, (viewModel.ui.overlay as Overlay.Link).state.savedServer)

        viewModel.confirmLink()
        runCurrent()

        assertTrue(engine.serverInputs.isEmpty())
        assertEquals(listOf(ConnectCall(2, null, listOf("Lobby"))), engine.connectCalls.toList())
    }

    @Test
    fun aLinkWithoutUserNameNeedsOneTyped() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.openLink("mumble://new.example")
        runCurrent()

        viewModel.confirmLink()
        runCurrent()
        assertTrue(engine.serverInputs.isEmpty())

        viewModel.changeLinkUsername("Ben")
        viewModel.confirmLink()
        runCurrent()
        assertEquals("Ben", engine.serverInputs.single().username)
        assertEquals(1, engine.connectCalls.size)
    }

    @Test
    fun aLinkWhileConnectedElsewhereAsksBeforeSwitching() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.connect(1)
        runCurrent()

        viewModel.openLink("mumble://tidepool.example/Lobby")
        runCurrent()
        viewModel.confirmLink()
        runCurrent()

        assertEquals(
            Overlay.ConfirmSwitch(ConnectTarget(2, channelPath = listOf("Lobby")), "Nordlicht", "Tidepool"),
            viewModel.ui.overlay,
        )
        assertEquals(1, engine.connectCalls.size)

        viewModel.confirmSwitch()
        runCurrent()
        assertEquals(ConnectCall(2, null, listOf("Lobby")), engine.connectCalls.last())
        assertEquals(1, engine.disconnects)
    }

    @Test
    fun somethingThatIsNotAMumbleLinkIsReportedAndDoesNothing() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.openLink("https://example.org")
        runCurrent()

        assertEquals(Overlay.InvalidLink, viewModel.ui.overlay)
        assertTrue(engine.connectCalls.isEmpty())
    }
}
