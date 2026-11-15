// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

enum OutputKind: String, Equatable {
    case speaker = "Speaker"
    case earpiece = "Earpiece"
    case wiredHeadset = "WiredHeadset"
    case bluetooth = "Bluetooth"
}

/// Where the voice of a session can be heard. `name` is the phone's own name for it; for a
/// Bluetooth device that is the name its owner gave it, which is never logged.
struct AudioOutput: Equatable {
    let id: String
    let kind: OutputKind
    let name: String

    var isHeadset: Bool {
        kind == .wiredHeadset || kind == .bluetooth
    }
}

/// The outputs of the running session; empty and nil while there is none.
struct OutputState: Equatable {
    var available: [AudioOutput] = []
    var current: AudioOutput?
}

/// The audio outputs of the voice session (FR-5.34). The state follows the phone by itself.
@MainActor
protocol AudioOutputs: AnyObject {
    var state: OutputState { get }
    /// The state of the moment and every later one, in order.
    func states() -> AsyncStream<OutputState>
    /// Moves the voice to the output with this id at once; an unknown id does nothing.
    func select(_ id: String)
    /// A session began or ended; without one there are no outputs.
    func setSession(_ exists: Bool)
}

/// What the phone system's wish to end the session's call means.
enum EndRequest: Equatable {
    case leave
    case toggleMute
}

/// The one rule for a call that the phone system ends (FR-5.35).
///
/// iOS reports the red End button of its own call screen (lock screen, Dynamic Island), a
/// car's hang-up button and the button of a wired headset as the same request, without saying
/// where it came from. The End button is in plain sight and must do what it says, so on iOS
/// the request always leaves the session, whatever `current` is; a headset mutes through the
/// system's mute request instead (`systemMute`). Android has no such button for the session's
/// call and reads the request as the headset's button while the voice is on a headset:
/// returning `.toggleMute` for `current?.isHeadset == true` here does the same on iOS.
func endRequestMeans(current: AudioOutput?) -> EndRequest {
    .leave
}

/// A port of the phone's audio session, reduced to what the output rules need. `name` is the
/// phone's name of the device.
struct AudioPort: Equatable {
    enum Kind: Equatable {
        case builtInMicrophone
        case builtInReceiver
        case builtInSpeaker
        /// A wired headset with a microphone, or a USB audio device.
        case wiredHeadset
        /// Wired headphones without a microphone; an output only.
        case headphones
        case bluetooth
        case other
    }

    let uid: String
    let kind: Kind
    let name: String
}

/// The ids of the two outputs every phone names the same way, and of headphones that have no
/// microphone and therefore no port that could be chosen.
enum OutputId {
    static let speaker = "speaker"
    static let earpiece = "earpiece"
    static let headphones = "headphones"
}

/// The outputs of a session from what the phone's audio session says: `inputs` are the
/// microphones it offers, `route` the outputs the voice plays on right now.
///
/// iOS only lists microphones, never outputs. A Bluetooth hands-free device and a wired
/// headset are a microphone and an output in one, so they are found among the microphones;
/// headphones without a microphone only show while they are the route. The loudspeaker is
/// always there, the earpiece on a phone (`hasEarpiece`) as long as nothing is plugged in:
/// with a wired headset the phone does not use its earpiece.
func outputState(inputs: [AudioPort], route: [AudioPort], hasEarpiece: Bool) -> OutputState {
    var available = [AudioOutput(id: OutputId.speaker, kind: .speaker, name: "")]
    let bluetooth = inputs.filter { $0.kind == .bluetooth }
        .map { AudioOutput(id: $0.uid, kind: .bluetooth, name: $0.name) }
    var wired = inputs.filter { $0.kind == .wiredHeadset }
        .map { AudioOutput(id: $0.uid, kind: .wiredHeadset, name: "") }
    let playing = route.first
    if wired.isEmpty, playing?.kind == .headphones || playing?.kind == .wiredHeadset {
        wired = [AudioOutput(id: OutputId.headphones, kind: .wiredHeadset, name: "")]
    }
    if hasEarpiece, wired.isEmpty {
        available.append(AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: ""))
    }
    available += bluetooth + wired

    var current: AudioOutput?
    switch playing?.kind {
    case .builtInSpeaker:
        current = available.first
    case .builtInReceiver:
        current = available.first { $0.kind == .earpiece }
    case .headphones, .wiredHeadset:
        current = wired.first
    case .bluetooth:
        // The same device has one port for its microphone and one for its sound; where the
        // two do not share an id, the name tells which one plays.
        current = bluetooth.first { $0.id == playing?.uid }
            ?? bluetooth.first { $0.name == playing?.name }
            ?? bluetooth.first
        if current == nil, let playing {
            let output = AudioOutput(id: playing.uid, kind: .bluetooth, name: playing.name)
            available.append(output)
            current = output
        }
    case .builtInMicrophone, .other, nil:
        current = nil
    }
    return OutputState(available: available, current: current)
}

/// The phone's side of the outputs. A seam, so the rules of `SessionOutputs` can be tested
/// without an audio session.
protocol OutputDevice: AnyObject {
    /// The outputs as the phone has them now, or nil while the voice does not run: the phone
    /// only knows where a voice session plays while there is one.
    func outputs() -> OutputState?
    /// Moves the voice to this output.
    func select(_ id: String)
    /// Called on any thread whenever `outputs()` may answer differently.
    var onOutputsChanged: (@Sendable () -> Void)? { get set }
}

/// The outputs of the voice session as the phone reports them.
///
/// The phone forgets a chosen output whenever the session's audio stops (a phone call, a
/// deafen), so the choice is kept here and made again when the audio is back. While the audio
/// is stopped the last known outputs stay, so the screens do not flicker.
@MainActor
final class SessionOutputs: AudioOutputs {
    private let device: OutputDevice?
    private let current = Observed(OutputState())
    private var session = false
    private var running = false
    /// The output the voice was last on.
    private var wanted: String?

    /// - Parameter device: the phone's outputs, or nil where there are none to choose from.
    init(device: OutputDevice?) {
        self.device = device
        device?.onOutputsChanged = { [weak self] in
            Task { @MainActor in self?.refresh() }
        }
    }

    nonisolated var state: OutputState { current.value }

    nonisolated func states() -> AsyncStream<OutputState> { current.states() }

    func setSession(_ exists: Bool) {
        if exists == session { return }
        session = exists
        if exists {
            refresh()
        } else {
            running = false
            wanted = nil
            publish(OutputState())
        }
    }

    func select(_ id: String) {
        guard session, let device, state.available.contains(where: { $0.id == id }) else { return }
        wanted = id
        device.select(id)
        refresh()
    }

    /// Reads the outputs again; the phone said that they changed.
    func refresh() {
        guard session, let device else { return }
        guard var now = device.outputs() else {
            running = false
            return
        }
        if !running {
            running = true
            if let wanted, wanted != now.current?.id, now.available.contains(where: { $0.id == wanted }) {
                device.select(wanted)
                now = device.outputs() ?? now
            }
        }
        wanted = now.current?.id ?? wanted
        publish(now)
    }

    private func publish(_ next: OutputState) {
        if next != current.value { current.set(next) }
    }
}
