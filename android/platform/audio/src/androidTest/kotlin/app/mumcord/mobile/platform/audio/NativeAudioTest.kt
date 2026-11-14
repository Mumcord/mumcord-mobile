// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import android.Manifest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real streams on a device, against the engine's library without an engine (handle 0: a push
 * is dropped, a pull is silence). A device without a microphone is fine: the test then proves
 * that the microphone fails cleanly while the speaker runs.
 */
@RunWith(AndroidJUnit4::class)
class NativeAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val controller = AudioController(
        backend = NativeAudio,
        session = AndroidVoiceSession(context) { },
        microphoneGranted = { true },
        scope = scope,
        log = { },
    )

    @Before
    fun allowTheMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        controller.setEndpoints(capture = NO_ENGINE, playback = NO_ENGINE)
    }

    @After
    fun stop() {
        controller.setWanted(playback = false, capture = false)
        await("the streams to stop") { controller.state.value == AudioState() }
        scope.cancel()
    }

    @Test
    fun streamsStartAndStopRepeatedly() {
        val mode = audioManager.mode
        repeat(REPETITIONS) {
            controller.setWanted(playback = true, capture = true)
            awaitSettled()
            assertTrue(controller.state.value.playback is StreamState.Running)
            awaitMode(AudioManager.MODE_IN_COMMUNICATION)
            controller.setWanted(playback = false, capture = false)
            await("the streams to stop") { controller.state.value == AudioState() }
            awaitMode(mode)
        }
    }

    @Test
    fun aSessionThatStartsRightAfterAnotherEndedStillLeavesTheVoiceMode() {
        val mode = audioManager.mode
        val session = AndroidVoiceSession(context) { }
        repeat(REPETITIONS) {
            session.begin()
            session.end()
        }
        awaitMode(mode)
    }

    @Test
    fun theSpeakerStreamPullsFromTheEngine() {
        controller.setWanted(playback = true, capture = false)
        await("the speaker stream to start") { controller.state.value.playback is StreamState.Running }
        val before = AudioProbe.frames(AudioDirection.Playback)
        await("the speaker callback to run") { AudioProbe.frames(AudioDirection.Playback) > before + FORMAT_RATE / 10 }
        // Nothing is connected, so the engine's library hands out silence.
        AudioProbe.takePlaybackLevel()
        Thread.sleep(200)
        assertEquals(0f, AudioProbe.takePlaybackLevel(), 0f)
    }

    @Test
    fun aLostStreamIsReopened() {
        controller.setWanted(playback = true, capture = true)
        awaitSettled()
        repeat(REPETITIONS) {
            val before = AudioProbe.frames(AudioDirection.Playback)
            AudioProbe.loseStream(AudioDirection.Playback)
            if (controller.state.value.capture is StreamState.Running) AudioProbe.loseStream(AudioDirection.Capture)
            await("the speaker stream to run again") { AudioProbe.frames(AudioDirection.Playback) > before + FORMAT_RATE / 10 }
            assertTrue(controller.state.value.playback is StreamState.Running)
        }
    }

    /** Until both directions have come to rest: running, or failed for good (no microphone). */
    private fun awaitSettled() = await("the streams to start") {
        val state = controller.state.value
        state.playback is StreamState.Running &&
            (state.capture is StreamState.Running || state.capture is StreamState.Failed)
    }

    /**
     * The controller reports the streams as stopped before it ends the session, and the system
     * applies a new mode a moment after it was set, so the mode is waited for.
     */
    private fun awaitMode(mode: Int) = await("the audio mode to become $mode") { audioManager.mode == mode }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for $what; state ${controller.state.value}, audio mode ${audioManager.mode}")
            Thread.sleep(20)
        }
    }

    private companion object {
        const val REPETITIONS = 20
        const val FORMAT_RATE = 48_000
        const val TIMEOUT_NANOS = 20_000_000_000L
        const val NO_ENGINE = 0L
    }
}
