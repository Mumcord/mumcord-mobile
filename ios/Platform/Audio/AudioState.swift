// SPDX-License-Identifier: AGPL-3.0-only

/// The two directions of voice: what the others say (speaker) and what the user says (microphone).
enum AudioDirection: Equatable {
    case playback
    case capture
}

/// Why a direction does not run although it should, in terms the app can put into words.
enum AudioFailure: Equatable {
    /// The phone has no usable microphone right now.
    case microphoneUnavailable
    /// The phone has no usable speaker or headset right now.
    case speakerUnavailable
}

enum StreamState: Equatable {
    case stopped
    case running
    /// Only the microphone: it is wanted, but the user has not allowed it. Listening goes on.
    case noPermission
    case failed(AudioFailure)
}

struct AudioState: Equatable {
    var playback = StreamState.stopped
    var capture = StreamState.stopped
}
