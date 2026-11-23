// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import kotlinx.coroutines.flow.StateFlow

/** How the system prompt for the microphone ended, as far as the system tells. */
data class MicrophoneAnswer(
    val granted: Boolean,
    /** Whether the system said, before and after the prompt, that the user has refused before. */
    val refusedBefore: Boolean,
    val refusedAfter: Boolean,
) {
    /**
     * After a second refusal the system stops showing its prompt, which it only signals by no
     * longer reporting the earlier refusal. A prompt closed without an answer looks different:
     * no refusal was known before it either.
     */
    val blocksPrompt: Boolean
        get() = !granted && refusedBefore && !refusedAfter
}

/**
 * What the microphone question needs from the system and remembers about the user's answers.
 * Everything platform-specific stays behind it, so the flow can be tested without a device.
 */
interface MicrophoneGateway {
    fun granted(): Boolean

    /** The user has said no once ("Not now" or in the system's prompt): do not explain again. */
    var declined: Boolean

    /** Whether the system no longer shows its prompt, so only the system settings help. */
    var promptBlocked: Boolean
}

/** What the connected screens show about the microphone. */
enum class MicrophoneNotice {
    None,

    /** Say why the microphone is needed, then let the system ask. */
    Explain,

    /** The user said no: the others cannot hear them; tapping lets the system ask again. */
    Denied,

    /** As [Denied], but the system will not ask any more: point to the system settings. */
    Blocked,
}

/**
 * [missing] is true while the engine wants the microphone and the permission is not there. The
 * explanation is shown once; after a "no" only the calm notice stays.
 */
fun microphoneNotice(missing: Boolean, declined: Boolean, promptBlocked: Boolean): MicrophoneNotice = when {
    !missing -> MicrophoneNotice.None
    promptBlocked -> MicrophoneNotice.Blocked
    declined -> MicrophoneNotice.Denied
    else -> MicrophoneNotice.Explain
}

/** The microphone question as the screens see it; the audio itself runs at process scope. */
interface MicrophoneAccess {
    val notice: StateFlow<MicrophoneNotice>

    /** The user put the explanation away without letting the system ask. */
    fun notNow()

    fun answer(answer: MicrophoneAnswer)

    /** The app is on screen again: the permission may have changed in the system settings. */
    fun recheck()
}
