// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordDot
import app.mumcord.mobile.design.components.MumcordFab
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordTile
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.ui.connect.messageRes

const val SERVER_ROW_TEST_TAG = "server-row"
const val SERVER_CONNECT_TEST_TAG = "server-connect"
const val SERVER_ADD_TEST_TAG = "server-add"
const val CONNECT_ERROR_TEST_TAG = "connect-error"
const val LOCAL_NETWORK_SETTINGS_TEST_TAG = "local-network-settings"
const val CONNECT_RETRY_TEST_TAG = "connect-retry"

/** What the local network permission has to do with a failed connection. */
enum class LocalNetworkHint {
    /** Access is refused, so the server on the local network was not even tried. */
    Denied,

    /** The server has a private address and may be out of reach only because access is missing. */
    Suspected,
}

/**
 * A failed connection shown above the list. [serverName] is null when no server is involved;
 * [localNetwork] is set when the missing local network permission is, or may be, the reason.
 */
data class ConnectFailure(
    val error: ConnectionError,
    val serverName: String?,
    val localNetwork: LocalNetworkHint? = null,
)

/**
 * The saved servers with people online and latency, and the way to add, edit, remove and
 * connect. While [editing], every row offers Edit and Remove instead of Connect.
 */
@Composable
fun ServerListScreen(
    rows: List<ServerRow>,
    loaded: Boolean,
    editing: Boolean,
    failure: ConnectFailure?,
    onConnect: (Long) -> Unit,
    onCancelConnect: () -> Unit,
    onAdd: () -> Unit,
    onToggleEditing: () -> Unit,
    onEdit: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen {
        MumcordAppBar(title = stringResource(R.string.servers_title), large = true, windowInsets = topInsets)
        if (failure != null) FailureBanner(failure, onRetry, onOpenSettings, onDismissError)
        when {
            // Nothing to show before the list is read; an empty state would flash by.
            !loaded -> Unit
            rows.isEmpty() -> EmptyState(onAdd, Modifier.weight(1f).windowInsetsPadding(bottomInsets))
            else -> Box(Modifier.weight(1f).windowInsetsPadding(bottomInsets)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MumcordSectionHeader(
                        title = stringResource(R.string.servers_favourites),
                        action = stringResource(if (editing) R.string.servers_done else R.string.servers_edit),
                        onAction = onToggleEditing,
                    )
                    MumcordList {
                        rows.forEachIndexed { index, row ->
                            ServerListRow(row, index > 0, editing, onConnect, onCancelConnect, onEdit, onRemove)
                        }
                    }
                    // Room to scroll the last row out from under the add button.
                    Spacer(Modifier.height(FAB_CLEARANCE))
                }
                MumcordFab(
                    icon = MumcordIcons.Plus,
                    contentDescription = stringResource(R.string.servers_add),
                    onClick = onAdd,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MumcordSpace.s4, bottom = MumcordSpace.s3)
                        .testTag(SERVER_ADD_TEST_TAG),
                )
            }
        }
    }
}

@Composable
private fun ServerListRow(
    row: ServerRow,
    divider: Boolean,
    editing: Boolean,
    onConnect: (Long) -> Unit,
    onCancelConnect: () -> Unit,
    onEdit: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val connectDescription = stringResource(R.string.servers_connect_description, row.name)
    val cancelDescription = stringResource(R.string.servers_cancel_description, row.name)
    MumcordListRow(
        title = row.name,
        subtitle = row.address,
        status = statusLine(row),
        leading = { MumcordTile(row.initials, brand = row.highlighted) },
        divider = divider,
        tall = true,
        modifier = Modifier.testTag(SERVER_ROW_TEST_TAG),
        trailing = {
            when {
                row.connecting -> MumcordButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancelConnect,
                    style = MumcordButtonStyle.Secondary,
                    small = true,
                    modifier = Modifier.semantics { contentDescription = cancelDescription },
                )
                editing -> {
                    MumcordIconButton(
                        icon = MumcordIcons.Edit,
                        contentDescription = stringResource(R.string.servers_edit_description, row.name),
                        onClick = { onEdit(row.id) },
                    )
                    MumcordIconButton(
                        icon = MumcordIcons.Trash,
                        contentDescription = stringResource(R.string.servers_remove_description, row.name),
                        onClick = { onRemove(row.id) },
                    )
                }
                else -> MumcordButton(
                    text = stringResource(R.string.servers_connect),
                    onClick = { onConnect(row.id) },
                    style = if (row.highlighted) MumcordButtonStyle.Primary else MumcordButtonStyle.Secondary,
                    small = true,
                    modifier = Modifier
                        .testTag(SERVER_CONNECT_TEST_TAG)
                        .semantics { contentDescription = connectDescription },
                )
            }
        },
    )
}

/** The third line of a row: connecting, or what the server answered to the ping. */
@Composable
private fun statusLine(row: ServerRow): (@Composable RowScope.() -> Unit)? {
    val ping = row.ping
    return when {
        row.connecting -> {
            {
                MumcordListRowValue(
                    stringResource(R.string.servers_connecting),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        ping is PingState.Online -> {
            {
                MumcordDot()
                MumcordListRowValue(
                    pluralStringResource(R.plurals.servers_online, ping.peopleOnline, ping.peopleOnline, ping.latencyMs),
                )
            }
        }
        ping is PingState.Offline -> {
            {
                MumcordDot(off = true)
                MumcordListRowValue(stringResource(R.string.servers_offline))
            }
        }
        else -> null
    }
}

@Composable
private fun FailureBanner(
    failure: ConnectFailure,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .testTag(CONNECT_ERROR_TEST_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        MumcordBanner(
            title = if (failure.serverName != null) {
                stringResource(R.string.error_title_server, failure.serverName)
            } else {
                stringResource(R.string.error_title)
            },
            text = when (failure.localNetwork) {
                LocalNetworkHint.Denied -> stringResource(R.string.local_network_denied)
                LocalNetworkHint.Suspected -> stringResource(R.string.local_network_suspected)
                null -> stringResource(failure.error.messageRes())
            },
            icon = MumcordIcons.Alert,
            tone = MumcordBannerTone.Danger,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier.padding(horizontal = MumcordSpace.s4),
        ) {
            if (failure.localNetwork == LocalNetworkHint.Denied) {
                MumcordButton(
                    text = stringResource(R.string.local_network_open_settings),
                    onClick = onOpenSettings,
                    small = true,
                    modifier = Modifier.testTag(LOCAL_NETWORK_SETTINGS_TEST_TAG),
                )
            }
            MumcordButton(
                text = stringResource(
                    if (failure.error == ConnectionError.WrongPassword && failure.serverName != null) {
                        R.string.error_enter_password
                    } else {
                        R.string.error_retry
                    },
                ),
                onClick = onRetry,
                style = if (failure.localNetwork == LocalNetworkHint.Denied) {
                    MumcordButtonStyle.Secondary
                } else {
                    MumcordButtonStyle.Primary
                },
                small = true,
                modifier = Modifier.testTag(CONNECT_RETRY_TEST_TAG),
            )
            MumcordButton(
                text = stringResource(R.string.error_dismiss),
                onClick = onDismiss,
                style = MumcordButtonStyle.Ghost,
                small = true,
            )
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize().padding(MumcordSpace.s5),
    ) {
        BasicText(
            text = stringResource(R.string.servers_empty_title),
            style = MumcordTheme.typography.title.copy(color = MumcordTheme.colors.text, textAlign = TextAlign.Center),
            modifier = Modifier.semantics { heading() },
        )
        BasicText(
            text = stringResource(R.string.servers_empty_text),
            style = MumcordTheme.typography.body.copy(
                color = MumcordTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            ),
        )
        MumcordButton(
            text = stringResource(R.string.servers_add),
            onClick = onAdd,
            icon = MumcordIcons.Plus,
            modifier = Modifier.testTag(SERVER_ADD_TEST_TAG),
        )
    }
}

private val FAB_CLEARANCE = 84.dp
