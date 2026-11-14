// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAvatar
import app.mumcord.mobile.design.components.MumcordAvatarSize
import app.mumcord.mobile.design.components.MumcordBadge
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordFab
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordListRowValue
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.chat.ChatClock
import app.mumcord.mobile.ui.chat.DayLabel
import app.mumcord.mobile.ui.chat.dayLabel
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.connected.avatarSlot
import app.mumcord.mobile.ui.connected.badgeText
import app.mumcord.mobile.ui.connected.initials
import java.time.Instant

const val MESSAGES_SCREEN_TEST_TAG = "messages-screen"
const val MESSAGES_EMPTY_TEST_TAG = "messages-empty"
const val MESSAGES_SEARCH_TEST_TAG = "messages-search"
const val MESSAGES_REQUESTS_ROW_TEST_TAG = "messages-requests"
const val MESSAGES_NEW_TEST_TAG = "messages-new"
const val MESSAGES_NOTIFICATIONS_OFF_TEST_TAG = "messages-notifications-off"
const val CONVERSATION_ROW_TEST_TAG = "conversation-row"
const val REQUESTS_SCREEN_TEST_TAG = "requests-screen"
const val REQUEST_ACCEPT_TEST_TAG = "request-accept"
const val REQUEST_DECLINE_TEST_TAG = "request-decline"
const val PICKER_SCREEN_TEST_TAG = "picker-screen"
const val PICKER_SEARCH_TEST_TAG = "picker-search"
const val PICKER_PERSON_TEST_TAG = "picker-person"
const val BLOCKED_SCREEN_TEST_TAG = "blocked-screen"
const val BLOCKED_UNBLOCK_TEST_TAG = "blocked-unblock"

/** Where the floating button sits above the voice bar in mockup `09-messages`. */
private val FAB_BOTTOM = 44.dp

/** Room under the list so that its last row can be scrolled clear of the floating button. */
private val FAB_CLEARANCE = 120.dp

/**
 * The Messages tab while no conversation is open: the conversation list of mockup
 * `09-messages`, the message requests, or the people to start a conversation with.
 */
@Composable
fun MessagesScreen(
    state: MessagesUiState,
    actions: MessagesController,
    clock: ChatClock,
    onNotificationSettings: () -> Unit,
    topInsets: WindowInsets,
) {
    when (state.page) {
        MessagesPage.List -> ConversationList(state, actions, clock, onNotificationSettings, topInsets)
        MessagesPage.Requests -> RequestsPage(state, actions, topInsets)
        MessagesPage.Picker -> PickerPage(state, actions, topInsets)
    }
}

@Composable
private fun ConversationList(
    state: MessagesUiState,
    actions: MessagesController,
    clock: ChatClock,
    onNotificationSettings: () -> Unit,
    topInsets: WindowInsets,
) {
    Box(Modifier.fillMaxSize().testTag(MESSAGES_SCREEN_TEST_TAG)) {
        Column(Modifier.fillMaxSize()) {
            MumcordAppBar(title = stringResource(R.string.tab_messages), large = true, windowInsets = topInsets) {
                if (state.searching) {
                    MumcordIconButton(MumcordIcons.X, stringResource(R.string.messages_search_close), actions::closeSearch)
                } else if (state.hasConversations) {
                    MumcordIconButton(MumcordIcons.Search, stringResource(R.string.messages_search), actions::openSearch)
                }
            }
            if (state.searching) {
                SearchField(
                    query = state.query,
                    onChange = actions::changeQuery,
                    label = stringResource(R.string.messages_search),
                    tag = MESSAGES_SEARCH_TEST_TAG,
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = FAB_CLEARANCE)) {
                if (state.notificationsOff) {
                    NotificationsOffBanner(onNotificationSettings)
                    Spacer(Modifier.height(MumcordSpace.s2))
                }
                if (state.requests.isNotEmpty() && !state.searching) RequestsRow(state.requests.size, actions::openRequests)
                when {
                    state.rows.isNotEmpty() -> Conversations(state.rows, actions, clock)
                    state.searching -> CenteredNote(stringResource(R.string.messages_search_empty))
                    else -> CenteredNote(stringResource(R.string.messages_empty), Modifier.testTag(MESSAGES_EMPTY_TEST_TAG))
                }
            }
        }
        MumcordFab(
            icon = MumcordIcons.Edit,
            contentDescription = stringResource(R.string.messages_new),
            onClick = actions::openPicker,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = MumcordSpace.s4, bottom = FAB_BOTTOM)
                .testTag(MESSAGES_NEW_TEST_TAG),
        )
    }
}

/** The calm line for a phone that may not show the app's notifications; it leads to the settings. */
@Composable
private fun NotificationsOffBanner(onOpenSettings: () -> Unit) {
    MumcordBanner(
        title = stringResource(R.string.messages_notifications_off_title),
        text = stringResource(R.string.messages_notifications_off_text),
        icon = MumcordIcons.Bell,
        modifier = Modifier
            .testTag(MESSAGES_NOTIFICATIONS_OFF_TEST_TAG)
            .clickable(
                onClickLabel = stringResource(R.string.messages_notifications_off_action),
                role = Role.Button,
                onClick = onOpenSettings,
            ),
    )
}

@Composable
private fun RequestsRow(count: Int, onClick: () -> Unit) {
    MumcordList {
        MumcordListRow(
            title = stringResource(R.string.messages_requests, count),
            subtitle = stringResource(R.string.messages_requests_sub),
            icon = MumcordIcons.User,
            trailing = {
                // The count is in the title; the badge only repeats it for the eye.
                MumcordBadge(badgeText(count), Modifier.clearAndSetSemantics { })
                Chevron()
            },
            onClick = onClick,
            modifier = Modifier.testTag(MESSAGES_REQUESTS_ROW_TEST_TAG),
        )
    }
}

@Composable
private fun Conversations(rows: List<ConversationRow>, actions: MessagesController, clock: ChatClock) {
    Column {
        MumcordSectionHeader(stringResource(R.string.messages_section))
        MumcordList {
            rows.forEachIndexed { index, row ->
                ConversationLine(row, divider = index > 0, clock = clock) {
                    actions.openConversation(row.chat, row.name, row.session)
                }
            }
        }
    }
}

// Names and previews come from other people: they are only ever drawn as plain characters.
@Composable
private fun ConversationLine(row: ConversationRow, divider: Boolean, clock: ChatClock, onClick: () -> Unit) {
    val name = row.name.ifEmpty { stringResource(R.string.messages_unknown_person) }
    val preview = row.preview.text()
    val time = listTime(row.sentAtMs, clock)
    val unread = if (row.unread > 0) pluralStringResource(R.plurals.messages_row_unread, row.unread, row.unread) else null
    val online = if (row.online) stringResource(R.string.messages_online) else null
    // One sentence for screen readers instead of the row's separate pieces.
    val description = listOfNotNull(name, online, unread, preview, time).joinToString(", ")
    MumcordListRow(
        title = name,
        subtitle = preview,
        subtitleEmphasis = row.unread > 0,
        leading = { ConversationAvatar(name, row.online) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.unread > 0) {
                    ListTime(time, emphasis = true)
                    MumcordBadge(badgeText(row.unread))
                } else {
                    ListTime(time, emphasis = false)
                    Spacer(Modifier.height(20.dp))
                }
            }
        },
        divider = divider,
        tall = true,
        onClick = onClick,
        modifier = Modifier
            .testTag(CONVERSATION_ROW_TEST_TAG)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
    )
}

@Composable
private fun ListTime(text: String, emphasis: Boolean) {
    val colors = MumcordTheme.colors
    val style = MumcordTheme.typography.small
    BasicText(
        text = text,
        maxLines = 1,
        style = if (emphasis) {
            style.copy(color = colors.accentText, fontWeight = FontWeight.SemiBold)
        } else {
            style.copy(color = colors.textMuted)
        },
    )
}

/** The avatar of a conversation with the green dot of a person who is on the server (`.pdot`). */
@Composable
private fun ConversationAvatar(name: String, online: Boolean, size: MumcordAvatarSize = MumcordAvatarSize.Conversation) {
    val colors = MumcordTheme.colors
    Box {
        MumcordAvatar(name.initials(1), avatarSlot(name), size = size)
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(14.dp)
                    .background(colors.success, CircleShape)
                    .border(2.dp, colors.bgElevated, CircleShape),
            )
        }
    }
}

@Composable
private fun ConversationPreview.text(): String = when (this) {
    is ConversationPreview.Text -> text
    ConversationPreview.Picture -> stringResource(R.string.messages_preview_picture)
    ConversationPreview.Nothing -> ""
}

/** The time of a message of today, "Yesterday", or the date of an older one. */
@Composable
private fun listTime(sentAtMs: Long, clock: ChatClock): String {
    val day = Instant.ofEpochMilli(sentAtMs).atZone(clock.zone).toLocalDate()
    return when (val label = dayLabel(day, clock.today)) {
        DayLabel.Today -> clock.time(sentAtMs)
        DayLabel.Yesterday -> stringResource(R.string.chat_day_yesterday)
        is DayLabel.Date -> clock.date(label.day)
    }
}

@Composable
private fun Chevron() {
    MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = MumcordTheme.colors.textMuted)
}

@Composable
private fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth().padding(MumcordSpace.s6)) {
        Paragraph(text)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, label: String, tag: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    MumcordTextField(
        value = query,
        onValueChange = onChange,
        label = label,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .padding(bottom = MumcordSpace.s2)
            .focusRequester(focus)
            .testTag(tag),
    )
}

// ----- message requests -----

@Composable
private fun RequestsPage(state: MessagesUiState, actions: MessagesController, topInsets: WindowInsets) {
    Column(Modifier.fillMaxSize().testTag(REQUESTS_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.requests_title),
            onBack = actions::back,
            backContentDescription = stringResource(R.string.requests_back),
            windowInsets = topInsets,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4),
        ) {
            Paragraph(stringResource(R.string.requests_hint), Modifier.padding(horizontal = MumcordSpace.s4))
            state.requests.forEach { request -> RequestCard(request, actions) }
        }
    }
}

@Composable
private fun RequestCard(request: RequestRow, actions: MessagesController) {
    val name = request.name.ifEmpty { stringResource(R.string.messages_unknown_person) }
    val text = request.text.text().ifEmpty {
        if (request.chatRequest) stringResource(R.string.requests_chat_request_empty) else ""
    }
    MumcordList {
        MumcordListRow(
            title = name,
            subtitle = text.ifEmpty { null },
            leading = { ConversationAvatar(name, online = false) },
            trailing = {
                MumcordListRowValue(
                    if (request.chatRequest) {
                        stringResource(R.string.requests_chat_request)
                    } else {
                        pluralStringResource(R.plurals.requests_more_messages, request.messageCount, request.messageCount)
                    },
                )
            },
            tall = true,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MumcordSpace.s4, end = MumcordSpace.s4, bottom = MumcordSpace.s3),
        ) {
            val accept = stringResource(R.string.requests_accept_description, name)
            val decline = stringResource(R.string.requests_decline_description, name)
            MumcordButton(
                text = stringResource(R.string.requests_decline),
                onClick = { actions.declineRequest(request.id) },
                style = MumcordButtonStyle.Secondary,
                modifier = Modifier.weight(1f).testTag(REQUEST_DECLINE_TEST_TAG).semantics { contentDescription = decline },
            )
            MumcordButton(
                text = stringResource(R.string.requests_accept),
                onClick = { actions.acceptRequest(request.id) },
                modifier = Modifier.weight(1f).testTag(REQUEST_ACCEPT_TEST_TAG).semantics { contentDescription = accept },
            )
        }
    }
}

// ----- starting a conversation -----

@Composable
private fun PickerPage(state: MessagesUiState, actions: MessagesController, topInsets: WindowInsets) {
    Column(Modifier.fillMaxSize().testTag(PICKER_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.picker_title),
            onBack = actions::back,
            backContentDescription = stringResource(R.string.requests_back),
            windowInsets = topInsets,
        )
        SearchField(
            query = state.pickerQuery,
            onChange = actions::changePickerQuery,
            label = stringResource(R.string.picker_search),
            tag = PICKER_SEARCH_TEST_TAG,
        )
        if (state.pickerPeople.isEmpty()) {
            CenteredNote(
                stringResource(if (state.pickerQuery.isBlank()) R.string.picker_empty else R.string.picker_no_match),
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
                MumcordList {
                    state.pickerPeople.forEachIndexed { index, person ->
                        MumcordListRow(
                            title = person.name,
                            leading = { ConversationAvatar(person.name, online = false, size = MumcordAvatarSize.Large) },
                            divider = index > 0,
                            onClick = { actions.messagePerson(person.session) },
                            modifier = Modifier.testTag(PICKER_PERSON_TEST_TAG),
                        )
                    }
                }
            }
        }
    }
}

// ----- blocked people (More tab) -----

/** The people the user blocked, each with "Unblock". Reached from the More tab. */
@Composable
fun BlockedPeopleScreen(state: MessagesUiState, actions: MessagesController, topInsets: WindowInsets) {
    Column(Modifier.fillMaxSize().testTag(BLOCKED_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.blocked_title),
            onBack = actions::back,
            backContentDescription = stringResource(R.string.blocked_back),
            windowInsets = topInsets,
        )
        if (state.blocked.isEmpty()) {
            CenteredNote(stringResource(R.string.blocked_empty))
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
            MumcordList {
                state.blocked.forEachIndexed { index, person ->
                    val name = person.name.ifEmpty { stringResource(R.string.messages_unknown_person) }
                    val unblock = stringResource(R.string.blocked_unblock_description, name)
                    MumcordListRow(
                        title = name,
                        subtitle = person.serverLabel.ifEmpty { null },
                        leading = { ConversationAvatar(name, online = false, size = MumcordAvatarSize.Large) },
                        trailing = {
                            MumcordButton(
                                text = stringResource(R.string.person_unblock),
                                onClick = { actions.unblock(person.id) },
                                style = MumcordButtonStyle.Secondary,
                                small = true,
                                modifier = Modifier
                                    .testTag(BLOCKED_UNBLOCK_TEST_TAG)
                                    .semantics { contentDescription = unblock },
                            )
                        },
                        divider = index > 0,
                        tall = true,
                    )
                }
            }
        }
    }
}

