// SPDX-License-Identifier: AGPL-3.0-only

import AVFAudio

/// The short sounds that answer a headset's button (FR-5.35).
enum FeedbackTone: CaseIterable {
    /// Two notes going down: the microphone is off.
    case muted
    /// Two notes going up: the microphone is on.
    case unmuted

    var notes: (firstHz: Double, secondHz: Double) {
        switch self {
        case .muted: (660, 440)
        case .unmuted: (440, 660)
        }
    }
}

let toneSampleRate = 48_000
private let noteMillis = 70
private let fadeMillis = 8
/// Quiet next to voice: a tenth of full scale.
private let amplitude = 0.1

/// The samples of a tone: two notes of 70 ms each, faded in and out so that they do not click.
func feedbackToneSamples(_ tone: FeedbackTone, sampleRate: Int = toneSampleRate) -> [Int16] {
    let note = sampleRate * noteMillis / 1000
    let fade = sampleRate * fadeMillis / 1000
    let notes = tone.notes
    return (0..<note * 2).map { index in
        let inNote = index % note
        let hz = index < note ? notes.firstHz : notes.secondHz
        let envelope = min(1.0, Double(min(inNote, note - 1 - inNote)) / Double(fade))
        let sample = sin(2 * Double.pi * hz * Double(inNote) / Double(sampleRate)) * envelope * amplitude
        return Int16((sample * Double(Int16.max)).rounded())
    }
}

/// A tone as a sound file in memory (WAV: mono, 16 bit), which is what a player takes.
func feedbackToneFile(_ tone: FeedbackTone, sampleRate: Int = toneSampleRate) -> Data {
    let samples = feedbackToneSamples(tone, sampleRate: sampleRate)
    let bytes = samples.count * 2
    var data = Data(capacity: 44 + bytes)
    func append<Value: FixedWidthInteger>(_ value: Value) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
    data.append(contentsOf: Array("RIFF".utf8))
    append(UInt32(36 + bytes))
    data.append(contentsOf: Array("WAVEfmt ".utf8))
    append(UInt32(16))
    // Plain samples, one channel.
    append(UInt16(1))
    append(UInt16(1))
    append(UInt32(sampleRate))
    append(UInt32(sampleRate * 2))
    append(UInt16(2))
    append(UInt16(16))
    data.append(contentsOf: Array("data".utf8))
    append(UInt32(bytes))
    for sample in samples { append(sample) }
    return data
}

/// Plays a `FeedbackTone` to the user only; the others never hear it.
protocol FeedbackTones: Sendable {
    func play(_ tone: FeedbackTone)
}

/// Plays the tones with players of their own, next to the voice: they come out where the
/// session's audio does, also on a Bluetooth headset, and nothing here touches the voice
/// streams or their audio thread. The players are made once and only started for a tone.
final class SystemFeedbackTones: FeedbackTones, @unchecked Sendable {
    private let log: @Sendable (String) -> Void
    /// Used on the main thread only.
    private var players: [FeedbackTone: AVAudioPlayer] = [:]

    init(log: @escaping @Sendable (String) -> Void) {
        self.log = log
    }

    func play(_ tone: FeedbackTone) {
        DispatchQueue.main.async { [self] in
            guard let player = player(tone) else { return }
            player.currentTime = 0
            player.play()
        }
    }

    private func player(_ tone: FeedbackTone) -> AVAudioPlayer? {
        if let player = players[tone] { return player }
        do {
            let player = try AVAudioPlayer(data: feedbackToneFile(tone), fileTypeHint: "com.microsoft.waveform-audio")
            player.prepareToPlay()
            players[tone] = player
            return player
        } catch {
            log("preparing a feedback tone failed (\((error as NSError).code))")
            return nil
        }
    }
}
