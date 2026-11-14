// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.TreeRow

private val INDENT_PER_LEVEL = 20.dp

@Composable
fun ChannelTreeScreen(
    rows: List<TreeRow>,
    phase: ConnectionPhase,
    onDisconnect: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.channels_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                OutlinedButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.channels_disconnect))
                }
            }
            if (phase is ConnectionPhase.Reconnecting) {
                Text(
                    text = stringResource(
                        R.string.channels_reconnecting,
                        phase.attempt,
                        phase.maxAttempts,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = rows,
                    key = { row ->
                        when (row) {
                            is TreeRow.ChannelRow -> "channel-${row.id}"
                            is TreeRow.PersonRow -> "person-${row.session}"
                        }
                    },
                ) { row ->
                    when (row) {
                        is TreeRow.ChannelRow -> ChannelLine(row)
                        is TreeRow.PersonRow -> PersonLine(row)
                    }
                }
            }
        }
    }
}

// Names come from the server and its users: they are only ever passed to Text, which draws
// them as plain characters without interpreting any markup.

@Composable
private fun ChannelLine(row: TreeRow.ChannelRow) {
    val people = pluralStringResource(R.plurals.channels_people_count, row.peopleCount, row.peopleCount)
    val description = stringResource(R.string.channels_channel_description, row.name, people)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + INDENT_PER_LEVEL * row.depth, end = 16.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.peopleCount.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PersonLine(row: TreeRow.PersonRow) {
    val description = stringResource(
        if (row.isSelf) R.string.channels_self_description else R.string.channels_person_description,
        row.name,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + INDENT_PER_LEVEL * row.depth, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.isSelf) {
            Text(
                text = stringResource(R.string.channels_self_marker),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
