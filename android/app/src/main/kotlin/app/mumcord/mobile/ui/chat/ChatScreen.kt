// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAppBarSubtitle
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordChatHint
import app.mumcord.mobile.design.components.MumcordDaySeparator
import app.mumcord.mobile.design.components.MumcordDot
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordIconButtonStyle
import app.mumcord.mobile.design.components.MumcordInputBar
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordPill
import app.mumcord.mobile.design.components.MumcordReplyBar
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.connected.text
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

const val CHAT_SCREEN_TEST_TAG = "chat-screen"
const val CHAT_LIST_TEST_TAG = "chat-list"
const val CHAT_INPUT_TEST_TAG = "chat-input"
const val CHAT_SEND_TEST_TAG = "chat-send"
const val CHAT_IMAGE_BUTTON_TEST_TAG = "chat-add-image"
const val CHAT_GIF_BUTTON_TEST_TAG = "chat-add-gif"
const val CHAT_NEW_MESSAGES_TEST_TAG = "chat-new-messages"
const val CHAT_REPLY_BAR_TEST_TAG = "chat-reply-bar"
const val CHAT_MENTION_TEST_TAG = "chat-mention"
const val CHAT_EMPTY_TEST_TAG = "chat-empty"
const val CHAT_NOTICE_TEST_TAG = "chat-notice"

/** How times and days are written: the phone's time zone, today's date and its 12 or 24 hour setting. */
data class ChatClock(val zone: ZoneId, val today: LocalDate, val use24Hour: Boolean, val locale: Locale) {
    private val timeFormat = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)

    fun time(epochMs: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun date(day: LocalDate): String = dateFormat.format(day)
}

@Composable
fun rememberChatClock(): ChatClock {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val use24Hour = DateFormat.is24HourFormat(context)
    return remember(zone, today, use24Hour, locale) { ChatClock(zone, today, use24Hour, locale) }
}

private val ROW_GAP = 12.dp
private val MENTION_LIST_MAX_HEIGHT = 216.dp
private const val LOAD_OLDER_WITHIN_ROWS = 6
private const val NOTICE_MILLIS = 4_000L

/**
 * The chat of one channel or person: header, the messages with the newest at the bottom, and
 * the input bar with the reply bar and the `@` suggestions above it.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatController,
    loader: ChatImageLoader,
    clock: ChatClock,
    topInsets: WindowInsets,
) {
    LifecycleResumeEffect(actions) {
        actions.setResumed(true)
        onPauseOrDispose { actions.setResumed(false) }
    }
    Column(Modifier.fillMaxSize().testTag(CHAT_SCREEN_TEST_TAG).keyboardPadding()) {
        MumcordAppBar(
            title = stringResource(R.string.chat_title, state.title),
            border = true,
            windowInsets = topInsets,
            subtitle = {
                MumcordDot()
                MumcordAppBarSubtitle(
                    pluralStringResource(R.plurals.chat_subtitle, state.people, state.people, state.serverName),
                )
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(MumcordTheme.colors.bgChat)) {
            when {
                state.loading -> CenteredNote(stringResource(R.string.chat_loading))
                state.loadFailed -> Column(
                    verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize().padding(MumcordSpace.s6),
                ) {
                    Paragraph(stringResource(R.string.chat_load_failed))
                    MumcordButton(stringResource(R.string.chat_retry), actions::reload, style = MumcordButtonStyle.Secondary)
                }
                state.empty -> CenteredNote(stringResource(R.string.chat_empty), Modifier.testTag(CHAT_EMPTY_TEST_TAG))
                else -> MessageList(state, actions, loader, clock)
            }
            if (state.newMessages > 0) {
                MumcordPill(
                    text = pluralStringResource(R.plurals.chat_new_messages, state.newMessages, state.newMessages),
                    onClick = actions::scrollToNewest,
                    icon = MumcordIcons.ChevronDown,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = MumcordSpace.s1).testTag(CHAT_NEW_MESSAGES_TEST_TAG),
                )
            }
            state.notice?.let { notice ->
                LaunchedEffect(notice) {
                    delay(NOTICE_MILLIS)
                    actions.dismissNotice()
                }
                MumcordBanner(
                    text = stringResource(notice.text()),
                    tone = if (notice == ChatNotice.GifSaved) MumcordBannerTone.Info else MumcordBannerTone.Warning,
                    icon = if (notice == ChatNotice.GifSaved) MumcordIcons.Star else MumcordIcons.Alert,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = MumcordSpace.s2)
                        .testTag(CHAT_NOTICE_TEST_TAG)
                        .clickable(onClickLabel = stringResource(R.string.chat_notice_dismiss), onClick = actions::dismissNotice)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            NewMessageAnnouncement(state)
        }
        Composer(state, actions)
    }
}

private fun ChatNotice.text(): Int = when (this) {
    ChatNotice.GifSaved -> R.string.gif_saved
    ChatNotice.GifNotSaved -> R.string.gif_not_saved
    ChatNotice.ImageUnreadable -> R.string.chat_image_unreadable
}

@Composable
private fun CenteredNote(text: String, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize().padding(MumcordSpace.s6)) {
        Paragraph(text)
    }
}

/**
 * Says the newest message of somebody else to a screen reader, politely: it waits until the
 * reader has finished what it is saying. Nothing is drawn.
 */
@Composable
private fun NewMessageAnnouncement(state: ChatUiState) {
    val newest = state.rows.lastOrNull { it is ChatRow.Message && !it.message.own } as? ChatRow.Message ?: return
    val words = stringResource(
        R.string.chat_announce,
        newest.message.senderName,
        newest.message.content.plainText().ifEmpty { stringResource(R.string.chat_image_word) },
    )
    Box(
        Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = words
        },
    )
}

@Composable
private fun MessageList(state: ChatUiState, actions: ChatController, loader: ChatImageLoader, clock: ChatClock) {
    val listState = rememberLazyListState()
    // The list is laid out from the bottom: row 0 is the newest. Older pages are added at the
    // far end, so reading them in never moves what is on screen.
    val rows = remember(state.rows) { state.rows.asReversed() }

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(atBottom) { actions.setAtBottom(atBottom) }
    LaunchedEffect(state.scrollToken) { listState.scrollToItem(0) }

    val nearOldest by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_OLDER_WITHIN_ROWS
        }
    }
    LaunchedEffect(nearOldest, state.hasMore, state.loadingOlder, rows.size) {
        if (nearOldest && state.hasMore && !state.loadingOlder) actions.loadOlder()
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        // A chat with few messages starts under the header, as in the mockup.
        verticalArrangement = Arrangement.spacedBy(ROW_GAP, Alignment.Top),
        contentPadding = PaddingValues(start = MumcordSpace.s2, top = MumcordSpace.s2, end = MumcordSpace.s2, bottom = MumcordSpace.s3),
        modifier = Modifier.fillMaxSize().testTag(CHAT_LIST_TEST_TAG),
    ) {
        items(items = rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is ChatRow.Day -> MumcordDaySeparator(
                    when (val label = dayLabel(row.day, clock.today)) {
                        DayLabel.Today -> stringResource(R.string.chat_day_today)
                        DayLabel.Yesterday -> stringResource(R.string.chat_day_yesterday)
                        is DayLabel.Date -> clock.date(label.day)
                    },
                )
                is ChatRow.Message -> MessageItem(row, actions, loader, clock)
                is ChatRow.Pending -> PendingItem(row, state.selfName, actions)
                is ChatRow.Hint -> MumcordChatHint(row.hint.text())
            }
        }
        if (state.loadingOlder) {
            item(key = "loading-older") { HistoryNote(stringResource(R.string.chat_loading_older)) }
        } else if (state.historyStart) {
            item(key = "history-start") { HistoryNote(stringResource(R.string.chat_history_start)) }
        }
    }
}

@Composable
private fun HistoryNote(text: String) {
    BasicText(
        text = text,
        style = MumcordTheme.typography.caption.copy(color = MumcordTheme.colors.textMuted, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().padding(vertical = MumcordSpace.s2),
    )
}

/** The reply bar, the `@` suggestions, the counter and the input bar. */
@Composable
private fun Composer(state: ChatUiState, actions: ChatController) {
    val colors = MumcordTheme.colors
    state.reply?.let { reply ->
        MumcordReplyBar(
            title = stringResource(R.string.chat_replying_to, reply.quote.name),
            text = reply.quote.snippet.ifEmpty { stringResource(R.string.chat_image_word) },
            closeContentDescription = stringResource(R.string.chat_reply_cancel),
            onClose = actions::cancelReply,
            modifier = Modifier.testTag(CHAT_REPLY_BAR_TEST_TAG),
        )
    }
    if (state.mentions.isNotEmpty()) MentionList(state.mentions, actions::pickMention)
    state.charactersLeft?.let { left ->
        BasicText(
            text = if (left >= 0) {
                pluralStringResource(R.plurals.chat_characters_left, left, left)
            } else {
                pluralStringResource(R.plurals.chat_characters_over, -left, -left)
            },
            style = MumcordTheme.typography.caption.copy(
                color = if (left >= 0) colors.textMuted else colors.danger,
                textAlign = TextAlign.End,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgChat)
                .padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s1)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    // The field keeps its own cursor while the user types; the state replaces the text only
    // when the app changed it (a sent message, a completed mention).
    var field by remember(state.draftRevision) {
        mutableStateOf(TextFieldValue(state.draft, TextRange(state.cursor)))
    }
    MumcordInputBar(
        value = field,
        onValueChange = {
            val changed = it.text != field.text || it.selection != field.selection
            field = it
            if (changed) actions.changeDraft(it.text, it.selection.end)
        },
        placeholder = stringResource(R.string.chat_placeholder, state.title),
        modifier = Modifier.testTag(CHAT_INPUT_TEST_TAG),
        leading = {
            MumcordIconButton(
                MumcordIcons.Image,
                stringResource(R.string.chat_add_image),
                actions::openImageSources,
                Modifier.testTag(CHAT_IMAGE_BUTTON_TEST_TAG),
            )
            MumcordIconButton(
                MumcordIcons.Gif,
                stringResource(R.string.chat_add_gif),
                actions::openGifs,
                Modifier.testTag(CHAT_GIF_BUTTON_TEST_TAG),
            )
        },
        trailing = {
            MumcordIconButton(
                MumcordIcons.Send,
                stringResource(R.string.chat_send),
                actions::send,
                // Drawn as in the mockup also while there is nothing to send; a tap then does
                // nothing and screen readers hear that it is not available.
                Modifier.testTag(CHAT_SEND_TEST_TAG).semantics { if (!state.canSend) disabled() },
                style = MumcordIconButtonStyle.Accent,
            )
        },
    )
}

@Composable
private fun MentionList(mentions: List<MentionCandidate>, onPick: (MentionCandidate) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MumcordTheme.colors.bgChat)
            .heightIn(max = MENTION_LIST_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(vertical = MumcordSpace.s2),
    ) {
        MumcordList {
            mentions.forEachIndexed { index, candidate ->
                MumcordListRow(
                    // The name is untrusted text and shown as plain characters.
                    title = stringResource(R.string.chat_mention_item, candidate.name),
                    icon = if (candidate.session == null) MumcordIcons.Users else MumcordIcons.User,
                    divider = index > 0,
                    onClick = { onPick(candidate) },
                    modifier = Modifier.testTag(CHAT_MENTION_TEST_TAG),
                )
            }
        }
    }
}

/**
 * Keeps the chat above the keyboard. The bars under the chat (voice bar, tab bar) already take
 * part of the keyboard's height, so only what is left of it is padded.
 */
@Composable
private fun Modifier.keyboardPadding(): Modifier {
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density)
    var below by remember { mutableIntStateOf(0) }
    return onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates()
        below = (root.size.height - (coordinates.positionInRoot().y + coordinates.size.height)).roundToInt()
    }.padding(bottom = with(density) { (keyboard - below).coerceAtLeast(0).toDp() })
}
