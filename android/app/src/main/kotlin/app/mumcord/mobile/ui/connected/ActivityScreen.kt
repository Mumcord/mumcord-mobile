// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.ActivityKind
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAppBarSubtitle
import app.mumcord.mobile.design.components.MumcordChipRow
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordColors
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.chat.ChatClock
import app.mumcord.mobile.ui.chat.DayLabel
import app.mumcord.mobile.ui.common.Paragraph

const val ACTIVITY_SCREEN_TEST_TAG = "activity"
const val ACTIVITY_ENTRY_TEST_TAG = "activity-entry"
const val ACTIVITY_EMPTY_TEST_TAG = "activity-empty"

/**
 * The activity log of the connection (mockup `10-activity-log`, FR-5.94): what people did,
 * newest first under the day it happened, with filter chips. The empty states have no mockup.
 */
@Composable
fun ActivityScreen(state: MoreUiState, actions: MoreController, clock: ChatClock, topInsets: WindowInsets) {
    val days = remember(state.activity, state.filter, clock.today, clock.zone) {
        activityDays(state.activity, state.filter, clock.zone, clock.today)
    }
    Column(Modifier.fillMaxSize().testTag(ACTIVITY_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.activity_title),
            border = true,
            onBack = actions::back,
            backContentDescription = stringResource(R.string.activity_back),
            windowInsets = topInsets,
            subtitle = { MumcordAppBarSubtitle(state.serverName) },
        )
        val filters = ActivityFilter.entries
        MumcordChipRow(
            options = filters.map { filterText(it) },
            selectedIndex = filters.indexOf(state.filter),
            onSelect = { actions.selectFilter(filters[it]) },
            modifier = Modifier.padding(top = MumcordSpace.s2),
        )
        if (days.isEmpty()) {
            EmptyActivity(filtered = state.activity.isNotEmpty())
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            days.forEach { day ->
                item(key = "day-${day.entries.first().id}") {
                    MumcordSectionHeader(dayText(day.label, clock))
                }
                item(key = "list-${day.entries.first().id}") {
                    MumcordList {
                        day.entries.forEachIndexed { index, entry ->
                            ActivityRow(entry, state.serverName, clock, divider = index > 0)
                        }
                    }
                }
            }
            item { Box(Modifier.padding(bottom = MumcordSpace.s4)) }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry, serverName: String, clock: ChatClock, divider: Boolean) {
    val colors = MumcordTheme.colors
    val look = entry.kind.look(colors)
    val name = if (entry.isSelf) stringResource(R.string.activity_you) else entry.name
    // "Connected" names the server; the other kinds name a channel.
    val place = if (entry.kind == ActivityKind.CONNECTED) serverName else entry.channelName.orEmpty()
    val sentence = activitySentence(template(entry), name, place)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .then(if (divider) Modifier.dividerLine(colors.border) else Modifier)
            .padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s2)
            .semantics(mergeDescendants = true) { }
            .testTag(ACTIVITY_ENTRY_TEST_TAG),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ICON_BOX).background(colors.bgHover, RoundedCornerShape(MumcordRadius.lg)),
        ) {
            MumcordIcon(look.icon, contentDescription = null, tint = look.tint)
        }
        BasicText(sentence, style = MumcordTheme.typography.body.copy(color = colors.text), modifier = Modifier.weight(1f))
        MumcordListRowValue(clock.time(entry.atMs))
    }
}

@Composable
private fun EmptyActivity(filtered: Boolean) {
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(MumcordSpace.s6).testTag(ACTIVITY_EMPTY_TEST_TAG),
    ) {
        if (filtered) {
            Paragraph(stringResource(R.string.activity_empty_filter), color = MumcordTheme.colors.textMuted)
        } else {
            MumcordIcon(MumcordIcons.Activity, contentDescription = null, tint = MumcordTheme.colors.textMuted)
            BasicText(
                stringResource(R.string.activity_empty),
                style = MumcordTheme.typography.bodyStrong.copy(color = MumcordTheme.colors.text, textAlign = TextAlign.Center),
            )
            BasicText(
                stringResource(R.string.activity_empty_text),
                style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted, textAlign = TextAlign.Center),
            )
        }
    }
}

private data class EntryLook(val icon: ImageVector, val tint: Color)

/** Icon and colour as in the mockup. */
private fun ActivityKind.look(colors: MumcordColors): EntryLook = when (this) {
    ActivityKind.CONNECTED, ActivityKind.JOINED -> EntryLook(MumcordIcons.Users, colors.success)
    ActivityKind.MOVED -> EntryLook(MumcordIcons.ChevronRight, colors.textSecondary)
    ActivityKind.LEFT -> EntryLook(MumcordIcons.PhoneOff, colors.textMuted)
    ActivityKind.STREAM_STARTED -> EntryLook(MumcordIcons.Monitor, colors.accent)
    ActivityKind.STREAM_STOPPED -> EntryLook(MumcordIcons.Monitor, colors.textMuted)
}

@Composable
private fun template(entry: ActivityEntry): String {
    val known = entry.channelName != null
    return stringResource(
        when (entry.kind) {
            ActivityKind.CONNECTED -> R.string.activity_connected
            ActivityKind.JOINED -> if (known) R.string.activity_joined else R.string.activity_joined_server
            ActivityKind.MOVED -> if (known) R.string.activity_moved else R.string.activity_moved_elsewhere
            ActivityKind.LEFT -> R.string.activity_left
            ActivityKind.STREAM_STARTED ->
                if (known) R.string.activity_stream_started else R.string.activity_stream_started_elsewhere
            ActivityKind.STREAM_STOPPED -> R.string.activity_stream_stopped
        },
    )
}

@Composable
private fun filterText(filter: ActivityFilter): String = stringResource(
    when (filter) {
        ActivityFilter.All -> R.string.activity_filter_all
        ActivityFilter.Joins -> R.string.activity_filter_joins
        ActivityFilter.Streams -> R.string.activity_filter_streams
    },
)

@Composable
private fun dayText(label: DayLabel, clock: ChatClock): String = when (label) {
    DayLabel.Today -> stringResource(R.string.chat_day_today)
    DayLabel.Yesterday -> stringResource(R.string.chat_day_yesterday)
    is DayLabel.Date -> clock.date(label.day)
}

/** The line above every row but the first, as in [app.mumcord.mobile.design.components.MumcordListRow]. */
private fun Modifier.dividerLine(color: Color): Modifier = drawBehind {
    val y = 0.5.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
}

private val ROW_HEIGHT = 60.dp
private val ICON_BOX = 36.dp
