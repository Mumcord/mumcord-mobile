// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

/**
 * Test probe for automated tests on a device without a microphone or a listener. It exists only
 * in debug builds: neither this class nor its part of the C++ library is in a release build.
 */
object AudioProbe {
    init {
        // The probe's functions live in the same library as the streams.
        NativeAudio
    }

    /** What the microphone stream sends to the engine in place of what the device recorded. */
    enum class Signal {
        /** A generated signal that the engine treats as somebody talking. */
        Speech,

        /** Nothing at all; an emulator's microphone delivers a test tone of its own. */
        Silence,
    }

    /** Replaces the microphone's input by [signal]; null gives the device's own input back. */
    fun replaceMicrophone(signal: Signal?) = nativeReplaceMicrophone(if (signal == null) 0 else signal.ordinal + 1)

    /**
     * The loudest block (RMS, 0 to 1) the speaker stream got from the engine since the last
     * call; 0 for silence.
     */
    fun takePlaybackLevel(): Float = nativeTakeLevel()

    /** How many frames the stream of [direction] has exchanged with the engine so far. */
    fun frames(direction: AudioDirection): Long = nativeFrames(direction.ordinal)

    /** Makes the running stream of [direction] report that it lost its device. */
    fun loseStream(direction: AudioDirection) = nativeLoseStream(direction.ordinal)

    private external fun nativeReplaceMicrophone(signal: Int)

    private external fun nativeTakeLevel(): Float

    private external fun nativeFrames(direction: Int): Long

    private external fun nativeLoseStream(direction: Int)
}
