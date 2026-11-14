// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.platform.network.NetworkMonitor
import app.mumcord.mobile.platform.network.NetworkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mobile data and network changes (FR-5.26, FR-5.40, FR-5.41; AC-5.13): the rules as values,
 * then the session against the fake engine with a network the test plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSessionTest {
    private val wifi = NetworkStatus(id = 1, validated = true, metered = false)
    private val mobile = NetworkStatus(id = 2, validated = true, metered = true)
    private val defaults = FAKE_MOBILE_SETTINGS
    private val reconnecting = ConnectionPhase.Reconnecting(attempt = 3, maxAttempts = 10)

    // ----- the rules -----

    @Test
    fun imagesAndGifsAreAlwaysLoadedOnWifiAndOnMobileDataAsTheSwitchSays() {
        val off = defaults.copy(mobileDataLoadMedia = false)

        assertTrue(loadMedia(wifi, off))
        assertFalse(loadMedia(mobile, off))
        assertTrue("On by default", loadMedia(mobile, defaults))
        assertTrue("And while the settings are unread", loadMedia(mobile, null))
        assertFalse("Without a network nothing is loaded when the switch is off", loadMedia(NetworkStatus.None, off))
    }

    @Test
    fun streamsStartByThemselvesOnWifiAndOnMobileDataOnlyWithTheSwitch() {
        assertTrue(autoplayStreams(wifi, defaults))
        assertFalse("Off by default", autoplayStreams(mobile, defaults))
        assertFalse(autoplayStreams(mobile, null))
        assertTrue(autoplayStreams(mobile, defaults.copy(mobileDataAutoplayStreams = true)))
    }

    @Test
    fun theVoiceBitrateIsLimitedTo24OnlyOnAMeteredNetworkWithTheSwitchOn() {
        val reduced = defaults.copy(mobileDataReducedVoice = true)

        assertEquals(24u, bitrateLimitKbps(mobile, reduced))
        assertNull(bitrateLimitKbps(wifi, reduced))
        assertNull("Off by default", bitrateLimitKbps(mobile, defaults))
        assertNull(bitrateLimitKbps(mobile, null))
    }

    @Test
    fun meteredIsWhatTheNetworkSaysNotItsKind() {
        val hotspot = NetworkStatus(id = 3, validated = true, metered = true)

        assertEquals(24u, bitrateLimitKbps(hotspot, defaults.copy(mobileDataReducedVoice = true)))
        assertFalse(loadMedia(hotspot, defaults.copy(mobileDataLoadMedia = false)))
    }

    @Test
    fun aNetworkThatAppearsWhileTheEngineWaitsLongReconnectsAtOnce() {
        assertTrue("Back from no network", reconnectsNow(NetworkStatus.None, wifi, reconnecting, running = false))
        assertTrue("From Wi-Fi to mobile data", reconnectsNow(wifi, mobile, reconnecting, running = false))
        assertTrue(
            "A network that has just been checked to work",
            reconnectsNow(wifi.copy(validated = false), wifi, reconnecting, running = false),
        )
    }

    @Test
    fun theFirstAttemptsAreLeftToTheEngine() {
        val early = ConnectionPhase.Reconnecting(attempt = 2, maxAttempts = 10)

        assertFalse(reconnectsNow(wifi, mobile, early, running = false))
    }

    @Test
    fun nothingIsStartedWithoutAReasonOrASecondTime() {
        assertFalse("Still connected", reconnectsNow(wifi, mobile, ConnectionPhase.Connected, running = false))
        assertFalse("Not connected at all", reconnectsNow(wifi, mobile, ConnectionPhase.Idle, running = false))
        assertFalse("The same network as before", reconnectsNow(wifi, wifi, reconnecting, running = false))
        assertFalse("The network went away", reconnectsNow(wifi, NetworkStatus.None, reconnecting, running = false))
        assertFalse("Not checked to work yet", reconnectsNow(wifi, mobile.copy(validated = false), reconnecting, running = false))
        assertFalse("One reconnect runs already", reconnectsNow(wifi, mobile, reconnecting, running = true))
    }

    @Test
    fun theWayToTheOwnChannelIsNamedFromBelowTheRoot() {
        val server = mockupServer()
        val model = ServerModel(
            selfSession = server.selfSession,
            channels = (server.channels + channel(9u, "Raid", parentId = MOCKUP_GAMING)).associateBy { it.id },
            people = server.people.associateBy { it.session },
        )
        fun movedTo(channelId: UInt) = model.copy(
            people = model.people + (MOCKUP_LEA to model.people.getValue(MOCKUP_LEA).copy(channelId = channelId)),
        )

        assertEquals(listOf("Lobby"), model.selfChannelPath())
        assertEquals(listOf("Gaming", "Raid"), movedTo(9u).selfChannelPath())
        assertEquals("The root channel needs no way", emptyList<String>(), movedTo(MOCKUP_ROOT).selfChannelPath())
    }

    // ----- with a session -----

    private class FakeNetwork(initial: NetworkStatus) : NetworkMonitor {
        override val status = MutableStateFlow(initial)
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val logs = mutableListOf<String>()
    private val network = FakeNetwork(wifi)

    private suspend fun TestScope.repository(): SessionRepository = SessionRepository(
        engineFactory = { listener ->
            FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
        },
        scope = backgroundScope,
        engineDispatcher = dispatcher,
        log = { logs += it },
    ).also { it.loadServers() }

    private suspend fun TestScope.connected(password: String? = null): SessionRepository = repository().also {
        it.connectServer(1, password, channelPath = emptyList())
        runCurrent()
    }

    /** What the engine says after it lost the server and failed to get back twice. */
    private fun TestScope.loseTheServer() {
        engine.emit(BridgeEvent.ConnectionChanged(ConnectionState.Reconnecting(3u, 10u, 4u)))
        runCurrent()
    }

    @Test
    fun theLimitIsSetWithTheSessionAndFollowsTheNetworkAndTheSwitch() = runTest(dispatcher) {
        val repository = repository()
        repository.updateMobileSettings { it.copy(mobileDataReducedVoice = true) }
        NetworkSession(repository, network, backgroundScope)
        runCurrent()
        assertNull("Wi-Fi", engine.bitrateLimitKbps)

        network.status.value = mobile
        runCurrent()
        assertEquals(24u, engine.bitrateLimitKbps)

        // The engine forgets the limit when it starts; a connection sets it again.
        engine.forgetBitrateLimit()
        repository.connectServer(1, password = null, channelPath = emptyList())
        runCurrent()
        assertEquals(24u, engine.bitrateLimitKbps)

        repository.updateMobileSettings { it.copy(mobileDataReducedVoice = false) }
        runCurrent()
        assertNull(engine.bitrateLimitKbps)

        repository.updateMobileSettings { it.copy(mobileDataReducedVoice = true) }
        runCurrent()
        network.status.value = wifi
        runCurrent()
        assertNull("Back on Wi-Fi", engine.bitrateLimitKbps)
        assertEquals(null, repository.voiceSettings()?.bitrateLimitKbps)
    }

    @Test
    fun gettingBackInKeepsTheScreensTheChannelTheMuteAndThePasswordOfTheSession() = runTest(dispatcher) {
        val repository = connected(password = "sesame")
        repository.setSelfMute(true)
        runCurrent()
        loseTheServer()
        val phases = mutableListOf<ConnectionPhase>()
        backgroundScope.launch { repository.state.collect { phases += it.phase } }
        runCurrent()

        repository.reconnectNow()
        runCurrent()

        val again = engine.connectCalls.last()
        assertEquals(ConnectCall(1, "sesame", listOf("Lobby")), again)
        assertEquals(ConnectionPhase.Connected, repository.state.value.phase)
        assertTrue("Never shown as left or as a new connection: $phases", phases.none { it == ConnectionPhase.Idle || it == ConnectionPhase.Connecting })
        assertEquals("Muted as before", SelfVoice(muted = true, deafened = false), selfVoice(repository.state.value))
        assertTrue("The password is never logged", logs.none { "sesame" in it })
    }

    @Test
    fun aDeafenedUserIsDeafenedAgain() = runTest(dispatcher) {
        val repository = connected()
        repository.setSelfDeaf(true)
        runCurrent()
        loseTheServer()

        repository.reconnectNow()
        runCurrent()

        assertEquals(SelfVoice.Silenced, selfVoice(repository.state.value))
    }

    @Test
    fun withoutALostConnectionNothingHappens() = runTest(dispatcher) {
        val repository = connected()

        repository.reconnectNow()
        runCurrent()

        assertEquals(1, engine.connectCalls.size)
        assertEquals(0, engine.disconnects)
    }

    @Test
    fun aSecondRequestWhileOneRunsDoesNothing() = runTest(dispatcher) {
        val repository = connected()
        loseTheServer()

        val first = launch { repository.reconnectNow() }
        val second = launch { repository.reconnectNow() }
        runCurrent()

        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals("One connection at the start, one to get back in", 2, engine.connectCalls.size)
        assertEquals(1, engine.disconnects)
    }

    @Test
    fun whenTheServerCannotBeReachedTheConnectionEndsAsLostAndThePasswordIsForgotten() = runTest(dispatcher) {
        val repository = connected(password = "sesame")
        loseTheServer()
        engine.connectFailure = BridgeException.ServerUnreachable("no route to 203.0.113.7")

        repository.reconnectNow()
        runCurrent()

        val state = repository.state.value
        assertEquals("Not stuck in Reconnecting", ConnectionPhase.Failed(ConnectionError.ServerUnreachable), state.phase)
        assertNull(state.server)
        assertEquals("The error can still name the server and try again", 1L, state.activeServerId)
        assertTrue("Neither the address nor the password is logged", logs.none { "203.0.113.7" in it || "sesame" in it })

        repository.connectServer(1, password = null, channelPath = emptyList())
        runCurrent()
        loseTheServer()
        repository.reconnectNow()
        runCurrent()
        assertNull("The password of the lost session is not used again", engine.connectCalls.last().password)
    }

    @Test
    fun aRefusalByTheServerWhileGettingBackInIsShownWithItsOwnReason() = runTest(dispatcher) {
        val repository = connected()
        loseTheServer()
        engine.requiredPassword = "changed"

        repository.reconnectNow()
        runCurrent()

        assertEquals(ConnectionPhase.Failed(ConnectionError.WrongPassword), repository.state.value.phase)
    }

    @Test
    fun leavingAfterwardsForgetsThePassword() = runTest(dispatcher) {
        val repository = connected(password = "sesame")
        repository.disconnect()
        runCurrent()
        repository.connectServer(1, password = null, channelPath = emptyList())
        runCurrent()
        loseTheServer()

        repository.reconnectNow()
        runCurrent()

        assertNull(engine.connectCalls.last().password)
    }

    @Test
    fun aNetworkThatComesBackWhileTheEngineWaitsGetsTheSessionBackAtOnce() = runTest(dispatcher) {
        val repository = connected()
        NetworkSession(repository, network, backgroundScope)
        runCurrent()

        network.status.value = NetworkStatus.None
        runCurrent()
        loseTheServer()
        assertEquals("Nothing to do without a network", 1, engine.connectCalls.size)

        network.status.value = mobile
        runCurrent()

        assertEquals(2, engine.connectCalls.size)
        assertEquals(ConnectionPhase.Connected, repository.state.value.phase)
    }

    @Test
    fun aNetworkChangeWhileConnectedIsLeftToTheEngine() = runTest(dispatcher) {
        val repository = connected()
        NetworkSession(repository, network, backgroundScope)
        runCurrent()

        network.status.value = mobile
        runCurrent()

        assertEquals(1, engine.connectCalls.size)
        assertEquals(0, engine.disconnects)
    }

    @Test
    fun whileGettingBackInTheOrderlyEndAndTheNewAttemptAreNotShown() {
        val state = SessionState(phase = reconnecting, activeServerId = 1)

        assertEquals(state, state.foldResuming(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null))))
        assertEquals(state, state.foldResuming(BridgeEvent.ConnectionChanged(ConnectionState.Connecting)))
        assertEquals(
            ConnectionPhase.Connected,
            state.foldResuming(BridgeEvent.ConnectionChanged(ConnectionState.Connected)).phase,
        )
        assertEquals(
            "A failure is shown",
            ConnectionPhase.Failed(ConnectionError.Banned),
            state.foldResuming(
                BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(BridgeException.Banned("banned"))),
            ).phase,
        )
    }
}
