// SPDX-License-Identifier: AGPL-3.0-only

/// What the audio unit is asked to do. On iOS the speaker and the microphone are the two sides
/// of one unit, so they are opened and closed together.
struct StreamRequest: Equatable {
    /// The engine's handles (`MumcordEngine.audioEndpoints()`).
    var playbackHandle: UInt64
    var captureHandle: UInt64
    /// Play what the engine has; otherwise the speaker gets silence.
    var playback: Bool
    /// Open the microphone and send what it records to the engine.
    var capture: Bool
}

/// How opening the unit ended.
enum OpenResult: Equatable {
    /// `microphoneOpen` says whether the system holds the microphone for the app now.
    case opened(microphoneOpen: Bool)
    /// `code` is the system's error number and `step` the step that failed, for the log only.
    case failed(code: Int32, step: Int32)
}

/// The audio unit itself. A seam, so the controller can be tested without a device. Everything
/// is called from the controller's single queue.
protocol StreamBackend: AnyObject {
    /// Opens and starts the unit; a unit that is open is replaced.
    func open(_ request: StreamRequest) -> OpenResult
    /// Stops and closes the unit; does nothing when it is not open.
    func close()
}

/// The real unit: the C code in `AudioIO.c`, whose callbacks talk to the engine directly.
final class NativeAudio: StreamBackend {
    /// The simulator has no echo canceller to select and its voice-processing unit is not
    /// reliable, so it gets the plain input/output unit; phones always get voice processing,
    /// which is the echo cancellation the app relies on.
    static var usesVoiceProcessing: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return true
        #endif
    }

    func open(_ request: StreamRequest) -> OpenResult {
        var format = MumcordIOFormat(sample_rate: 0, input_open: false)
        var step: Int32 = 0
        let status = mumcord_io_open(
            MumcordIORequest(
                playback_handle: request.playbackHandle,
                capture_handle: request.captureHandle,
                playback: request.playback,
                capture: request.capture,
                voice_processing: Self.usesVoiceProcessing
            ),
            &format,
            &step
        )
        if status != 0 { return .failed(code: status, step: step) }
        return .opened(microphoneOpen: format.input_open)
    }

    func close() {
        mumcord_io_close()
    }
}
