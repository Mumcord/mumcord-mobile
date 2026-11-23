// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.platform.call.OutputState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for the audio output (FR-5.34, AC-5.11), first as values, then with a session. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOutputTest {
    private val phone = OutputState(available = listOf(SPEAKER, EARPIECE), current = EARPIECE)

    // ----- the rules -----

    @Test
    fun withoutAHeadsetTheFirstConnectionAsks() {
        val (memory, step) = outputStep(phone, DefaultOutput.ASK, OutputMemory())

        assertEquals(OutputStep.Ask, step)
        assertFalse("The question stays until it is answered", memory.settled)
        assertEquals(OutputStep.Ask, outputStep(phone, DefaultOutput.ASK, memory).second)
    }

    @Test
    fun theSavedAnswerIsAppliedOnceAndWhatTheUserPicksLaterStays() {
        val (memory, step) = outputStep(phone, DefaultOutput.SPEAKER, OutputMemory())
        assertEquals(OutputStep.Select(SPEAKER.id), step)

        val onSpeaker = phone.copy(current = SPEAKER)
        assertEquals(OutputStep.None, outputStep(onSpeaker, DefaultOutput.SPEAKER, memory).second)
        assertEquals("Back on the earpiece by hand", OutputStep.None, outputStep(phone, DefaultOutput.SPEAKER, memory).second)
    }

    @Test
    fun anOutputThatIsAlreadyTheSavedOneIsNotAskedForAgain() {
        assertEquals(OutputStep.None, outputStep(phone, DefaultOutput.EARPIECE, OutputMemory()).second)
    }

    @Test
    fun aPhoneWithoutAnEarpieceIsNotAskedAndUsesTheSpeaker() {
        val tablet = OutputState(available = listOf(SPEAKER), current = SPEAKER)

        val (memory, step) = outputStep(tablet, DefaultOutput.ASK, OutputMemory())

        assertEquals(OutputStep.None, step)
        assertTrue(memory.settled)
        assertEquals(
            "A saved earpiece that is not there means the speaker",
            OutputStep.None,
            outputStep(tablet, DefaultOutput.EARPIECE, OutputMemory()).second,
        )
    }

    @Test
    fun aConnectedHeadsetWinsAndNothingIsAsked() {
        val withBuds = OutputState(available = listOf(SPEAKER, EARPIECE, BUDS), current = EARPIECE)

        val (memory, step) = outputStep(withBuds, DefaultOutput.ASK, OutputMemory())

        assertEquals(OutputStep.Select(BUDS.id), step)
        assertEquals(
            "With the voice on it there is nothing more to do",
            OutputStep.None,
            outputStep(withBuds.copy(current = BUDS), DefaultOutput.ASK, memory).second,
        )
    }

    @Test
    fun aHeadsetThatIsPluggedInLaterTakesOverAndTheUserMaySwitchAway() {
        val (settled, _) = outputStep(phone.copy(current = SPEAKER), DefaultOutput.SPEAKER, OutputMemory())
        val plugged = OutputState(available = listOf(SPEAKER, EARPIECE, WIRED), current = SPEAKER)

        val (memory, step) = outputStep(plugged, DefaultOutput.SPEAKER, settled)
        assertEquals(OutputStep.Select(WIRED.id), step)

        assertEquals(
            "Switched to the speaker by hand while the headset stays plugged in",
            OutputStep.None,
            outputStep(plugged, DefaultOutput.SPEAKER, memory).second,
        )
    }

    @Test
    fun afterAHeadsetWentAwayTheSavedDefaultIsAppliedAgain() {
        val withBuds = OutputState(available = listOf(SPEAKER, EARPIECE, BUDS), current = BUDS)
        val (memory, _) = outputStep(withBuds, DefaultOutput.SPEAKER, OutputMemory())

        val (_, step) = outputStep(phone, DefaultOutput.SPEAKER, memory)

        assertEquals(OutputStep.Select(SPEAKER.id), step)
    }

    @Test
    fun afterAHeadsetWentAwayWithoutASavedAnswerTheQuestionIsAsked() {
        val withBuds = OutputState(available = listOf(SPEAKER, EARPIECE, BUDS), current = BUDS)
        val (memory, _) = outputStep(withBuds, DefaultOutput.ASK, OutputMemory())

        assertEquals(OutputStep.Ask, outputStep(phone, DefaultOutput.ASK, memory).second)
    }

    @Test
    fun theEndOfASessionForgetsWhatWasDone() {
        val (memory, _) = outputStep(phone, DefaultOutput.SPEAKER, OutputMemory())

        assertEquals(OutputMemory() to OutputStep.None, outputStep(OutputState(), DefaultOutput.SPEAKER, memory))
    }

    // ----- with a session -----

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private val outputs = FakeOutputs()

    private suspend fun TestScope.voiceOutput(default: DefaultOutput = DefaultOutput.ASK): VoiceOutput {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht")))
                    .also {
                        it.mobileSettings = it.mobileSettings.copy(defaultOutput = default)
                        engine = it
                    }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        return VoiceOutput(repository, outputs, backgroundScope).also { runCurrent() }
    }

    @Test
    fun theFirstConnectionAsksAndTheAnswerIsAppliedAndRemembered() = runTest(dispatcher) {
        val output = voiceOutput()
        assertFalse("No session, no question", output.asking.value)

        outputs.state.value = phone
        runCurrent()
        assertTrue(output.asking.value)
        assertTrue("Nothing is switched before the answer", outputs.selected.isEmpty())

        output.answer(DefaultOutput.SPEAKER)
        runCurrent()

        assertFalse(output.asking.value)
        assertEquals(listOf(SPEAKER.id), outputs.selected)
        assertEquals(DefaultOutput.SPEAKER, engine.mobileSettings.defaultOutput)
    }

    @Test
    fun aLaterConnectionAppliesTheRememberedAnswerWithoutAsking() = runTest(dispatcher) {
        val output = voiceOutput(DefaultOutput.SPEAKER)

        outputs.state.value = phone
        runCurrent()

        assertFalse(output.asking.value)
        assertEquals(listOf(SPEAKER.id), outputs.selected)

        outputs.state.value = OutputState()
        runCurrent()
        outputs.state.value = phone
        runCurrent()
        assertEquals("And again with the next session", listOf(SPEAKER.id, SPEAKER.id), outputs.selected)
    }

    @Test
    fun puttingTheQuestionAwayKeepsThePhonesChoiceAndTheNextSessionAsksAgain() = runTest(dispatcher) {
        val output = voiceOutput()
        outputs.state.value = phone
        runCurrent()

        output.dismissQuestion()
        runCurrent()

        assertFalse(output.asking.value)
        assertTrue(outputs.selected.isEmpty())
        assertEquals(DefaultOutput.ASK, engine.mobileSettings.defaultOutput)

        outputs.state.value = OutputState()
        runCurrent()
        outputs.state.value = phone
        runCurrent()
        assertTrue(output.asking.value)
    }

    @Test
    fun aHeadsetThatConnectsWhileTheQuestionIsOpenAnswersItForNow() = runTest(dispatcher) {
        val output = voiceOutput()
        outputs.state.value = phone
        runCurrent()

        outputs.state.value = OutputState(available = listOf(SPEAKER, EARPIECE, BUDS), current = EARPIECE)
        runCurrent()

        assertFalse(output.asking.value)
        assertEquals(listOf(BUDS.id), outputs.selected)
    }

    @Test
    fun theUsersChoiceInTheSheetSwitchesAtOnceAndIsNotRemembered() = runTest(dispatcher) {
        val output = voiceOutput(DefaultOutput.SPEAKER)
        outputs.state.value = phone
        runCurrent()

        output.select(EARPIECE.id)
        runCurrent()

        assertEquals(EARPIECE, output.state.value.current)
        assertEquals(DefaultOutput.SPEAKER, engine.mobileSettings.defaultOutput)
    }
}
