// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordChipRow
import app.mumcord.mobile.design.components.MumcordDot
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.components.MumcordTile
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import kotlinx.coroutines.flow.distinctUntilChanged

const val PUBLIC_SERVERS_SCREEN_TEST_TAG = "public-servers"
const val PUBLIC_SERVER_ROW_TEST_TAG = "public-server-row"
const val PUBLIC_SERVER_ADD_TEST_TAG = "public-server-add"
const val PUBLIC_SERVER_CONNECT_TEST_TAG = "public-server-connect"
const val PUBLIC_SERVERS_REFRESH_FAILED_TEST_TAG = "public-servers-refresh-failed"
const val PUBLIC_SERVERS_RETRY_TEST_TAG = "public-servers-retry"

/** Lazy list keys of the rows start with this; the other items are the header and the note. */
private const val ROW_KEY = "row:"

/** What the user can do on the public server browser. */
interface PublicServersActions {
    fun back()
    fun refresh()
    fun search(text: String)
    fun selectCountry(code: String?)
    fun showing(keys: List<String>)
    fun add(key: String)
    fun connect(serverId: Long)
}

/**
 * The public server browser (FR-5.9): search, a country filter and the list with people online
 * and latency for the rows on screen. Names and countries come from the list and are shown as
 * plain text.
 */
@Composable
fun PublicServersScreen(
    state: PublicServersUiState,
    actions: PublicServersActions,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen(Modifier.testTag(PUBLIC_SERVERS_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.public_title),
            onBack = actions::back,
            backContentDescription = stringResource(R.string.public_back),
            windowInsets = topInsets,
        )
        when (state.status) {
            PublicListStatus.Loading -> Message(stringResource(R.string.public_loading), Modifier.weight(1f))
            PublicListStatus.Failed -> Failed(actions, Modifier.weight(1f))
            else -> {
                Filters(state, actions)
                Banners(state)
                ServerList(state, actions, bottomInsets, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Filters(state: PublicServersUiState, actions: PublicServersActions) {
    MumcordTextField(
        value = state.search,
        onValueChange = actions::search,
        label = stringResource(R.string.public_search),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .padding(bottom = MumcordSpace.s2),
    )
    if (state.countries.isNotEmpty()) {
        MumcordChipRow(
            options = listOf(stringResource(R.string.public_any_country)) + state.countries.map { it.name },
            selectedIndex = state.countries.indexOfFirst { it.code == state.country } + 1,
            onSelect = { index -> actions.selectCountry(state.countries.getOrNull(index - 1)?.code) },
        )
    }
}

@Composable
private fun Banners(state: PublicServersUiState) {
    if (state.refreshFailed) {
        MumcordBanner(
            text = stringResource(R.string.public_refresh_failed),
            tone = MumcordBannerTone.Warning,
            modifier = Modifier
                .padding(top = MumcordSpace.s2)
                .testTag(PUBLIC_SERVERS_REFRESH_FAILED_TEST_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.addFailed) {
        MumcordBanner(
            text = stringResource(R.string.public_add_failed),
            icon = MumcordIcons.Alert,
            tone = MumcordBannerTone.Danger,
            modifier = Modifier
                .padding(top = MumcordSpace.s2)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ServerList(
    state: PublicServersUiState,
    actions: PublicServersActions,
    bottomInsets: WindowInsets,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // Only the rows on screen are pinged; the list tells which they are whenever that changes.
    LaunchedEffect(listState, actions) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                (item.key as? String)?.takeIf { it.startsWith(ROW_KEY) }?.removePrefix(ROW_KEY)
            }
        }.distinctUntilChanged().collect(actions::showing)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = bottomInsets.asPaddingValues().calculateBottomPadding() + MumcordSpace.s4),
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            MumcordSectionHeader(
                title = when {
                    state.refreshing -> stringResource(R.string.public_refreshing)
                    state.rows.size == state.total -> pluralStringResource(R.plurals.public_count_all, state.total, state.total)
                    else -> pluralStringResource(R.plurals.public_count_shown, state.total, state.rows.size, state.total)
                },
                action = stringResource(R.string.public_refresh).takeIf { !state.refreshing },
                onAction = actions::refresh,
            )
        }
        when (state.status) {
            PublicListStatus.Empty -> item(key = "empty") { Message(stringResource(R.string.public_empty)) }
            PublicListStatus.NoMatch -> item(key = "no-match") { Message(stringResource(R.string.public_no_match)) }
            else -> itemsIndexed(state.rows, key = { _, row -> ROW_KEY + row.key }) { index, row ->
                PublicServerListRow(row, divider = index > 0, actions)
            }
        }
        item(key = "note") {
            BasicText(
                text = stringResource(R.string.public_note),
                style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted),
                modifier = Modifier.padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s4),
            )
        }
    }
}

@Composable
private fun PublicServerListRow(row: PublicServerRow, divider: Boolean, actions: PublicServersActions) {
    MumcordListRow(
        title = row.name,
        subtitle = if (row.country.isEmpty()) row.address else stringResource(R.string.public_address_country, row.address, row.country),
        status = pingLine(row.ping),
        // The initials only repeat the name.
        leading = { MumcordTile(row.initials, Modifier.clearAndSetSemantics { }) },
        divider = divider,
        tall = true,
        // Read as one item: name, address and country, ping; the button stays its own.
        modifier = Modifier
            .testTag(PUBLIC_SERVER_ROW_TEST_TAG)
            .semantics(mergeDescendants = true) {},
        trailing = {
            val savedId = row.savedId
            if (savedId != null) {
                val description = stringResource(R.string.servers_connect_description, row.name)
                MumcordButton(
                    text = stringResource(R.string.servers_connect),
                    onClick = { actions.connect(savedId) },
                    style = MumcordButtonStyle.Secondary,
                    small = true,
                    modifier = Modifier
                        .testTag(PUBLIC_SERVER_CONNECT_TEST_TAG)
                        .semantics { contentDescription = description },
                )
            } else {
                val description = stringResource(R.string.public_add_description, row.name)
                MumcordButton(
                    text = stringResource(R.string.public_add),
                    onClick = { actions.add(row.key) },
                    style = MumcordButtonStyle.Secondary,
                    small = true,
                    icon = MumcordIcons.Plus,
                    enabled = !row.adding,
                    modifier = Modifier
                        .testTag(PUBLIC_SERVER_ADD_TEST_TAG)
                        .semantics { contentDescription = description },
                )
            }
        },
    )
}

/** The third line of a row: what the server answered to its ping, nothing before it was asked. */
@Composable
private fun pingLine(ping: PublicPing?): (@Composable RowScope.() -> Unit)? = when (ping) {
    is PublicPing.Answered -> {
        {
            val spoken = pluralStringResource(R.plurals.public_ping_description, ping.maxPeople, ping.people, ping.maxPeople, ping.latencyMs)
            MumcordDot()
            MumcordListRowValue(
                stringResource(R.string.public_ping, ping.people, ping.maxPeople, ping.latencyMs),
                Modifier.semantics { contentDescription = spoken },
            )
        }
    }
    PublicPing.NoAnswer -> {
        {
            MumcordDot(off = true)
            MumcordListRowValue(stringResource(R.string.public_no_answer))
        }
    }
    null -> null
}

@Composable
private fun Failed(actions: PublicServersActions, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize().padding(MumcordSpace.s5),
    ) {
        BasicText(
            text = stringResource(R.string.public_load_failed_title),
            style = MumcordTheme.typography.title.copy(color = MumcordTheme.colors.text, textAlign = TextAlign.Center),
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        BasicText(
            text = stringResource(R.string.public_load_failed),
            style = MumcordTheme.typography.body.copy(color = MumcordTheme.colors.textSecondary, textAlign = TextAlign.Center),
        )
        MumcordButton(
            text = stringResource(R.string.public_retry),
            onClick = actions::refresh,
            modifier = Modifier.testTag(PUBLIC_SERVERS_RETRY_TEST_TAG),
        )
    }
}

/** A calm line in the middle of the screen or the list: loading, an empty list, no match. */
@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(MumcordSpace.s5),
    ) {
        BasicText(
            text = text,
            style = MumcordTheme.typography.body.copy(color = MumcordTheme.colors.textSecondary, textAlign = TextAlign.Center),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
