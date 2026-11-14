// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

/** Whether the user is muted and deafened on the server, as they set it themselves. */
data class SelfVoice(val muted: Boolean, val deafened: Boolean) {
    companion object {
        /** What a phone call makes of the user: deafened, which includes muted. */
        val Silenced = SelfVoice(muted = true, deafened = true)
    }
}

/** How the phone took the audio focus away from the voice session. */
enum class FocusLoss {
    /** For a moment (an alarm, a navigation prompt); the focus comes back. */
    Transient,

    /** For a moment, and the session may keep playing more quietly. */
    CanDuck,

    /** For good: another app plays now. */
    Permanent,
}

/** What the phone reports while a voice session runs. */
sealed interface InterruptionEvent {
    /** The system put the session's call on hold, or took it off hold again. */
    data class CallHeld(val held: Boolean) : InterruptionEvent

    /** A normal phone call is being talked on (not merely ringing), or no longer. */
    data class PhoneCall(val active: Boolean) : InterruptionEvent

    /** [ringing] is true while the phone only rings for an incoming call. */
    data class FocusLost(val kind: FocusLoss, val ringing: Boolean) : InterruptionEvent

    data object FocusGained : InterruptionEvent

    /** The voice session is over; nothing is restored on a server the user has left. */
    data object SessionEnded : InterruptionEvent
}

/** What the app has to do about an interruption. */
sealed interface InterruptionEffect {
    /** Mute and deafen on the server, or put back what the user had before. */
    data class SetVoice(val voice: SelfVoice) : InterruptionEffect

    /** Stop or resume playing on this phone only; the others see no change. */
    data class PausePlayback(val paused: Boolean) : InterruptionEffect
}

/**
 * The rules for interruptions (FR-5.33, FR-5.36) as a value without any Android in it.
 *
 * An accepted phone call mutes and deafens for its duration and puts the previous state back
 * afterwards, unless the user changed it meanwhile. A call that only rings changes nothing.
 * Losing the audio focus for a moment pauses playback until the focus is back; losing it for
 * good to another app changes nothing, like a call that goes on while music starts.
 *
 * @param held the system holds the session's call.
 * @param phoneCall the phone's audio is in a normal phone call.
 * @param saved what the user had before the phone call, while one is going on.
 */
data class InterruptionState(
    val held: Boolean = false,
    val phoneCall: Boolean = false,
    val saved: SelfVoice? = null,
    val playbackPaused: Boolean = false,
) {
    private val inCall: Boolean
        get() = held || phoneCall

    /**
     * The state after [event] and what to do now. [current] is what the user is set to on the
     * server at this moment, or null while that is unknown.
     */
    fun step(event: InterruptionEvent, current: SelfVoice?): Pair<InterruptionState, List<InterruptionEffect>> =
        when (event) {
            is InterruptionEvent.CallHeld -> call(copy(held = event.held), current)
            is InterruptionEvent.PhoneCall -> call(copy(phoneCall = event.active), current)
            is InterruptionEvent.FocusLost -> when {
                // The phone call silences everything already, and ringing must not interrupt.
                inCall || event.ringing -> this to emptyList()
                event.kind == FocusLoss.Transient && !playbackPaused -> pause(true)
                // No focus will come back to end the pause.
                event.kind == FocusLoss.Permanent && playbackPaused -> pause(false)
                else -> this to emptyList()
            }
            InterruptionEvent.FocusGained -> if (playbackPaused) pause(false) else this to emptyList()
            InterruptionEvent.SessionEnded ->
                InterruptionState() to if (playbackPaused) listOf(InterruptionEffect.PausePlayback(false)) else emptyList()
        }

    private fun pause(paused: Boolean) =
        copy(playbackPaused = paused) to listOf(InterruptionEffect.PausePlayback(paused))

    private fun call(next: InterruptionState, current: SelfVoice?): Pair<InterruptionState, List<InterruptionEffect>> =
        when {
            next.inCall == inCall || current == null -> next to emptyList()
            next.inCall -> next.copy(saved = current) to
                if (current == SelfVoice.Silenced) emptyList() else listOf(InterruptionEffect.SetVoice(SelfVoice.Silenced))
            else -> next.copy(saved = null) to
                // Only what the phone call itself did is undone: a change by the user stays.
                if (saved != null && saved != current && current == SelfVoice.Silenced) {
                    listOf(InterruptionEffect.SetVoice(saved))
                } else {
                    emptyList()
                }
        }
}
