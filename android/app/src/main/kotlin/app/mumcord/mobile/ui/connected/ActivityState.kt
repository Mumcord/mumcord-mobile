// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.ActivityKind
import app.mumcord.mobile.ui.chat.DayLabel
import app.mumcord.mobile.ui.chat.dayLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The filter chips of the activity log (mockup `10-activity-log`). The mockup's "Moderation"
 * is left out: the bridge reports kicks and bans as people leaving, so it would always be empty.
 */
enum class ActivityFilter { All, Joins, Streams }

fun ActivityFilter.includes(kind: ActivityKind): Boolean = when (this) {
    ActivityFilter.All -> true
    ActivityFilter.Joins -> kind != ActivityKind.STREAM_STARTED && kind != ActivityKind.STREAM_STOPPED
    ActivityFilter.Streams -> kind == ActivityKind.STREAM_STARTED || kind == ActivityKind.STREAM_STOPPED
}

/** The entries of one day under their heading, newest first. */
data class ActivityDay(val label: DayLabel, val entries: List<ActivityEntry>)

/**
 * The log as the screen lists it: the entries of [filter], grouped by the day they happened
 * on in [zone]. [entries] come newest first and stay in that order.
 */
fun activityDays(entries: List<ActivityEntry>, filter: ActivityFilter, zone: ZoneId, today: LocalDate): List<ActivityDay> =
    entries.filter { filter.includes(it.kind) }
        .groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
        .map { (day, onDay) -> ActivityDay(dayLabel(day, today), onDay) }

/**
 * The number on the Activity row of the More tab: what others did since the log was last
 * looked at ([seenId], the newest entry then; null when it was not opened in this connection).
 */
fun unseenActivity(entries: List<ActivityEntry>, seenId: ULong?): Int =
    entries.count { !it.isSelf && (seenId == null || it.id > seenId) }

/**
 * A sentence of the log from its [template]: `%1$s` becomes the [name] in bold, `%2$s` the
 * [place] (a channel or the server). Both are the server's text and stay plain characters.
 */
fun activitySentence(template: String, name: String, place: String): AnnotatedString = buildAnnotatedString {
    var rest = template
    while (rest.isNotEmpty()) {
        val nameAt = rest.indexOf(NAME).takeIf { it >= 0 } ?: Int.MAX_VALUE
        val placeAt = rest.indexOf(PLACE).takeIf { it >= 0 } ?: Int.MAX_VALUE
        val next = minOf(nameAt, placeAt)
        if (next == Int.MAX_VALUE) {
            append(rest)
            return@buildAnnotatedString
        }
        append(rest.substring(0, next))
        if (next == nameAt) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
            rest = rest.substring(next + NAME.length)
        } else {
            append(place)
            rest = rest.substring(next + PLACE.length)
        }
    }
}

private const val NAME = "%1\$s"
private const val PLACE = "%2\$s"
