// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// What has been done about the outputs of the running session.
struct OutputMemory: Equatable {
    /// The headsets seen so far, so that a new one is noticed.
    var headsets: Set<String> = []
    /// The output for "no headset" has been chosen (or the user put the question away); after
    /// that the app leaves the choice to the user until a headset comes and goes.
    var settled = false
}

enum OutputStep: Equatable {
    case select(String)
    /// Ask "Loudspeaker or earpiece?" and wait for the answer.
    case ask
    case nothing
}

/// The rules for the audio output (FR-5.34) as a value: called for every change of the outputs
/// or of the saved default, it says what to do now.
///
/// A headset that appears is used at once. Without a headset the saved default is applied
/// once; while the default is still "ask" and the device has both a loudspeaker and an
/// earpiece, the question is asked (a tablet has no earpiece and never asks). After a headset
/// went away the default is applied again. What the user picks in between is never undone.
func outputStep(_ state: OutputState, default saved: DefaultOutput, memory: OutputMemory) -> (OutputMemory, OutputStep) {
    if state.available.isEmpty { return (OutputMemory(), .nothing) }
    let headsets = state.available.filter(\.isHeadset)
    let ids = Set(headsets.map(\.id))
    if let arrived = headsets.last(where: { !memory.headsets.contains($0.id) }) {
        return (OutputMemory(headsets: ids, settled: false), select(arrived, state))
    }
    if !headsets.isEmpty || memory.settled {
        return (OutputMemory(headsets: ids, settled: memory.settled), .nothing)
    }
    let speaker = state.available.first { $0.kind == .speaker }
    let earpiece = state.available.first { $0.kind == .earpiece }
    let wanted: AudioOutput?
    switch saved {
    case .speaker:
        wanted = speaker
    case .earpiece:
        wanted = earpiece ?? speaker
    case .ask:
        if speaker != nil, earpiece != nil { return (OutputMemory(headsets: ids), .ask) }
        wanted = speaker
    }
    return (OutputMemory(headsets: ids, settled: true), wanted.map { select($0, state) } ?? .nothing)
}

private func select(_ output: AudioOutput, _ state: OutputState) -> OutputStep {
    state.current?.id == output.id ? .nothing : .select(output.id)
}

/// What the screens know about the audio output.
struct OutputNotice: Equatable {
    var state = OutputState()
    /// True while "Loudspeaker or earpiece?" waits for an answer.
    var asking = false
}

/// The audio output for the screens: what there is, the question, and the user's choices.
@MainActor
protocol OutputAccess: AnyObject {
    var notice: OutputNotice { get }
    /// The notice of the moment and every later one, in order.
    func notices() -> AsyncStream<OutputNotice>
    /// Moves the voice to this output at once.
    func select(_ id: String)
    /// The answer to the question: the loudspeaker or the earpiece. It is remembered.
    func answer(_ choice: DefaultOutput)
    /// Puts the question away unanswered; the next session asks again.
    func dismissQuestion()
}

/// For screens shown without a phone behind them: no outputs, no question.
@MainActor
final class NoOutputs: OutputAccess {
    let notice = OutputNotice()

    func notices() -> AsyncStream<OutputNotice> {
        let notice = self.notice
        return AsyncStream { continuation in
            continuation.yield(notice)
            continuation.finish()
        }
    }

    func select(_ id: String) {}
    func answer(_ choice: DefaultOutput) {}
    func dismissQuestion() {}
}

/// Applies `outputStep` to the running session and keeps the answer to the first-connect
/// question in the phone settings (`defaultOutput`). It follows the session by itself once it
/// has been created, and lives as long as the process.
@MainActor
final class VoiceOutput: OutputAccess {
    private let store: SessionStore
    private let outputs: AudioOutputs
    private let current = Observed(OutputNotice())
    private var outputState = OutputState()
    /// The saved default; nil until the phone settings have been read.
    private var saved: DefaultOutput?
    /// The answer just given, until the engine reports it as saved.
    private var answered: DefaultOutput?
    private var memory = OutputMemory()

    init(store: SessionStore, outputs: AudioOutputs) {
        self.store = store
        self.outputs = outputs
        let sessions = store.states()
        Task { [weak self] in
            for await session in sessions {
                guard let self else { return }
                self.saved = session.mobileSettings?.defaultOutput
                self.outputs.setSession(callInfo(session) != nil)
                self.follow()
            }
        }
        let states = outputs.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                self.outputState = state
                self.follow()
            }
        }
    }

    var notice: OutputNotice { current.value }

    func notices() -> AsyncStream<OutputNotice> { current.states() }

    private func follow() {
        if answered == saved { answered = nil }
        var asking = false
        // Nothing is decided before the saved default is known.
        if let rule = answered ?? saved {
            let (next, step) = outputStep(outputState, default: rule, memory: memory)
            memory = next
            asking = step == .ask
            if case .select(let id) = step { outputs.select(id) }
        }
        publish(OutputNotice(state: outputState, asking: asking))
    }

    private func publish(_ next: OutputNotice) {
        if next != current.value { current.set(next) }
    }

    func select(_ id: String) {
        outputs.select(id)
    }

    func answer(_ choice: DefaultOutput) {
        answered = choice
        follow()
        // A failure is logged by the store; the answer then holds until the app is closed.
        Task { _ = await store.updateMobileSettings { $0.defaultOutput = choice } }
    }

    func dismissQuestion() {
        memory.settled = true
        publish(OutputNotice(state: outputState, asking: false))
    }
}
