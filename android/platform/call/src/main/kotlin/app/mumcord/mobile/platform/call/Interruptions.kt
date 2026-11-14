// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs the interruption rules ([InterruptionState]): takes what the phone reports, one event
 * after the other, and has the app carry out what follows.
 *
 * @param current what the user is set to on the server right now, or null without a session.
 * @param apply carries out one effect; the next event waits until it is done.
 */
class Interruptions(
    scope: CoroutineScope,
    private val current: () -> SelfVoice?,
    private val apply: suspend (InterruptionEffect) -> Unit,
) {
    private val events = Channel<InterruptionEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            var state = InterruptionState()
            for (event in events) {
                val (next, effects) = state.step(event, current())
                state = next
                effects.forEach { apply(it) }
            }
        }
    }

    fun send(event: InterruptionEvent) {
        events.trySend(event)
    }
}
