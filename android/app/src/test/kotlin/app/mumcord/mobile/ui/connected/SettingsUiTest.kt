// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.ActivityKind
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.session.FAKE_MOBILE_SETTINGS
import app.mumcord.mobile.session.InMemoryMessageNotificationSettings
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

/** The More tab (activity badge, settings) and the notice after "disconnect when alone", through the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUiTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val notifier = FakeIdleNotifier()
    private val messageSettings = InMemoryMessageNotificationSettings()

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
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            messageSettings = messageSettings,
            idle = idle,
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        backgroundScope.launch { viewModel.moreState.collect() }
        runCurrent()
        viewModel.connect(1)
        runCurrent()
        return viewModel
    }

    private val AppViewModel.settings get() = moreState.value?.settings

    private val AppViewModel.badge get() = moreState.value!!.activityBadge

    private fun activity(id: Int, self: Boolean = false) = BridgeEvent.ActivityAdded(
        ActivityEntry(
            id = id.toULong(),
            kind = if (self) ActivityKind.MOVED else ActivityKind.JOINED,
            session = id.toUInt(),
            name = "Person $id",
            isSelf = self,
            channelId = MOCKUP_GAMING,
            channelName = "Gaming",
            atMs = id.toLong(),
        ),
    )

    @Test
    fun theSettingsOpenFromMoreWithTheDefaultsOfTheSpecification() = runTest(dispatcher) {
        val viewModel = connected()
        assertNull("Closed at first", viewModel.settings)

        viewModel.connected.selectTab(ConnectedTab.More)
        viewModel.more.openSettings()
        runCurrent()

        val settings = viewModel.settings!!
        assertFalse("Start streams automatically", settings.mobile.mobileDataAutoplayStreams)
        assertTrue("Load images and GIFs", settings.mobile.mobileDataLoadMedia)
        assertFalse("Reduced voice quality", settings.mobile.mobileDataReducedVoice)
        assertFalse("Disconnect when alone", settings.mobile.idleDisconnect)
        assertTrue("Private messages", settings.app.notifyPrivateMessages)
        assertTrue("Mentions", settings.app.notifyMentions)
        assertFalse("Show sender only", settings.senderOnly)
        assertNull("History is kept", settings.app.historyMaxAgeDays)
        assertEquals(TalkMode.VOICE_ACTIVITY, settings.talkMode)
        assertEquals("Read from the engine", false, settings.noiseSuppression)
        assertFalse("This phone has no earpiece", settings.hasEarpiece)

        viewModel.more.back()
        runCurrent()
        assertNull(viewModel.settings)
    }

    @Test
    fun everySwitchIsSavedAtOnceAndLeavesTheOthersAlone() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.more.openSettings()

        viewModel.more.changeMobile { it.copy(mobileDataAutoplayStreams = true) }
        viewModel.more.changeMobile { it.copy(mobileDataLoadMedia = false) }
        viewModel.more.changeMobile { it.copy(mobileDataReducedVoice = true) }
        viewModel.more.changeMobile { it.copy(idleDisconnect = true) }
        viewModel.more.changeApp { it.copy(notifyPrivateMessages = false) }
        viewModel.more.changeApp { it.copy(notifyMentions = false) }
        viewModel.more.setNoiseSuppression(true)
        viewModel.more.setSenderOnly(true)
        runCurrent()

        val expected = FAKE_MOBILE_SETTINGS.copy(
            mobileDataAutoplayStreams = true,
            mobileDataLoadMedia = false,
            mobileDataReducedVoice = true,
            idleDisconnect = true,
        )
        assertEquals(expected, engine.mobileSettings)
        assertFalse(engine.appSettings.notifyPrivateMessages)
        assertFalse(engine.appSettings.notifyMentions)
        assertTrue("The other shared settings stay", engine.appSettings.linkPreviews)
        assertTrue(engine.noiseSuppression)
        assertTrue(messageSettings.senderOnly.value)
        val shown = viewModel.settings!!
        assertEquals("The screen shows what was saved", expected, shown.mobile)
        assertEquals(engine.appSettings, shown.app)
        assertEquals(true, shown.noiseSuppression)
        assertTrue(shown.senderOnly)
    }

    @Test
    fun aChoiceFromASheetIsSavedAndClosesIt() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.more.openSettings()

        viewModel.more.openSheet(SettingsSheet.TalkMode)
        runCurrent()
        assertEquals(SettingsSheet.TalkMode, viewModel.settings!!.sheet)
        viewModel.more.selectTalkMode(TalkMode.PUSH_TO_TALK)
        runCurrent()
        assertNull(viewModel.settings!!.sheet)
        assertEquals(TalkMode.PUSH_TO_TALK, engine.talkMode)
        assertEquals(TalkMode.PUSH_TO_TALK, viewModel.settings!!.talkMode)

        viewModel.more.openSheet(SettingsSheet.HistoryAge)
        viewModel.more.selectHistoryAge(30u)
        runCurrent()
        assertEquals(30u, engine.appSettings.historyMaxAgeDays)
        assertEquals(30u, viewModel.settings!!.app.historyMaxAgeDays)

        viewModel.more.openSheet(SettingsSheet.DefaultOutput)
        viewModel.more.selectDefaultOutput(DefaultOutput.EARPIECE)
        runCurrent()
        assertEquals(DefaultOutput.EARPIECE, engine.mobileSettings.defaultOutput)
        assertNull(viewModel.settings!!.sheet)
    }

    @Test
    fun theHistoryChoicesAreTheDesktops() {
        assertEquals(listOf(null, 7u, 30u, 90u, 365u), HISTORY_AGE_DAYS)
    }

    @Test
    fun theActivityBadgeCountsWhatOthersDidUntilTheLogIsOpened() = runTest(dispatcher) {
        val viewModel = connected()
        assertEquals(0, viewModel.badge)

        engine.emit(activity(1))
        engine.emit(activity(2))
        engine.emit(activity(3, self = true))
        runCurrent()
        assertEquals("the user's own move is not news", 2, viewModel.badge)
        assertEquals(listOf(3uL, 2uL, 1uL), viewModel.moreState.value!!.activity.map { it.id })

        viewModel.more.openActivity()
        engine.emit(activity(4))
        runCurrent()
        assertEquals(MorePage.Activity, viewModel.moreState.value!!.page)
        assertEquals(0, viewModel.badge)

        viewModel.more.back()
        runCurrent()
        assertEquals("seen while the log was open", 0, viewModel.badge)

        engine.emit(activity(5))
        runCurrent()
        assertEquals(1, viewModel.badge)

        viewModel.more.selectFilter(ActivityFilter.Streams)
        viewModel.disconnect()
        runCurrent()
        assertEquals(MoreLocal(), viewModel.more.local.value)
    }

    @Test
    fun afterDisconnectingAloneTheAppSaysSoAndOffersToReconnect() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.more.changeMobile { it.copy(idleDisconnect = true) }
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
        viewModel.more.changeMobile { it.copy(idleDisconnect = true) }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        viewModel.dismissOverlay()
        runCurrent()

        assertNull(viewModel.uiState.value.overlay)
        assertFalse(notifier.disconnected)
    }
}
