// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
/// Test probe for automated tests on a device without a microphone or a listener. It exists
/// only in debug builds: neither this type nor its part of the C code is in a release build.
enum AudioProbe {
    /// What the microphone side sends to the engine in place of what the device recorded.
    enum Signal: Int32 {
        /// A generated signal that the engine treats as somebody talking.
        case speech = 1
        /// Nothing at all.
        case silence = 2
    }

    /// Replaces the microphone's input by `signal`; nil gives the device's own input back.
    static func replaceMicrophone(_ signal: Signal?) {
        mumcord_io_probe_replace_microphone(signal?.rawValue ?? 0)
    }

    /// The loudest block (RMS, 0 to 1) the speaker side got from the engine since the last
    /// call; 0 for silence.
    static func takePlaybackLevel() -> Float {
        mumcord_io_probe_take_playback_level()
    }

    /// How many frames the direction has exchanged with the engine so far.
    static func frames(_ direction: AudioDirection) -> UInt64 {
        mumcord_io_probe_frames(direction == .playback ? 0 : 1)
    }

    /// Hands `frames` frames of the speech signal to the engine exactly as the microphone
    /// callback does, without a running unit.
    static func push(captureHandle: UInt64, frames: UInt32) {
        mumcord_io_probe_push(captureHandle, frames)
    }

    /// Fetches `frames` frames from the engine exactly as the speaker callback does, without a
    /// running unit, and returns their level (RMS, 0 to 1).
    static func pull(playbackHandle: UInt64, frames: UInt32) -> Float {
        mumcord_io_probe_pull(playbackHandle, frames)
    }
}
#endif
