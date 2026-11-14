// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Show sender only" in the engine's phone settings, and the move out of the app's preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineMessageNotificationSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine

    private class Stored(var value: Boolean?) : StoredSenderOnly {
        override fun read(): Boolean? = value

        override fun clear() {
            value = null
        }
    }

    private fun TestScope.settings(
        stored: Stored = Stored(null),
        configure: (FakeEngine) -> Unit = {},
    ): EngineMessageNotificationSettings {
        val repository = SessionRepository(
            engineFactory = { listener -> FakeEngine(listener, mockupServer()).also(configure).also { engine = it } },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return EngineMessageNotificationSettings(repository, stored, backgroundScope)
    }

    @Test
    fun theSwitchShowsWhatTheEngineKeeps() = runTest(dispatcher) {
        val settings = settings { it.mobileSettings = it.mobileSettings.copy(notifySenderOnly = true) }
        assertFalse("not known before the engine answered", settings.senderOnly.value)

        runCurrent()

        assertTrue(settings.senderOnly.value)
    }

    @Test
    fun aChangeIsSavedInTheEngineAndLeavesTheOtherSettingsAlone() = runTest(dispatcher) {
        val settings = settings { it.mobileSettings = it.mobileSettings.copy(idleDisconnect = true) }
        runCurrent()

        settings.setSenderOnly(true)
        assertTrue("the switch does not wait for the engine", settings.senderOnly.value)
        runCurrent()

        assertTrue(engine.mobileSettings.notifySenderOnly)
        assertTrue(engine.mobileSettings.idleDisconnect)

        settings.setSenderOnly(false)
        runCurrent()
        assertFalse(engine.mobileSettings.notifySenderOnly)
    }

    @Test
    fun aChoiceOfAnEarlierVersionIsMovedIntoTheEngineOnce() = runTest(dispatcher) {
        val stored = Stored(true)
        val settings = settings(stored)
        assertTrue("known at once, before the engine exists", settings.senderOnly.value)

        runCurrent()

        assertTrue(engine.mobileSettings.notifySenderOnly)
        assertNull("forgotten on the phone", stored.value)
        assertTrue(settings.senderOnly.value)
    }

    @Test
    fun aChoiceThatCouldNotBeMovedStaysForTheNextStart() = runTest(dispatcher) {
        val stored = Stored(true)
        val settings = settings(stored) { it.mobileSettingsFailure = BridgeException.Io("disk") }
        runCurrent()

        assertEquals(true, stored.value)
        assertTrue(settings.senderOnly.value)
        assertFalse(engine.mobileSettings.notifySenderOnly)
    }

    @Test
    fun aChoiceMadeWhileTheEngineIsStillAskedWins() = runTest(dispatcher) {
        val settings = settings()

        settings.setSenderOnly(true)
        runCurrent()

        assertTrue(settings.senderOnly.value)
        assertTrue(engine.mobileSettings.notifySenderOnly)
    }
}
