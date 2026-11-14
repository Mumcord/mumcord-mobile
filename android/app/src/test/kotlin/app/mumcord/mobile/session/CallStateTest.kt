// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.platform.call.CallInfo
import app.mumcord.mobile.platform.call.InterruptionEffect
import app.mumcord.mobile.platform.call.InterruptionEvent
import app.mumcord.mobile.platform.call.Interruptions
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.platform.call.SessionMarker
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

/** The session as the phone system sees it, and what reaches the engine from outside the screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallStateTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine

    private fun TestScope.repository(talkMode: TalkMode = TalkMode.VOICE_ACTIVITY) = SessionRepository(
        engineFactory = { listener ->
            FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht")), talkMode = talkMode)
                .also { engine = it }
        },
        scope = backgroundScope,
        engineDispatcher = dispatcher,
        log = {},
    )

    private suspend fun TestScope.connected(talkMode: TalkMode = TalkMode.VOICE_ACTIVITY): SessionRepository =
        repository(talkMode).also {
            it.loadServers()
            it.connectServer(1, password = null, channelPath = emptyList())
            runCurrent()
        }

    @Test
    fun withoutAConnectionThereIsNoCallAndNothingToRemember() = runTest(dispatcher) {
        val repository = repository()
        repository.loadServers()
        runCurrent()
        assertNull(callInfo(repository.state.value))
        assertNull(selfVoice(repository.state.value))
        assertEquals(MarkerStep.Clear, markerStep(repository.state.value))
    }

    @Test
    fun aConnectionIsACallNamedAfterServerAndChannelAndIsRemembered() = runTest(dispatcher) {
        val repository = connected()
        assertEquals(CallInfo("Nordlicht", "Lobby", muted = false, deafened = false), callInfo(repository.state.value))
        assertEquals(MarkerStep.Mark(SessionMarker(1, "Nordlicht")), markerStep(repository.state.value))

        repository.disconnect()
        runCurrent()
        assertNull(callInfo(repository.state.value))
        assertEquals(MarkerStep.Clear, markerStep(repository.state.value))
    }

    @Test
    fun theNotificationsButtonsMuteDeafenAndLeave() = runTest(dispatcher) {
        val repository = connected()
        val control = SessionVoiceControl(repository, backgroundScope)

        control.toggleMute()
        runCurrent()
        assertTrue(engine.selfMuted)
        assertEquals(SelfVoice(muted = true, deafened = false), control.current())
        assertTrue(callInfo(repository.state.value)!!.muted)

        control.toggleDeafen()
        runCurrent()
        assertTrue(engine.selfDeafened)
        assertEquals(SelfVoice.Silenced, control.current())
        control.toggleDeafen()
        runCurrent()
        assertFalse(engine.selfDeafened)

        control.leave()
        runCurrent()
        assertFalse(repository.state.value.showsServer)
    }

    @Test
    fun inPushToTalkModeNoButtonOfTheNotificationOpensTheMicrophone() = runTest(dispatcher) {
        val repository = connected(TalkMode.PUSH_TO_TALK)
        val control = SessionVoiceControl(repository, backgroundScope)
        control.toggleMute()
        control.toggleMute()
        control.toggleDeafen()
        control.toggleDeafen()
        runCurrent()
        assertTrue("Only the hold button on screen talks", engine.pushToTalkCalls.isEmpty())
    }

    @Test
    fun aPhoneCallMutesAndDeafensOnTheServerAndItsEndRestoresWhatTheUserHad() = runTest(dispatcher) {
        val repository = connected()
        val control = SessionVoiceControl(repository, backgroundScope)
        val paused = mutableListOf<Boolean>()
        val interruptions = Interruptions(backgroundScope, control::current) { effect ->
            when (effect) {
                is InterruptionEffect.SetVoice -> control.set(effect.voice)
                is InterruptionEffect.PausePlayback -> paused += effect.paused
            }
        }
        control.toggleMute()
        runCurrent()

        interruptions.send(InterruptionEvent.CallHeld(true))
        runCurrent()
        assertEquals(SelfVoice.Silenced, control.current())
        assertTrue("The user stays on the server", repository.state.value.showsServer)

        interruptions.send(InterruptionEvent.CallHeld(false))
        runCurrent()
        assertEquals("Muted before, muted again", SelfVoice(muted = true, deafened = false), control.current())
        assertTrue(paused.isEmpty())
    }
}
