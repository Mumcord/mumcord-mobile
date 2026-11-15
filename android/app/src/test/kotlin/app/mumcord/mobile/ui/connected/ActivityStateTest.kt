// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.ui.text.font.FontWeight
import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.ActivityKind
import app.mumcord.mobile.ui.chat.DayLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The activity log as the screen lists it: filters, days, the badge and the sentences. */
class ActivityStateTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 15)

    private fun at(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun entry(id: Int, kind: ActivityKind, atMs: Long, self: Boolean = false) = ActivityEntry(
        id = id.toULong(),
        kind = kind,
        session = id.toUInt(),
        name = "Person $id",
        isSelf = self,
        channelId = 1u,
        channelName = "Lobby",
        atMs = atMs,
    )

    // Newest first, as the bridge and the state keep them.
    private val log = listOf(
        entry(7, ActivityKind.STREAM_STARTED, at(today, 19)),
        entry(6, ActivityKind.MOVED, at(today, 18)),
        entry(5, ActivityKind.LEFT, at(today, 17)),
        entry(4, ActivityKind.STREAM_STOPPED, at(today.minusDays(1), 22)),
        entry(3, ActivityKind.JOINED, at(today.minusDays(1), 21)),
        entry(2, ActivityKind.JOINED, at(today.minusDays(3), 20)),
        entry(1, ActivityKind.CONNECTED, at(today.minusDays(3), 19), self = true),
    )

    private fun ids(days: List<ActivityDay>) = days.map { day -> day.entries.map { it.id.toInt() } }

    @Test
    fun allEntriesAreGroupedByDayNewestFirst() {
        val days = activityDays(log, ActivityFilter.All, zone, today)

        assertEquals(
            listOf(DayLabel.Today, DayLabel.Yesterday, DayLabel.Date(today.minusDays(3))),
            days.map { it.label },
        )
        assertEquals(listOf(listOf(7, 6, 5), listOf(4, 3), listOf(2, 1)), ids(days))
    }

    @Test
    fun joinsAreComingGoingAndMovingAndStreamsAreScreenShares() {
        assertEquals(
            listOf(listOf(6, 5), listOf(3), listOf(2, 1)),
            ids(activityDays(log, ActivityFilter.Joins, zone, today)),
        )
        assertEquals(listOf(listOf(7), listOf(4)), ids(activityDays(log, ActivityFilter.Streams, zone, today)))
    }

    @Test
    fun theDayFollowsThePhonesTimeZone() {
        // 23:30 UTC yesterday is already today half an hour east of it.
        val late = listOf(entry(1, ActivityKind.JOINED, at(today.minusDays(1), 23) + 30 * 60_000))
        assertEquals(DayLabel.Yesterday, activityDays(late, ActivityFilter.All, zone, today).single().label)
        assertEquals(DayLabel.Today, activityDays(late, ActivityFilter.All, ZoneOffset.ofHours(1), today).single().label)
    }

    @Test
    fun aFilterWithoutEntriesHasNoDays() {
        val joinsOnly = log.filter { it.kind == ActivityKind.JOINED }
        assertTrue(activityDays(joinsOnly, ActivityFilter.Streams, zone, today).isEmpty())
        assertTrue(activityDays(emptyList(), ActivityFilter.All, zone, today).isEmpty())
    }

    @Test
    fun theBadgeCountsWhatOthersDidSinceTheLogWasLookedAt() {
        assertEquals("everything by others before the first look", 6, unseenActivity(log, seenId = null))
        assertEquals(2, unseenActivity(log, seenId = 5u))
        assertEquals(0, unseenActivity(log, seenId = 7u))
        val ownMove = listOf(entry(8, ActivityKind.MOVED, at(today, 20), self = true)) + log
        assertEquals("the user's own doings are not news", 0, unseenActivity(ownMove, seenId = 7u))
    }

    @Test
    fun theNameIsBoldAndThePlaceIsPlainWhereverTheTemplatePutsThem() {
        val sentence = activitySentence("%1\$s moved to %2\$s", "Jonas", "Gaming")
        assertEquals("Jonas moved to Gaming", sentence.text)
        val bold = sentence.spanStyles.single()
        assertEquals(FontWeight.SemiBold, bold.item.fontWeight)
        assertEquals("Jonas", sentence.text.substring(bold.start, bold.end))

        val placeFirst = activitySentence("In %2\$s: %1\$s", "Mira", "Music")
        assertEquals("In Music: Mira", placeFirst.text)
        assertEquals("Mira", placeFirst.text.substring(placeFirst.spanStyles.single().start))
    }

    @Test
    fun namesFromTheServerStayPlainCharacters() {
        // A name that looks like a placeholder or markup is not read as one.
        val sentence = activitySentence("%1\$s joined %2\$s", "%2\$s <b>x</b>", "Lobby")
        assertEquals("%2\$s <b>x</b> joined Lobby", sentence.text)
    }
}
