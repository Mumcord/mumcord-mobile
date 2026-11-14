// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The two sounds for a headset's button: short, quiet, without clicks, and not the same. */
class FeedbackToneTest {
    private val muted = feedbackToneSamples(FeedbackTone.Muted)
    private val unmuted = feedbackToneSamples(FeedbackTone.Unmuted)

    @Test
    fun aToneIsTwoShortNotes() {
        assertEquals("140 ms", TONE_SAMPLE_RATE * 140 / 1000, muted.size)
        assertEquals(muted.size, unmuted.size)
    }

    @Test
    fun aToneIsQuiet() {
        val loudest = (muted + unmuted).maxOf { abs(it.toInt()) }
        assertTrue("At most a tenth of full scale, was $loudest", loudest <= Short.MAX_VALUE / 10 + 1)
        assertTrue("And not silent", loudest > Short.MAX_VALUE / 20)
    }

    @Test
    fun eachNoteStartsAndEndsSilentSoThatNothingClicks() {
        val note = muted.size / 2
        listOf(0, note - 1, note, muted.size - 1).forEach { index ->
            assertTrue("sample $index", abs(muted[index].toInt()) < 40)
            assertTrue("sample $index", abs(unmuted[index].toInt()) < 40)
        }
    }

    @Test
    fun mutedGoesDownAndUnmutedGoesUp() {
        assertFalse(muted.contentEquals(unmuted))
        val note = muted.size / 2
        fun crossings(samples: ShortArray, from: Int) =
            (from + 1 until from + note).count { samples[it - 1] < 0 && samples[it] >= 0 }
        assertTrue(crossings(muted, 0) > crossings(muted, note))
        assertTrue(crossings(unmuted, 0) < crossings(unmuted, note))
    }
}
