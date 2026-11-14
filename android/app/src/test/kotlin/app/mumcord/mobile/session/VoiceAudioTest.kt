// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.AudioWanted
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.platform.audio.AudioController
import app.mumcord.mobile.platform.audio.AudioDirection
import app.mumcord.mobile.platform.audio.OpenResult
import app.mumcord.mobile.platform.audio.StreamBackend
import app.mumcord.mobile.platform.audio.StreamFormat
import app.mumcord.mobile.platform.audio.StreamState
import app.mumcord.mobile.platform.audio.VoiceSession
import app.mumcord.mobile.ui.connected.FakeMicrophoneGateway
import app.mumcord.mobile.ui.connected.MicrophoneAnswer
import app.mumcord.mobile.ui.connected.MicrophoneNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's audio follows the engine's "audio wanted" state, and the microphone is asked for
 * when it is first needed (FR-5.1): listening works without it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAudioTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = app.mumcord.mobile.bridge.ServerState(
        selfSession = 10u,
        channels = listOf(channel(0u, "Root")),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )

    private class Streams : StreamBackend {
        val open = mutableSetOf<AudioDirection>()
        override var onLost: ((direction: AudioDirection, generation: Int) -> Unit)? = null

        override fun open(direction: AudioDirection, handle: Long): OpenResult {
            open += direction
            return OpenResult.Opened(1, StreamFormat(48_000, 192, lowLatency = true))
        }

        override fun close(direction: AudioDirection) {
            open -= direction
        }
    }

    private object NoSession : VoiceSession {
        override fun begin() = Unit

        override fun end() = Unit
    }

    private val streams = Streams()
    private val gateway = FakeMicrophoneGateway(granted = false)
    private lateinit var engine: FakeEngine
    private lateinit var controller: AudioController

    private val allowed = MicrophoneAnswer(granted = true, refusedBefore = false, refusedAfter = false)
    private val refused = MicrophoneAnswer(granted = false, refusedBefore = false, refusedAfter = true)
    private val refusedForGood = MicrophoneAnswer(granted = false, refusedBefore = true, refusedAfter = false)

    /** Voice audio on a connection whose engine exists, as after a connect. */
    private suspend fun TestScope.voiceAudio(): VoiceAudio {
        val repository = SessionRepository(
            engineFactory = { listener -> FakeEngine(listener, server).also { engine = it } },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        controller = AudioController(streams, NoSession, { gateway.granted() }, backgroundScope, log = {})
        val voiceAudio = VoiceAudio(repository, controller, gateway, backgroundScope)
        repository.loadServers()
        runCurrent()
        return voiceAudio
    }

    private fun TestScope.want(playback: Boolean, capture: Boolean) {
        engine.emit(BridgeEvent.AudioWantedChanged(AudioWanted(capture = capture, playback = playback)))
        runCurrent()
    }

    private fun TestScope.answer(voiceAudio: VoiceAudio, answer: MicrophoneAnswer) {
        if (answer.granted) gateway.granted = true
        voiceAudio.answer(answer)
        runCurrent()
    }

    @Test
    fun theStreamsFollowWhatTheEngineWants() = runTest(dispatcher) {
        gateway.granted = true
        val voiceAudio = voiceAudio()
        assertTrue(streams.open.isEmpty())

        want(playback = true, capture = true)
        assertEquals(setOf(AudioDirection.Playback, AudioDirection.Capture), streams.open)
        assertEquals(MicrophoneNotice.None, voiceAudio.notice.value)

        // Muted: the microphone closes, listening goes on.
        want(playback = true, capture = false)
        assertEquals(setOf(AudioDirection.Playback), streams.open)

        want(playback = false, capture = false)
        assertTrue(streams.open.isEmpty())
    }

    @Test
    fun withoutPermissionItIsExplainedWhenTalkingIsFirstPossibleAndListeningWorks() = runTest(dispatcher) {
        val voiceAudio = voiceAudio()
        want(playback = true, capture = false)
        assertEquals(MicrophoneNotice.None, voiceAudio.notice.value)

        want(playback = true, capture = true)
        assertEquals(MicrophoneNotice.Explain, voiceAudio.notice.value)
        assertEquals(setOf(AudioDirection.Playback), streams.open)
    }

    @Test
    fun allowingItStartsTheMicrophone() = runTest(dispatcher) {
        val voiceAudio = voiceAudio()
        want(playback = true, capture = true)

        answer(voiceAudio, allowed)

        assertEquals(MicrophoneNotice.None, voiceAudio.notice.value)
        assertEquals(setOf(AudioDirection.Playback, AudioDirection.Capture), streams.open)
        assertTrue(controller.state.value.capture is StreamState.Running)
    }

    @Test
    fun notNowKeepsListeningAndLeavesACalmNoticeAlsoOnTheNextStart() = runTest(dispatcher) {
        val voiceAudio = voiceAudio()
        want(playback = true, capture = true)

        voiceAudio.notNow()
        runCurrent()

        assertEquals(MicrophoneNotice.Denied, voiceAudio.notice.value)
        assertEquals(setOf(AudioDirection.Playback), streams.open)
        assertTrue(gateway.declined)
    }

    @Test
    fun refusingTheSystemsQuestionLeavesTheNoticeAndASecondRefusalPointsToTheSettings() = runTest(dispatcher) {
        val voiceAudio = voiceAudio()
        want(playback = true, capture = true)

        answer(voiceAudio, refused)
        assertEquals(MicrophoneNotice.Denied, voiceAudio.notice.value)
        assertFalse(gateway.promptBlocked)

        answer(voiceAudio, refusedForGood)
        assertEquals(MicrophoneNotice.Blocked, voiceAudio.notice.value)
        assertTrue(gateway.promptBlocked)
        assertEquals(setOf(AudioDirection.Playback), streams.open)
    }

    @Test
    fun allowingItInTheSystemSettingsStartsTheMicrophoneOnReturn() = runTest(dispatcher) {
        val voiceAudio = voiceAudio()
        want(playback = true, capture = true)
        answer(voiceAudio, refused)
        answer(voiceAudio, refusedForGood)

        gateway.granted = true
        voiceAudio.recheck()
        runCurrent()

        assertEquals(MicrophoneNotice.None, voiceAudio.notice.value)
        assertEquals(setOf(AudioDirection.Playback, AudioDirection.Capture), streams.open)
        assertFalse(gateway.promptBlocked)
        assertFalse(gateway.declined)
    }

    @Test
    fun theNoticeGoesAwayWhileTheMicrophoneIsNotWanted() = runTest(dispatcher) {
        val voiceAudio = voiceAudio()
        want(playback = true, capture = true)
        voiceAudio.notNow()
        runCurrent()

        // Muted or disconnected.
        want(playback = true, capture = false)
        assertEquals(MicrophoneNotice.None, voiceAudio.notice.value)
    }
}
