// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.e2e

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.platform.audio.AudioDirection
import app.mumcord.mobile.platform.audio.AudioProbe

/**
 * What the voice scenarios measure on the phone's side. The emulator has no listener, so the
 * app's test probe measures what the speaker stream plays and how much the microphone stream
 * delivers; the second person's own report is checked by e2e/run.sh.
 */
object VoiceChecks {
    /** The name `e2e/voice-peer` connects with. */
    const val PEER_NAME = "e2e-peer"
    const val POLL_MILLIS = 250L

    /** Two seconds of polls: longer than the engine's own short sounds. */
    const val STEADY_POLLS = 8

    /** Level (RMS of the loudest block) from which the speaker counts as playing a voice. */
    const val HEARD = 0.02f

    /** Whether the speaker played nothing but silence during the next [polls] polls. */
    fun speakerQuietFor(polls: Int): Boolean {
        AudioProbe.takePlaybackLevel()
        repeat(polls) {
            Thread.sleep(POLL_MILLIS)
            if (AudioProbe.takePlaybackLevel() >= HEARD) return false
        }
        return true
    }

    /** Whether the speaker played a voice without a gap during the next [polls] polls. */
    fun speakerLoudFor(polls: Int): Boolean {
        AudioProbe.takePlaybackLevel()
        repeat(polls) {
            Thread.sleep(POLL_MILLIS)
            if (AudioProbe.takePlaybackLevel() < HEARD) return false
        }
        return true
    }

    /** How many frames the microphone stream handed to the engine in the next [millis]. */
    fun microphoneFramesIn(millis: Long): Long {
        val before = AudioProbe.frames(AudioDirection.Capture)
        Thread.sleep(millis)
        return AudioProbe.frames(AudioDirection.Capture) - before
    }

    /**
     * Holds a finger on the middle of the node with [tag] for [millis] and returns how long it
     * really was down. The touch goes through the system's input like a real finger (the
     * injection UI Automator itself uses), with the release at a known moment, which a swipe
     * of UI Automator does not offer.
     */
    fun hold(compose: ComposeTestRule, tag: String, millis: Long): Long {
        val center = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.center
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun touch(action: Int, downTime: Long) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, center.x, center.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true)) { "the system refused the touch" }
            event.recycle()
        }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down)
        Thread.sleep(millis)
        touch(MotionEvent.ACTION_UP, down)
        return SystemClock.uptimeMillis() - down
    }

    /** A line in the output of the test run, next to the test's result; e2e/run.sh reads some. */
    fun report(line: String) {
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
    }
}
