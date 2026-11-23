// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Disconnect when I'm alone in a channel for 30 minutes" (FR-5.42, AC-5.30): the rule as a
 * value, then the session against the fake engine on the test's own clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleDisconnectTest {
    private val times = IdleTimes()
    private val minute = 60_000L

    // ----- the rule -----

    @Test
    fun theProductWaits30MinutesAndWarnsForOne() {
        assertEquals(30 * minute, times.aloneMillis)
        assertEquals(minute, times.warningMillis)
    }

    @Test
    fun aloneFor30MinutesWarnsAndAnotherMinuteDisconnects() {
        val (alone, none) = IdleState.Resting.step(alone = true, nowMillis = 0, times)
        assertEquals(IdleState.Alone(0), alone)
        assertNull(none)
        assertEquals(30 * minute, alone.wakeAtMillis(times))

        assertEquals(alone to null, alone.step(alone = true, nowMillis = 30 * minute - 1, times))

        val (warned, warn) = alone.step(alone = true, nowMillis = 30 * minute, times)
        assertEquals(IdleState.Warned(untilMillis = 31 * minute), warned)
        assertEquals(IdleEffect.Warn, warn)
        assertEquals(31 * minute, warned.wakeAtMillis(times))

        assertEquals(warned to null, warned.step(alone = true, nowMillis = 31 * minute - 1, times))
        assertEquals(IdleState.Resting to IdleEffect.Disconnect, warned.step(alone = true, nowMillis = 31 * minute, times))
    }

    @Test
    fun someoneJoiningStartsOverAndTakesTheWarningAway() {
        val alone = IdleState.Alone(0)
        assertEquals(IdleState.Resting to null, alone.step(alone = false, nowMillis = 29 * minute, times))
        assertEquals(
            "Alone again later: the 30 minutes count from then",
            IdleState.Alone(40 * minute),
            IdleState.Resting.step(alone = true, nowMillis = 40 * minute, times).first,
        )

        val warned = IdleState.Warned(untilMillis = 31 * minute)
        assertEquals(IdleState.Resting to IdleEffect.Unwarn, warned.step(alone = false, nowMillis = 30 * minute + 5, times))
        assertNull(IdleState.Resting.wakeAtMillis(times))
    }

    @Test
    fun stayConnectedTakesTheWarningAwayAndStartsOver() {
        val warned = IdleState.Warned(untilMillis = 31 * minute)

        assertEquals(IdleState.Alone(30 * minute + 20) to IdleEffect.Unwarn, warned.stay(30 * minute + 20))
        assertEquals("Without a warning there is nothing to stay for", IdleState.Alone(5) to null, IdleState.Alone(5).stay(99))
    }

    @Test
    fun aLateLookStillWarnsFirstAndGivesTheFullMinute() {
        // The phone slept past both times: the warning comes first, the minute starts then.
        val (warned, warn) = IdleState.Alone(0).step(alone = true, nowMillis = 45 * minute, times)

        assertEquals(IdleEffect.Warn, warn)
        assertEquals(IdleState.Warned(untilMillis = 46 * minute), warned)
    }

    @Test
    fun aloneMeansConnectedAndTheOnlyOneInTheOwnChannel() {
        fun session(server: app.mumcord.mobile.bridge.ServerState, connection: ConnectionState = ConnectionState.Connected) =
            listOf(BridgeEvent.ConnectionChanged(ConnectionState.Connected), BridgeEvent.ServerSynced(server), BridgeEvent.ConnectionChanged(connection))
                .fold(SessionState()) { state, event -> state.fold(event) }
        val full = mockupVoiceServer()
        val onlyLea = full.copy(people = full.people.map { if (it.isSelf) it else it.copy(channelId = MOCKUP_GAMING) })

        assertFalse(aloneInChannel(session(full)))
        assertTrue("The others are on the server, in another channel", aloneInChannel(session(onlyLea)))
        assertFalse("Not while the connection is being restored", aloneInChannel(session(onlyLea, ConnectionState.Reconnecting(1u, 10u, 1u))))
        assertFalse(aloneInChannel(SessionState()))
    }

    // ----- with a session -----

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val notifier = FakeIdleNotifier()

    /** Connected to a server where the user is alone in the Lobby; the others are in Gaming. */
    private suspend fun TestScope.alone(enabled: Boolean = true): Pair<SessionRepository, IdleDisconnect> {
        val server = mockupVoiceServer().let { all ->
            all.copy(people = all.people.map { if (it.isSelf) it else it.copy(channelId = MOCKUP_GAMING) })
        }
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(savedServer(1, "Nordlicht"))).also {
                    it.mobileSettings = it.mobileSettings.copy(idleDisconnect = enabled)
                    engine = it
                }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        val idle = IdleDisconnect(repository, notifier, backgroundScope, clock = { currentTime })
        repository.connectServer(1, password = null, channelPath = emptyList())
        runCurrent()
        return repository to idle
    }

    @Test
    fun withoutATapTheAppWarnsThenDisconnectsAndSaysSo() = runTest(dispatcher) {
        val (repository, idle) = alone()

        advanceTimeBy(30 * minute - 1)
        runCurrent()
        assertFalse(notifier.warning)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("Disconnecting in 1 minute", notifier.warning)
        assertTrue(repository.state.value.showsServer)

        advanceTimeBy(minute)
        runCurrent()
        assertFalse(repository.state.value.showsServer)
        assertEquals(1, engine.disconnects)
        assertFalse("The warning is gone", notifier.warning)
        assertTrue(notifier.disconnected)
        assertEquals(IdleNotice(1, "Nordlicht"), idle.notice.value)

        idle.dismiss()
        assertNull(idle.notice.value)
        assertFalse(notifier.disconnected)
    }

    @Test
    fun stayConnectedKeepsTheSessionAndTheTimeStartsOver() = runTest(dispatcher) {
        val (repository, idle) = alone()
        advanceTimeBy(30 * minute)
        runCurrent()
        assertTrue(notifier.warning)

        advanceTimeBy(30_000)
        idle.stayConnected()
        runCurrent()
        assertFalse(notifier.warning)

        advanceTimeBy(29 * minute)
        runCurrent()
        assertTrue("Still connected long after the first warning would have ended", repository.state.value.showsServer)
        assertEquals(listOf("warning"), notifier.shown)

        advanceTimeBy(minute)
        runCurrent()
        assertEquals("30 minutes after the tap it warns again", listOf("warning", "warning"), notifier.shown)
    }

    @Test
    fun someoneJoiningTakesTheWarningAwayAndTheTimeStartsOver() = runTest(dispatcher) {
        val (repository, _) = alone()
        advanceTimeBy(30 * minute)
        runCurrent()
        assertTrue(notifier.warning)

        engine.change(MOCKUP_ANNA) { it.copy(channelId = MOCKUP_LOBBY) }
        runCurrent()
        assertFalse(notifier.warning)

        advanceTimeBy(60 * minute)
        runCurrent()
        assertTrue(repository.state.value.showsServer)

        engine.change(MOCKUP_ANNA) { it.copy(channelId = MOCKUP_GAMING) }
        runCurrent()
        advanceTimeBy(30 * minute)
        runCurrent()
        assertEquals("Alone again: 30 minutes from then", listOf("warning", "warning"), notifier.shown)
    }

    @Test
    fun withTheSettingOffNothingEverHappens() = runTest(dispatcher) {
        val (repository, _) = alone(enabled = false)

        advanceTimeBy(120 * minute)
        runCurrent()

        assertTrue(repository.state.value.showsServer)
        assertTrue(notifier.shown.isEmpty())
    }

    @Test
    fun switchingTheSettingOffDuringTheWarningKeepsTheSession() = runTest(dispatcher) {
        val (repository, _) = alone()
        advanceTimeBy(30 * minute)
        runCurrent()

        repository.updateMobileSettings { it.copy(idleDisconnect = false) }
        runCurrent()
        assertFalse(notifier.warning)

        advanceTimeBy(10 * minute)
        runCurrent()
        assertTrue(repository.state.value.showsServer)
    }

    @Test
    fun leavingDuringTheWarningTakesItAwayAndLeavesNoNotice() = runTest(dispatcher) {
        val (repository, idle) = alone()
        advanceTimeBy(30 * minute)
        runCurrent()

        repository.disconnect()
        runCurrent()
        advanceTimeBy(5 * minute)
        runCurrent()

        assertFalse(notifier.warning)
        assertEquals(listOf("warning"), notifier.shown)
        assertNull(idle.notice.value)
    }

    @Test
    fun aShortenedTimeIsReadWhenTheWaitBegins() = runTest(dispatcher) {
        val server = mockupVoiceServer().let { all ->
            all.copy(people = all.people.map { if (it.isSelf) it else it.copy(channelId = MOCKUP_GAMING) })
        }
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, servers = listOf(savedServer(1, "Nordlicht"))).also {
                    it.mobileSettings = it.mobileSettings.copy(idleDisconnect = true)
                    engine = it
                }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        IdleDisconnect(repository, notifier, backgroundScope, clock = { currentTime }) { IdleTimes(20_000, 10_000) }
        repository.connectServer(1, password = null, channelPath = emptyList())
        runCurrent()

        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(notifier.warning)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(repository.state.value.showsServer)
    }
}
