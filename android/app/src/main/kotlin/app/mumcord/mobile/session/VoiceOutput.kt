// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.platform.call.AudioOutput
import app.mumcord.mobile.platform.call.AudioOutputs
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What has been done about the outputs of the running session.
 *
 * @param headsets the headsets seen so far, so that a new one is noticed.
 * @param settled the output for "no headset" has been chosen (or the user put the question
 *   away); after that the app leaves the choice to the user until a headset comes and goes.
 */
data class OutputMemory(val headsets: Set<String> = emptySet(), val settled: Boolean = false)

sealed interface OutputStep {
    data class Select(val id: String) : OutputStep

    /** Ask "Loudspeaker or earpiece?" and wait for the answer. */
    data object Ask : OutputStep

    data object None : OutputStep
}

/**
 * The rules for the audio output (FR-5.34) as a value: called for every change of the outputs
 * or of the saved default, it says what to do now.
 *
 * A headset that appears is used at once. Without a headset the saved default is applied
 * once; while the default is still "ask" and the phone has both a loudspeaker and an earpiece,
 * the question is asked. After a headset went away the default is applied again. What the
 * user picks in between is never undone.
 */
fun outputStep(state: OutputState, default: DefaultOutput, memory: OutputMemory): Pair<OutputMemory, OutputStep> {
    if (state.available.isEmpty()) return OutputMemory() to OutputStep.None
    val headsets = state.available.filter { it.isHeadset }
    val ids = headsets.mapTo(HashSet()) { it.id }
    val arrived = headsets.lastOrNull { it.id !in memory.headsets }
    if (arrived != null) {
        return OutputMemory(ids, settled = false) to select(arrived, state)
    }
    if (headsets.isNotEmpty() || memory.settled) return memory.copy(headsets = ids) to OutputStep.None

    val speaker = state.available.firstOrNull { it.kind == OutputKind.Speaker }
    val earpiece = state.available.firstOrNull { it.kind == OutputKind.Earpiece }
    val wanted = when (default) {
        DefaultOutput.SPEAKER -> speaker
        DefaultOutput.EARPIECE -> earpiece ?: speaker
        DefaultOutput.ASK ->
            if (speaker != null && earpiece != null) return OutputMemory(ids) to OutputStep.Ask else speaker
    }
    return OutputMemory(ids, settled = true) to (wanted?.let { select(it, state) } ?: OutputStep.None)
}

private fun select(output: AudioOutput, state: OutputState): OutputStep =
    if (state.current?.id == output.id) OutputStep.None else OutputStep.Select(output.id)

/** The audio output for the screens: what there is, the question, and the user's choices. */
interface OutputAccess {
    val state: StateFlow<OutputState>

    /** True while "Loudspeaker or earpiece?" waits for an answer. */
    val asking: StateFlow<Boolean>

    /** Moves the voice to this output at once. */
    fun select(id: String)

    /** The answer to the question: [DefaultOutput.SPEAKER] or [DefaultOutput.EARPIECE]. It is remembered. */
    fun answer(choice: DefaultOutput)

    /** Puts the question away unanswered; the next session asks again. */
    fun dismissQuestion()

    companion object {
        /** For screens shown without a phone behind them: no outputs, no question. */
        val None: OutputAccess = object : OutputAccess {
            override val state = MutableStateFlow(OutputState())
            override val asking = MutableStateFlow(false)

            override fun select(id: String) = Unit

            override fun answer(choice: DefaultOutput) = Unit

            override fun dismissQuestion() = Unit
        }
    }
}

/**
 * Applies [outputStep] to the running session and keeps the answer to the first-connect
 * question in the phone settings (`defaultOutput`). It follows the session by itself once it
 * has been created.
 *
 * @param scope lives as long as the process and runs one thing at a time.
 */
class VoiceOutput(
    private val repository: SessionRepository,
    private val outputs: AudioOutputs,
    private val scope: CoroutineScope,
) : OutputAccess {
    override val state: StateFlow<OutputState> = outputs.state

    private val mutableAsking = MutableStateFlow(false)
    override val asking: StateFlow<Boolean> = mutableAsking.asStateFlow()

    /** The answer just given, until the engine reports it as saved. */
    private val answered = MutableStateFlow<DefaultOutput?>(null)
    private var memory = OutputMemory()

    init {
        scope.launch {
            val saved = repository.state.map { it.mobileSettings?.defaultOutput }.distinctUntilChanged()
            combine(outputs.state, saved, answered, ::Triple).collect { (state, default, answer) ->
                if (answer != null && answer == default) answered.value = null
                // Nothing is decided before the saved default is known.
                (answer ?: default)?.let { follow(state, it) }
            }
        }
    }

    private fun follow(state: OutputState, default: DefaultOutput) {
        val (next, step) = outputStep(state, default, memory)
        memory = next
        mutableAsking.value = step == OutputStep.Ask
        if (step is OutputStep.Select) outputs.select(step.id)
    }

    override fun select(id: String) = outputs.select(id)

    override fun answer(choice: DefaultOutput) {
        answered.value = choice
        // A failure is logged by the repository; the next session then asks again.
        scope.launch { repository.updateMobileSettings { it.copy(defaultOutput = choice) } }
    }

    override fun dismissQuestion() {
        scope.launch {
            memory = memory.copy(settled = true)
            mutableAsking.value = false
        }
    }
}
