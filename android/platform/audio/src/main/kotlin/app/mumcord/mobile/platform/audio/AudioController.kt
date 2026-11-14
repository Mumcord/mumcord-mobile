// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the speaker and the microphone stream exactly while the engine wants them. The app only
 * says what is wanted ([setWanted]) and where the samples go ([setEndpoints]); [state] says what
 * came of it.
 *
 * The microphone is opened only with the user's permission; without it [state] says so and the
 * speaker keeps running. A stream that is lost (a headset is plugged in or pulled out) or cannot
 * be opened is tried again a few times before it counts as failed.
 *
 * @param scope must run on a single thread: everything this class does happens there, off the
 *   main thread (opening a stream takes a moment) and never on an audio thread.
 * @param microphoneGranted whether the user allows the microphone right now.
 * @param log receives short technical messages without personal data.
 */
class AudioController(
    private val backend: StreamBackend,
    private val session: VoiceSession,
    private val microphoneGranted: () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = mutableState.asStateFlow()

    private val wanted = mutableMapOf(AudioDirection.Playback to false, AudioDirection.Capture to false)
    /** Empty until the engine exists and has said where its samples go. */
    private val handles = mutableMapOf<AudioDirection, Long>()

    /** The streams that are open, with the generation the backend gave them. */
    private val open = mutableMapOf<AudioDirection, Int>()
    private val retries = mutableMapOf<AudioDirection, Job>()
    private var sessionActive = false

    init {
        backend.onLost = { direction, generation -> scope.launch { lost(direction, generation) } }
    }

    /** The engine's handles for the two directions (`MumcordEngine.audioEndpoints()`). */
    fun setEndpoints(capture: Long, playback: Long) {
        scope.launch {
            handles[AudioDirection.Capture] = capture
            handles[AudioDirection.Playback] = playback
            reconcile()
        }
    }

    /** What the engine wants to run right now (`AudioWanted`); each direction on its own. */
    fun setWanted(playback: Boolean, capture: Boolean) {
        scope.launch {
            wanted[AudioDirection.Playback] = playback
            wanted[AudioDirection.Capture] = capture
            reconcile()
        }
    }

    /** Looks at the microphone permission again, after the user answered or came back to the app. */
    fun refreshPermission() {
        scope.launch { reconcile() }
    }

    private fun needed(direction: AudioDirection): Boolean =
        wanted.getValue(direction) && direction in handles

    private fun reconcile() {
        val playback = needed(AudioDirection.Playback)
        val captureNeeded = needed(AudioDirection.Capture)
        val capture = captureNeeded && microphoneGranted()
        if ((playback || capture) && !sessionActive) {
            // Before the streams: the mode decides which device and processing they get.
            session.begin()
            sessionActive = true
        }
        apply(AudioDirection.Playback, playback, StreamState.Stopped)
        apply(
            AudioDirection.Capture,
            capture,
            if (captureNeeded) StreamState.NoPermission else StreamState.Stopped,
        )
        if (!playback && !capture && sessionActive) {
            session.end()
            sessionActive = false
        }
    }

    private fun apply(direction: AudioDirection, run: Boolean, offState: StreamState) {
        if (!run) {
            retries.remove(direction)?.cancel()
            if (open.remove(direction) != null) backend.close(direction)
            set(direction, offState)
        } else if (direction !in open && direction !in retries) {
            open(direction, attempt = 0)
        }
    }

    private fun open(direction: AudioDirection, attempt: Int) {
        when (val result = backend.open(direction, handles.getValue(direction))) {
            is OpenResult.Opened -> {
                open[direction] = result.generation
                set(direction, StreamState.Running(result.format))
            }
            is OpenResult.Failed -> {
                log("opening the ${direction.name.lowercase()} stream failed (${result.code}), attempt ${attempt + 1}")
                val delayMillis = RETRY_DELAYS_MILLIS.getOrNull(attempt)
                if (delayMillis == null || result.engineMissing) {
                    set(direction, StreamState.Failed(failure(direction, result)))
                } else {
                    // A device that is just being switched is often back a moment later.
                    retries[direction] = scope.launch {
                        delay(delayMillis)
                        retries.remove(direction)
                        open(direction, attempt + 1)
                    }
                }
            }
        }
    }

    private fun lost(direction: AudioDirection, generation: Int) {
        // An error of a stream that has been closed or replaced meanwhile.
        if (open[direction] != generation) return
        log("the ${direction.name.lowercase()} stream was lost, reopening it")
        open.remove(direction)
        backend.close(direction)
        reconcile()
    }

    private fun failure(direction: AudioDirection, result: OpenResult.Failed): AudioFailure = when {
        result.engineMissing -> AudioFailure.EngineUnavailable
        direction == AudioDirection.Capture -> AudioFailure.MicrophoneUnavailable
        else -> AudioFailure.SpeakerUnavailable
    }

    private fun set(direction: AudioDirection, state: StreamState) = mutableState.update {
        when (direction) {
            AudioDirection.Playback -> it.copy(playback = state)
            AudioDirection.Capture -> it.copy(capture = state)
        }
    }

    private companion object {
        /** Waits before the second, third and fourth try to open a stream. */
        val RETRY_DELAYS_MILLIS = listOf(250L, 1_000L, 2_000L)
    }
}
