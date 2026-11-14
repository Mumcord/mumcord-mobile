// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptionStateTest {
    private val talking = SelfVoice(muted = false, deafened = false)
    private val muted = SelfVoice(muted = true, deafened = false)

    /** Feeds the events one after the other; the user's state follows every [InterruptionEffect.SetVoice]. */
    private class Run(var voice: SelfVoice?) {
        var state = InterruptionState()
        val effects = mutableListOf<InterruptionEffect>()

        fun send(vararg events: InterruptionEvent): Run {
            for (event in events) {
                val (next, now) = state.step(event, voice)
                state = next
                effects += now
                now.filterIsInstance<InterruptionEffect.SetVoice>().forEach { voice = it.voice }
            }
            return this
        }
    }

    private fun setVoice(voice: SelfVoice) = InterruptionEffect.SetVoice(voice)

    @Test
    fun anAcceptedPhoneCallMutesAndDeafensAndItsEndPutsThePreviousStateBack() {
        val run = Run(talking).send(InterruptionEvent.CallHeld(true))
        assertEquals(listOf(setVoice(SelfVoice.Silenced)), run.effects)

        run.send(InterruptionEvent.CallHeld(false))
        assertEquals(listOf(setVoice(SelfVoice.Silenced), setVoice(talking)), run.effects)
        assertEquals(InterruptionState(), run.state)
    }

    @Test
    fun aUserWhoWasMutedBeforeThePhoneCallIsMutedAgainAfterIt() {
        val run = Run(muted).send(InterruptionEvent.PhoneCall(true), InterruptionEvent.PhoneCall(false))
        assertEquals(listOf(setVoice(SelfVoice.Silenced), setVoice(muted)), run.effects)
    }

    @Test
    fun aUserWhoWasDeafenedAlreadyIsLeftAlone() {
        val run = Run(SelfVoice.Silenced).send(InterruptionEvent.CallHeld(true), InterruptionEvent.CallHeld(false))
        assertEquals(emptyList<InterruptionEffect>(), run.effects)
    }

    @Test
    fun theHoldAndThePhonesCallModeAreOneCallHoweverTheyOverlap() {
        val run = Run(talking).send(
            InterruptionEvent.PhoneCall(true),
            InterruptionEvent.CallHeld(true),
            InterruptionEvent.PhoneCall(false),
        )
        assertEquals("still held", listOf(setVoice(SelfVoice.Silenced)), run.effects)

        run.send(InterruptionEvent.CallHeld(false))
        assertEquals(listOf(setVoice(SelfVoice.Silenced), setVoice(talking)), run.effects)
    }

    @Test
    fun whatTheUserChangesDuringThePhoneCallStays() {
        val run = Run(talking).send(InterruptionEvent.CallHeld(true))
        // The user undeafens from the notification while still on the phone.
        run.voice = muted
        run.send(InterruptionEvent.CallHeld(false))
        assertEquals(listOf(setVoice(SelfVoice.Silenced)), run.effects)
    }

    @Test
    fun aRingingPhoneInterruptsNothing() {
        val run = Run(talking).send(InterruptionEvent.FocusLost(FocusLoss.Transient, ringing = true))
        assertEquals(emptyList<InterruptionEffect>(), run.effects)
        assertEquals(InterruptionState(), run.state)
    }

    @Test
    fun anAlarmPausesPlaybackUntilTheFocusIsBack() {
        val run = Run(talking).send(InterruptionEvent.FocusLost(FocusLoss.Transient, ringing = false))
        assertEquals(listOf(InterruptionEffect.PausePlayback(true)), run.effects)

        run.send(InterruptionEvent.FocusGained)
        assertEquals(
            listOf(InterruptionEffect.PausePlayback(true), InterruptionEffect.PausePlayback(false)),
            run.effects,
        )
    }

    @Test
    fun aPromptThatAllowsDuckingAndAnotherAppTakingOverChangeNothing() {
        val run = Run(talking).send(
            InterruptionEvent.FocusLost(FocusLoss.CanDuck, ringing = false),
            InterruptionEvent.FocusLost(FocusLoss.Permanent, ringing = false),
            InterruptionEvent.FocusGained,
        )
        assertEquals(emptyList<InterruptionEffect>(), run.effects)
    }

    @Test
    fun aPauseDoesNotOutliveAFocusThatIsGoneForGood() {
        val run = Run(talking).send(
            InterruptionEvent.FocusLost(FocusLoss.Transient, ringing = false),
            InterruptionEvent.FocusLost(FocusLoss.Permanent, ringing = false),
        )
        assertEquals(
            listOf(InterruptionEffect.PausePlayback(true), InterruptionEffect.PausePlayback(false)),
            run.effects,
        )
    }

    @Test
    fun theFocusLostToThePhoneCallItselfDoesNotPauseOnTopOfTheDeafen() {
        val run = Run(talking).send(
            InterruptionEvent.CallHeld(true),
            InterruptionEvent.FocusLost(FocusLoss.Transient, ringing = false),
        )
        assertEquals(listOf(setVoice(SelfVoice.Silenced)), run.effects)
    }

    @Test
    fun theEndOfTheSessionForgetsThePhoneCallAndEndsAPause() {
        val run = Run(talking).send(
            InterruptionEvent.FocusLost(FocusLoss.Transient, ringing = false),
            InterruptionEvent.CallHeld(true),
            InterruptionEvent.SessionEnded,
        )
        assertEquals(InterruptionState(), run.state)
        assertEquals(InterruptionEffect.PausePlayback(false), run.effects.last())

        // The phone call ends after the user has left: nothing is restored anywhere.
        run.voice = null
        val before = run.effects.size
        run.send(InterruptionEvent.CallHeld(false))
        assertEquals(before, run.effects.size)
    }

    @Test
    fun withoutASessionNothingIsMuted() {
        val run = Run(null).send(InterruptionEvent.PhoneCall(true), InterruptionEvent.PhoneCall(false))
        assertEquals(emptyList<InterruptionEffect>(), run.effects)
    }
}
