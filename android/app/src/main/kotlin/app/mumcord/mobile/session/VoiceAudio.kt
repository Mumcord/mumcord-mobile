// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.platform.audio.AudioController
import app.mumcord.mobile.platform.audio.StreamState
import app.mumcord.mobile.ui.connected.MicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneAnswer
import app.mumcord.mobile.ui.connected.MicrophoneGateway
import app.mumcord.mobile.ui.connected.MicrophoneNotice
import app.mumcord.mobile.ui.connected.microphoneNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Makes voice audible: runs the phone's speaker and microphone exactly while the engine wants
 * them, and keeps track of the microphone question. It lives as long as the process, so audio
 * goes on while the screens are recreated.
 *
 * @param scope lives as long as the process.
 */
class VoiceAudio(
    private val repository: SessionRepository,
    private val controller: AudioController,
    private val gateway: MicrophoneGateway,
    scope: CoroutineScope,
) : MicrophoneAccess {
    private data class Answers(val declined: Boolean, val promptBlocked: Boolean)

    private val answers = MutableStateFlow(Answers(gateway.declined, gateway.promptBlocked))

    override val notice: StateFlow<MicrophoneNotice> =
        combine(controller.state, answers) { audio, answers ->
            microphoneNotice(
                missing = audio.capture == StreamState.NoPermission,
                declined = answers.declined,
                promptBlocked = answers.promptBlocked,
            )
        }.stateIn(scope, SharingStarted.Eagerly, MicrophoneNotice.None)

    init {
        scope.launch {
            var endpointsKnown = false
            repository.state.map { it.audio }.distinctUntilChanged().collect { wanted ->
                if (!endpointsKnown && (wanted.playback || wanted.capture)) {
                    // The engine exists by now; its handles stay valid for the whole process.
                    repository.audioEndpoints()?.let {
                        controller.setEndpoints(capture = it.capture.toLong(), playback = it.playback.toLong())
                        endpointsKnown = true
                    }
                }
                controller.setWanted(playback = wanted.playback, capture = wanted.capture)
            }
        }
    }

    override fun notNow() = remember(declined = true, promptBlocked = answers.value.promptBlocked)

    override fun answer(answer: MicrophoneAnswer) {
        if (answer.granted) {
            remember(declined = false, promptBlocked = false)
            controller.refreshPermission()
        } else {
            remember(declined = true, promptBlocked = answer.blocksPrompt)
        }
    }

    override fun recheck() {
        // Allowed in the system settings: the prompt is possible again should it be taken back.
        if (gateway.granted()) remember(declined = false, promptBlocked = false)
        controller.refreshPermission()
    }

    private fun remember(declined: Boolean, promptBlocked: Boolean) {
        val next = Answers(declined, promptBlocked)
        if (answers.value == next) return
        gateway.declined = declined
        gateway.promptBlocked = promptBlocked
        answers.value = next
    }
}
