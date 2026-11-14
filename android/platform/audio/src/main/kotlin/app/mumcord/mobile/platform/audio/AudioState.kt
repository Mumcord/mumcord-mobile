// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

/** The two streams of voice: what the others say (speaker) and what the user says (microphone). */
enum class AudioDirection { Playback, Capture }

/** What a running stream got from the device. The sample rate is always the engine's. */
data class StreamFormat(val sampleRate: Int, val framesPerBurst: Int, val lowLatency: Boolean)

/** Why a stream does not run although it should, in terms the app can put into words. */
enum class AudioFailure {
    /** The phone has no usable microphone right now. */
    MicrophoneUnavailable,

    /** The phone has no usable speaker or headset right now. */
    SpeakerUnavailable,

    /** The engine's library is missing or does not fit; nothing can be played or sent. */
    EngineUnavailable,
}

sealed interface StreamState {
    data object Stopped : StreamState

    data class Running(val format: StreamFormat) : StreamState

    /** Only the microphone: it is wanted, but the user has not allowed it. Listening goes on. */
    data object NoPermission : StreamState

    data class Failed(val reason: AudioFailure) : StreamState
}

data class AudioState(
    val playback: StreamState = StreamState.Stopped,
    val capture: StreamState = StreamState.Stopped,
)
