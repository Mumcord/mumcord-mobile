// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val FORMAT = StreamFormat(sampleRate = 48_000, framesPerBurst = 192, lowLatency = true)
private val RUNNING = StreamState.Running(FORMAT)

/** Streams that open or fail as the test says and remember what was done to them. */
private class FakeBackend : StreamBackend {
    val calls = mutableListOf<String>()
    val failing = mutableSetOf<AudioDirection>()
    var engineMissing = false
    private var generation = 0
    val generations = mutableMapOf<AudioDirection, Int>()

    override var onLost: ((direction: AudioDirection, generation: Int) -> Unit)? = null

    override fun open(direction: AudioDirection, handle: Long): OpenResult {
        calls += "open $direction $handle"
        if (engineMissing) return OpenResult.Failed(-10000, engineMissing = true)
        if (direction in failing) return OpenResult.Failed(-899)
        generation += 1
        generations[direction] = generation
        return OpenResult.Opened(generation, FORMAT)
    }

    override fun close(direction: AudioDirection) {
        calls += "close $direction"
    }

    fun opens(direction: AudioDirection): Int = calls.count { it.startsWith("open $direction") }
}

private class FakeSession : VoiceSession {
    val calls = mutableListOf<String>()

    override fun begin() {
        calls += "begin"
    }

    override fun end() {
        calls += "end"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AudioControllerTest {
    private val backend = FakeBackend()
    private val session = FakeSession()
    private var granted = true
    private val log = mutableListOf<String>()

    private fun TestScope.controller(): AudioController =
        AudioController(backend, session, { granted }, CoroutineScopeOn(this), log::add).also {
            it.setEndpoints(capture = 7, playback = 9)
        }

    /** A scope on the test's scheduler whose jobs do not keep the test waiting. */
    private fun CoroutineScopeOn(scope: TestScope) =
        kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(scope.testScheduler))

    @Test
    fun nothingRunsUntilTheEngineWantsIt() = runTest {
        val controller = controller()
        runCurrent()
        assertEquals(AudioState(), controller.state.value)
        assertTrue(backend.calls.isEmpty())
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun wantedStreamsOpenWithTheirOwnHandlesAfterTheSessionBegan() = runTest {
        val controller = controller()
        controller.setWanted(playback = true, capture = true)
        runCurrent()
        assertEquals(AudioState(playback = RUNNING, capture = RUNNING), controller.state.value)
        assertEquals(listOf("open Playback 9", "open Capture 7"), backend.calls)
        assertEquals(listOf("begin"), session.calls)
    }

    @Test
    fun nothingOpensWithoutTheEnginesHandles() = runTest {
        val controller = AudioController(backend, session, { granted }, CoroutineScopeOn(this), log::add)
        controller.setWanted(playback = true, capture = true)
        runCurrent()
        assertEquals(AudioState(), controller.state.value)
        controller.setEndpoints(capture = 7, playback = 9)
        runCurrent()
        assertEquals(AudioState(playback = RUNNING, capture = RUNNING), controller.state.value)
    }

    @Test
    fun theDirectionsFollowTheEngineIndependently() = runTest {
        val controller = controller()
        controller.setWanted(playback = true, capture = false)
        runCurrent()
        assertEquals(AudioState(playback = RUNNING), controller.state.value)
        controller.setWanted(playback = true, capture = true)
        runCurrent()
        // Muting closes the microphone, so the system's indicator goes off; listening goes on.
        controller.setWanted(playback = true, capture = false)
        runCurrent()
        assertEquals(AudioState(playback = RUNNING), controller.state.value)
        assertEquals(listOf("open Playback 9", "open Capture 7", "close Capture"), backend.calls)
        assertEquals(listOf("begin"), session.calls)
    }

    @Test
    fun theSessionEndsAfterTheLastStreamClosed() = runTest {
        val controller = controller()
        controller.setWanted(playback = true, capture = true)
        controller.setWanted(playback = false, capture = false)
        runCurrent()
        assertEquals(AudioState(), controller.state.value)
        assertEquals(listOf("begin", "end"), session.calls)
        assertEquals("close Capture", backend.calls.last())
    }

    @Test
    fun withoutPermissionTheMicrophoneStaysClosedAndListeningWorks() = runTest {
        granted = false
        val controller = controller()
        controller.setWanted(playback = true, capture = true)
        runCurrent()
        assertEquals(AudioState(playback = RUNNING, capture = StreamState.NoPermission), controller.state.value)
        assertEquals(0, backend.opens(AudioDirection.Capture))
    }

    @Test
    fun theMicrophoneStartsOnceThePermissionIsGiven() = runTest {
        granted = false
        val controller = controller()
        controller.setWanted(playback = true, capture = true)
        runCurrent()
        granted = true
        controller.refreshPermission()
        runCurrent()
        assertEquals(AudioState(playback = RUNNING, capture = RUNNING), controller.state.value)
        assertEquals(1, backend.opens(AudioDirection.Playback))
    }

    @Test
    fun aMicrophoneThatDoesNotExistFailsAfterAFewTriesWithoutStoppingPlayback() = runTest {
        backend.failing += AudioDirection.Capture
        val controller = controller()
        controller.setWanted(playback = true, capture = true)
        advanceUntilIdle()
        assertEquals(
            AudioState(playback = RUNNING, capture = StreamState.Failed(AudioFailure.MicrophoneUnavailable)),
            controller.state.value,
        )
        assertEquals(4, backend.opens(AudioDirection.Capture))
        assertEquals(listOf("begin"), session.calls)
    }

    @Test
    fun aStreamThatFailsAtFirstRunsWhenTheDeviceIsBack() = runTest {
        backend.failing += AudioDirection.Playback
        val controller = controller()
        controller.setWanted(playback = true, capture = false)
        runCurrent()
        assertEquals(StreamState.Stopped, controller.state.value.playback)
        backend.failing.clear()
        advanceTimeBy(300)
        assertEquals(RUNNING, controller.state.value.playback)
        assertEquals(2, backend.opens(AudioDirection.Playback))
    }

    @Test
    fun aMissingEngineIsNotRetried() = runTest {
        backend.engineMissing = true
        val controller = controller()
        controller.setWanted(playback = true, capture = false)
        advanceUntilIdle()
        assertEquals(StreamState.Failed(AudioFailure.EngineUnavailable), controller.state.value.playback)
        assertEquals(1, backend.opens(AudioDirection.Playback))
    }

    @Test
    fun aLostStreamIsClosedAndOpenedAgain() = runTest {
        val controller = controller()
        controller.setWanted(playback = true, capture = true)
        runCurrent()
        backend.calls.clear()
        backend.onLost?.invoke(AudioDirection.Playback, backend.generations.getValue(AudioDirection.Playback))
        runCurrent()
        assertEquals(listOf("close Playback", "open Playback 9"), backend.calls)
        assertEquals(AudioState(playback = RUNNING, capture = RUNNING), controller.state.value)
        assertEquals(listOf("begin"), session.calls)
    }

    @Test
    fun aLateErrorOfAnEarlierStreamIsIgnored() = runTest {
        val controller = controller()
        controller.setWanted(playback = true, capture = false)
        runCurrent()
        val old = backend.generations.getValue(AudioDirection.Playback)
        backend.onLost?.invoke(AudioDirection.Playback, old)
        runCurrent()
        backend.calls.clear()
        backend.onLost?.invoke(AudioDirection.Playback, old)
        runCurrent()
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun aLostStreamThatIsNoLongerWantedStaysClosed() = runTest {
        val controller = controller()
        controller.setWanted(playback = true, capture = false)
        runCurrent()
        val generation = backend.generations.getValue(AudioDirection.Playback)
        controller.setWanted(playback = false, capture = false)
        backend.onLost?.invoke(AudioDirection.Playback, generation)
        runCurrent()
        assertEquals(1, backend.opens(AudioDirection.Playback))
        assertEquals(AudioState(), controller.state.value)
    }

    @Test
    fun stoppingCancelsAPendingRetry() = runTest {
        backend.failing += AudioDirection.Capture
        val controller = controller()
        controller.setWanted(playback = false, capture = true)
        runCurrent()
        controller.setWanted(playback = false, capture = false)
        advanceUntilIdle()
        assertEquals(1, backend.opens(AudioDirection.Capture))
        assertEquals(AudioState(), controller.state.value)
        assertEquals(listOf("begin", "end"), session.calls)
    }
}
