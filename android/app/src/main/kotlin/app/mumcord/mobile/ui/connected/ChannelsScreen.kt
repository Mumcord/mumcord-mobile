// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAppBarSubtitle
import app.mumcord.mobile.design.components.MumcordChannelRow
import app.mumcord.mobile.design.components.MumcordDot
import app.mumcord.mobile.design.components.MumcordHintLine
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordPersonRow
import app.mumcord.mobile.design.components.MumcordPersonStatus
import app.mumcord.mobile.design.components.MumcordStatusTone
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.components.MumcordTile
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.session.UnsupportedHint
import app.mumcord.mobile.session.UnsupportedKind
import app.mumcord.mobile.ui.common.Paragraph

const val CHANNEL_ROW_TEST_TAG = "channel-row"
const val PERSON_ROW_TEST_TAG = "person-row"
const val CHANNEL_SEARCH_TEST_TAG = "channel-search"
const val HINT_TEST_TAG = "unsupported-hint"

/** One step of nesting: the indent of a channel's people and sub-channels (`.channel-body`). */
private val INDENT = 28.dp

/** The Channels tab: server header, optional search field, the channel tree and the hints. */
@Composable
fun ChannelsScreen(state: ConnectedUiState, actions: ConnectedController, topInsets: WindowInsets) {
    Column(Modifier.fillMaxSize()) {
        MumcordAppBar(
            title = state.serverName,
            windowInsets = topInsets,
            leading = { MumcordTile(state.serverName.initials(2), brand = true, small = true) },
            subtitle = {
                MumcordDot()
                MumcordAppBarSubtitle(
                    pluralStringResource(R.plurals.channels_online, state.peopleOnline, state.peopleOnline),
                )
            },
        ) {
            if (state.searching) {
                MumcordIconButton(MumcordIcons.X, stringResource(R.string.channels_search_close), actions::closeSearch)
            } else {
                MumcordIconButton(MumcordIcons.Search, stringResource(R.string.channels_search), actions::openSearch)
            }
        }
        if (state.searching) SearchField(state.query, actions::changeQuery)
        if (state.searching && state.rows.isEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(MumcordSpace.s6)) {
                Paragraph(stringResource(R.string.channels_search_empty))
            }
        } else {
            ChannelList(state.rows, state.hints, actions)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    MumcordTextField(
        value = query,
        onValueChange = onChange,
        label = stringResource(R.string.channels_search),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .padding(bottom = MumcordSpace.s2)
            .focusRequester(focus)
            .testTag(CHANNEL_SEARCH_TEST_TAG),
    )
}

@Composable
private fun ChannelList(rows: List<ChannelListRow>, hints: List<UnsupportedHint>, actions: ConnectedController) {
    // Rows are immutable values with stable keys: when one person starts or stops talking,
    // only that person's row is drawn again.
    LazyColumn(
        contentPadding = PaddingValues(
            start = MumcordSpace.s2,
            top = MumcordSpace.s1,
            end = MumcordSpace.s2,
            bottom = MumcordSpace.s3,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = rows,
            key = { row ->
                when (row) {
                    is ChannelListRow.ChannelItem -> "channel-${row.id}"
                    is ChannelListRow.PersonItem -> "person-${row.session}"
                }
            },
            contentType = { it::class },
        ) { row ->
            val indent = Modifier.padding(start = INDENT * row.depth)
            when (row) {
                is ChannelListRow.ChannelItem -> ChannelLine(row, actions, indent)
                is ChannelListRow.PersonItem -> PersonLine(row, actions::openPerson, indent)
            }
        }
        items(items = hints, key = { "hint-${it.id}" }) { hint ->
            MumcordHintLine(hint.text(), Modifier.testTag(HINT_TEST_TAG))
        }
    }
}

// Names come from the server and its users: they are only ever drawn as plain characters.
@Composable
private fun ChannelLine(row: ChannelListRow.ChannelItem, actions: ConnectedController, modifier: Modifier) {
    val people = pluralStringResource(R.plurals.channels_people_count, row.peopleCount, row.peopleCount)
    val description = stringResource(R.string.channels_channel_description, row.name, people) +
        (if (row.current) stringResource(R.string.channels_channel_here_suffix) else "") +
        (if (row.locked) stringResource(R.string.channels_channel_locked_suffix) else "")
    MumcordChannelRow(
        name = row.name,
        onClick = { actions.join(row.id) },
        onLongClick = { actions.openChannelMenu(row.id) },
        longClickLabel = stringResource(R.string.channels_menu),
        icon = if (row.current) MumcordIcons.Volume else MumcordIcons.Hash,
        expanded = row.expanded,
        onToggleExpanded = { actions.toggleCollapsed(row.id) },
        toggleDescription = stringResource(
            if (row.expanded == true) R.string.channels_collapse else R.string.channels_expand,
            row.name,
        ),
        memberCount = row.peopleCount.takeIf { it > 0 },
        current = row.current,
        empty = row.peopleCount == 0,
        meta = if (row.current) stringResource(R.string.channels_here) else null,
        lockedDescription = if (row.locked) stringResource(R.string.channels_locked) else null,
        modifier = modifier
            .testTag(CHANNEL_ROW_TEST_TAG)
            .semantics(mergeDescendants = true) { contentDescription = description },
    )
}

@Composable
private fun PersonLine(row: ChannelListRow.PersonItem, onOpen: (UInt) -> Unit, modifier: Modifier) {
    MumcordPersonRow(
        name = row.name,
        initials = row.name.initials(1),
        colorSlot = avatarSlot(row.name),
        onClick = { onOpen(row.session) },
        talking = row.talking,
        talkingDescription = stringResource(R.string.person_talking),
        locallyMuted = row.locallyMuted,
        note = if (row.isSelf) stringResource(R.string.channels_self_marker) else null,
        status = row.state.statusIcons(),
        modifier = modifier.testTag(PERSON_ROW_TEST_TAG),
    )
}

/** The state icons in the order of the mockups: what is shared first, then what is switched off. */
@Composable
internal fun PersonFlags.statusIcons(): List<MumcordPersonStatus> = buildList {
    if (streaming) {
        add(MumcordPersonStatus(MumcordIcons.Monitor, stringResource(R.string.person_streaming), MumcordStatusTone.Live))
    }
    if (camera) {
        add(MumcordPersonStatus(MumcordIcons.Camera, stringResource(R.string.person_camera), MumcordStatusTone.Live))
    }
    if (serverMuted) {
        add(MumcordPersonStatus(MumcordIcons.MicOff, stringResource(R.string.person_server_muted), MumcordStatusTone.Off))
    } else if (muted) {
        add(MumcordPersonStatus(MumcordIcons.MicOff, stringResource(R.string.person_muted), MumcordStatusTone.Off))
    }
    if (deafened) {
        add(
            MumcordPersonStatus(MumcordIcons.HeadphonesOff, stringResource(R.string.person_deafened), MumcordStatusTone.Off),
        )
    }
}

@Composable
private fun UnsupportedHint.text(): String = stringResource(
    when (kind) {
        UnsupportedKind.FileTransfer -> R.string.hint_file_transfer
        UnsupportedKind.WatchTogether -> R.string.hint_watch_together
        UnsupportedKind.PluginContent -> R.string.hint_plugin_content
    },
    personName,
)
