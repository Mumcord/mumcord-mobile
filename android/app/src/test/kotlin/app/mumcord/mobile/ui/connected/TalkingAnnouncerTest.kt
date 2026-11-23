// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkingAnnouncerTest {
    private val announcer = TalkingAnnouncer(gapMillis = 5_000, repeatMillis = 30_000)

    @Test
    fun firstSpeakerIsAnnouncedAtOnce() {
        assertEquals(listOf("Anna"), announcer.update(0, mapOf(1u to "Anna")))
        assertNull(announcer.nextUpdateAt)
    }

    @Test
    fun stoppingIsNeverAnnounced() {
        announcer.update(0, mapOf(1u to "Anna"))
        assertEquals(emptyList<String>(), announcer.update(1_000, emptyMap()))
        assertNull(announcer.nextUpdateAt)
    }

    @Test
    fun speakersWithinTheGapAreNamedTogetherWhenItEnds() {
        announcer.update(0, mapOf(1u to "Anna"))
        assertEquals(emptyList<String>(), announcer.update(1_000, mapOf(1u to "Anna", 2u to "Ben")))
        assertEquals(5_000L, announcer.nextUpdateAt)
        assertEquals(emptyList<String>(), announcer.update(2_000, mapOf(1u to "Anna", 2u to "Ben", 3u to "Jonas")))
        assertEquals(listOf("Ben", "Jonas"), announcer.update(5_000, mapOf(1u to "Anna", 2u to "Ben", 3u to "Jonas")))
        assertNull(announcer.nextUpdateAt)
    }

    @Test
    fun somebodyWhoStoppedBeforeTheirTurnIsDropped() {
        announcer.update(0, mapOf(1u to "Anna"))
        announcer.update(1_000, mapOf(2u to "Ben"))
        assertEquals(emptyList<String>(), announcer.update(2_000, emptyMap()))
        assertNull(announcer.nextUpdateAt)
        assertEquals(emptyList<String>(), announcer.update(5_000, emptyMap()))
    }

    @Test
    fun theSamePersonIsNamedAgainOnlyAfterTheRepeatTime() {
        announcer.update(0, mapOf(1u to "Anna"))
        announcer.update(1_000, emptyMap())
        assertEquals(emptyList<String>(), announcer.update(10_000, mapOf(1u to "Anna")))
        assertNull(announcer.nextUpdateAt)
        announcer.update(11_000, emptyMap())
        assertEquals(listOf("Anna"), announcer.update(30_000, mapOf(1u to "Anna")))
    }

    @Test
    fun aFreshSpeakerAfterTheGapIsAnnouncedAtOnce() {
        announcer.update(0, mapOf(1u to "Anna"))
        assertEquals(listOf("Ben"), announcer.update(6_000, mapOf(1u to "Anna", 2u to "Ben")))
    }
}
