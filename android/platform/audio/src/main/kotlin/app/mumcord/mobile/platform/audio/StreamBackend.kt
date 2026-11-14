// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

/** How opening a stream ended. */
sealed interface OpenResult {
    /** [generation] names this stream, so a later loss can be matched to it. */
    data class Opened(val generation: Int, val format: StreamFormat) : OpenResult

    /** [code] is the system's error number, for the log only. */
    data class Failed(val code: Int, val engineMissing: Boolean = false) : OpenResult
}

/**
 * The streams themselves. A seam, so the controller can be tested without a device. Except for
 * [onLost], everything is called from the controller's single thread.
 */
interface StreamBackend {
    /** Opens and starts a stream that exchanges samples with the engine's [handle]. */
    fun open(direction: AudioDirection, handle: Long): OpenResult

    /** Stops and closes the stream; does nothing when it is not open. */
    fun close(direction: AudioDirection)

    /**
     * Called on a thread of the system when a running stream stops working, typically because
     * its device went away. The stream is still open and has to be closed.
     */
    var onLost: ((direction: AudioDirection, generation: Int) -> Unit)?
}

/** The real streams: AAudio in the module's C++ library. */
object NativeAudio : StreamBackend {
    private const val NO_ENGINE = -10000

    init {
        System.loadLibrary("mumcord_audio_io")
    }

    @Volatile
    override var onLost: ((direction: AudioDirection, generation: Int) -> Unit)? = null

    override fun open(direction: AudioDirection, handle: Long): OpenResult {
        if (!nativeBind()) return OpenResult.Failed(NO_ENGINE, engineMissing = true)
        val info = IntArray(3)
        val result = nativeOpen(direction.ordinal, handle, info)
        if (result <= 0) return OpenResult.Failed(result, engineMissing = result == NO_ENGINE)
        return OpenResult.Opened(
            generation = result,
            format = StreamFormat(sampleRate = info[0], framesPerBurst = info[1], lowLatency = info[2] == 1),
        )
    }

    override fun close(direction: AudioDirection) = nativeClose(direction.ordinal)

    /** Called by the C++ library, never from an audio callback. */
    @JvmStatic
    @Suppress("unused")
    fun onStreamError(direction: Int, generation: Int, error: Int) {
        onLost?.invoke(AudioDirection.entries[direction], generation)
    }

    private external fun nativeBind(): Boolean

    private external fun nativeOpen(direction: Int, handle: Long, info: IntArray): Int

    private external fun nativeClose(direction: Int)
}
