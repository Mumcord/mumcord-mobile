// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.session.FAKE_MOBILE_SETTINGS
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.FakeIdleNotifier
import app.mumcord.mobile.session.IdleDisconnect
import app.mumcord.mobile.session.IdleTimes
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.Overlay
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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

/** The settings in the More tab and the notice after "disconnect when alone", through the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUiTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val notifier = FakeIdleNotifier()

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Connected to Nordlicht, alone in the Lobby; "disconnect when alone" waits 20 s and warns for 10 s. */
    private suspend fun TestScope.connected(): AppViewModel {
        val server = mockupVoiceServer().let { all ->
            all.copy(people = all.people.map { if (it.isSelf) it else it.copy(channelId = MOCKUP_GAMING) })
        }
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        val idle = IdleDisconnect(repository, notifier, backgroundScope, clock = { currentTime }) { IdleTimes(20_000, 10_000) }
        val viewModel = AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess(), idle = idle)
        backgroundScope.launch { viewModel.uiState.collect() }
        runCurrent()
        viewModel.connect(1)
        runCurrent()
        return viewModel
    }

    private val AppViewModel.settings get() = uiState.value.connected!!.settings

    @Test
    fun theSettingsOpenFromMoreWithTheDefaultsOfTheSpecification() = runTest(dispatcher) {
        val viewModel = connected()
        assertNull("Closed at first", viewModel.settings)

        viewModel.connected.selectTab(ConnectedTab.More)
        viewModel.connected.openSettings()
        runCurrent()

        val settings = viewModel.settings!!
        assertFalse("Start streams automatically", settings.mobileDataAutoplayStreams)
        assertTrue("Load images and GIFs", settings.mobileDataLoadMedia)
        assertFalse("Reduced voice quality", settings.mobileDataReducedVoice)
        assertFalse("Disconnect when alone", settings.idleDisconnect)

        viewModel.connected.closeSettings()
        runCurrent()
        assertNull(viewModel.settings)
    }

    @Test
    fun everySwitchIsSavedAtOnceAndLeavesTheOthersAlone() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.openSettings()

        viewModel.connected.changeSettings { it.copy(mobileDataAutoplayStreams = true) }
        viewModel.connected.changeSettings { it.copy(mobileDataLoadMedia = false) }
        viewModel.connected.changeSettings { it.copy(mobileDataReducedVoice = true) }
        viewModel.connected.changeSettings { it.copy(idleDisconnect = true) }
        runCurrent()

        val expected = FAKE_MOBILE_SETTINGS.copy(
            mobileDataAutoplayStreams = true,
            mobileDataLoadMedia = false,
            mobileDataReducedVoice = true,
            idleDisconnect = true,
        )
        assertEquals(expected, engine.mobileSettings)
        assertEquals("The screen shows what was saved", expected, viewModel.settings)
    }

    @Test
    fun afterDisconnectingAloneTheAppSaysSoAndOffersToReconnect() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.changeSettings { it.copy(idleDisconnect = true) }
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()

        assertNull("Back on the server list", viewModel.uiState.value.connected)
        assertEquals(Overlay.SessionEnded(1, "Nordlicht", alone = true), viewModel.uiState.value.overlay)
        assertTrue(notifier.disconnected)

        viewModel.reconnectAfterSessionEnded()
        runCurrent()
        assertNull(viewModel.uiState.value.overlay)
        assertFalse("The notification goes with the notice", notifier.disconnected)
        assertTrue(viewModel.uiState.value.connected != null)
    }

    @Test
    fun closingTheNoticeTakesTheNotificationAway() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.connected.changeSettings { it.copy(idleDisconnect = true) }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        viewModel.dismissOverlay()
        runCurrent()

        assertNull(viewModel.uiState.value.overlay)
        assertFalse(notifier.disconnected)
    }
}
