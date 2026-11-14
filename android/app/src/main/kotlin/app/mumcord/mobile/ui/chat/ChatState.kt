// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ChatPage
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.UnsupportedHint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Consecutive messages of one sender within this time share one avatar and name, as on desktop. */
const val GROUP_GAP_MS = 5 * 60 * 1000L

/** The counter appears once this share of the text limit is used. */
private const val COUNTER_FROM = 0.9

/** The message a reply is being written to. */
data class ReplyTarget(val messageId: String, val quote: ReplyQuote)

/** What a message that is on its way, or did not get out, consists of. */
sealed interface PendingContent {
    data class Text(val text: String, val reply: ReplyQuote?) : PendingContent

    /** A picture from the photo library or the camera, in its original encoding. */
    class Image(val bytes: ByteArray, val mime: String) : PendingContent

    data class Gif(val url: String) : PendingContent
}

enum class SendFailure {
    /** It did not get out; trying again may work. */
    NotSent,

    /** The server or the size rules refuse it; trying again cannot work. */
    Refused,
}

/** An own message that is being sent ([failure] null) or could not be sent. */
data class PendingSend(val id: Long, val content: PendingContent, val failure: SendFailure? = null)

/** An image of a message, as the image loader is asked for it. */
sealed interface ChatImageSource {
    /** Sent inside the message; [width] and [height] are known before it is loaded. */
    data class Embedded(
        val chat: ChatId,
        val messageId: String,
        val imageId: String,
        val width: Int,
        val height: Int,
    ) : ChatImageSource

    /** On the web. [gif] marks one that can be kept as a GIF favourite. */
    data class Remote(val url: String, val gif: Boolean) : ChatImageSource
}

enum class ChatSheetKind { Gifs, ImageSource }

enum class ChatNotice { GifSaved, GifNotSaved, ImageUnreadable }

/**
 * Everything the chat screen remembers about the chat that is open. It is replaced when
 * another chat opens, except for what the user typed.
 */
data class ChatLocal(
    val chat: ChatId? = null,
    /** Oldest first, every id once. */
    val messages: List<ChatMessage> = emptyList(),
    val hasMore: Boolean = false,
    /** An older page was read, so the list says where the history begins. */
    val pagedOlder: Boolean = false,
    /** The first page is being read. */
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val loadFailed: Boolean = false,
    val draft: String = "",
    /** Where the cursor is in [draft]; the `@` autocomplete looks at the word before it. */
    val cursor: Int = 0,
    /** Counts up whenever the app, not the user, changed [draft]; the field then takes it over. */
    val draftRevision: Int = 0,
    val reply: ReplyTarget? = null,
    val mentions: List<MentionCandidate> = emptyList(),
    val pending: List<PendingSend> = emptyList(),
    val limits: ChatLimits? = null,
    /** The newest message is on screen. */
    val atBottom: Boolean = true,
    /** The chat is on screen: its tab is selected and the app is in front. */
    val visible: Boolean = false,
    /** Messages that arrived while the user was reading further up. */
    val newBelow: Int = 0,
    /** Counts up whenever the list has to jump to the newest message. */
    val scrollToken: Int = 0,
    val sheet: ChatSheetKind? = null,
    val gifs: List<GifFavourite> = emptyList(),
    /** A link whose text names another address, waiting for the user's decision. */
    val linkWarning: SafeLink? = null,
    /** An address to hand to the system browser. */
    val urlToOpen: String? = null,
    val viewer: ChatImageSource? = null,
    val notice: ChatNotice? = null,
)

/** The first page of a chat arrived. */
fun ChatLocal.loaded(page: ChatPage): ChatLocal =
    copy(
        messages = merge(page.messages, messages),
        hasMore = page.hasMore,
        loading = false,
        loadFailed = false,
        scrollToken = scrollToken + 1,
    )

/** An older page arrived; it goes in front of what is there. */
fun ChatLocal.prepended(page: ChatPage): ChatLocal =
    copy(messages = merge(page.messages, messages), hasMore = page.hasMore, loadingOlder = false, pagedOlder = true)

/**
 * A message of somebody else arrived. It is appended; the list follows it only when the user
 * is looking at the newest message, otherwise it is counted for the "New messages" pill.
 */
fun ChatLocal.received(message: ChatMessage): ChatLocal {
    if (message.chat != chat || messages.any { it.id == message.id }) return this
    val following = atBottom && visible
    return copy(
        messages = messages + message,
        scrollToken = if (following) scrollToken + 1 else scrollToken,
        newBelow = if (following || message.blocked) newBelow else newBelow + 1,
    )
}

/** The user wants an own message sent: it is shown at once and the list jumps to it. */
fun ChatLocal.sending(send: PendingSend): ChatLocal =
    copy(pending = pending + send, scrollToken = scrollToken + 1, newBelow = 0)

/** An own message got out: it takes the place of its pending row. */
fun ChatLocal.sent(pendingId: Long, message: ChatMessage): ChatLocal =
    copy(
        pending = pending.filterNot { it.id == pendingId },
        messages = merge(messages, listOf(message)),
        scrollToken = scrollToken + 1,
    )

fun ChatLocal.sendFailed(pendingId: Long, failure: SendFailure): ChatLocal =
    copy(pending = pending.map { if (it.id == pendingId) it.copy(failure = failure) else it })

/** The user scrolled; reaching the newest message puts the pill away. */
fun ChatLocal.scrolled(atBottom: Boolean): ChatLocal =
    copy(atBottom = atBottom, newBelow = if (atBottom) 0 else newBelow)

/** [older] followed by [newer], every id once; the first occurrence wins. */
private fun merge(older: List<ChatMessage>, newer: List<ChatMessage>): List<ChatMessage> =
    (older + newer).distinctBy { it.id }

// ----- rows -----

/** One row of the chat list. [key] is stable, so the list keeps its place when rows are added. */
sealed interface ChatRow {
    val key: String

    data class Day(val day: LocalDate) : ChatRow {
        override val key = "day-$day"
    }

    /** [showHeader] is false for a message that continues the group of the one above it. */
    data class Message(val message: ChatMessage, val showHeader: Boolean) : ChatRow {
        override val key = "message-${message.id}"
    }

    data class Pending(val send: PendingSend, val showHeader: Boolean) : ChatRow {
        override val key = "pending-${send.id}"
    }

    data class Hint(val hint: UnsupportedHint) : ChatRow {
        override val key = "hint-${hint.id}"
    }
}

/**
 * The rows of a chat, oldest first: a separator before the first message of each day, the
 * messages (those of blocked people are left out), the own messages still on their way, and
 * the calm hints for things this app cannot show.
 */
fun chatRows(
    messages: List<ChatMessage>,
    pending: List<PendingSend>,
    hints: List<UnsupportedHint>,
    zone: ZoneId,
): List<ChatRow> = buildList {
    var previous: ChatMessage? = null
    for (message in messages) {
        if (message.blocked) continue
        val day = message.day(zone)
        val before = previous
        val newDay = before == null || before.day(zone) != day
        if (newDay) add(ChatRow.Day(day))
        val grouped = before != null && !newDay && before.sameSender(message) &&
            message.sentAtMs - before.sentAtMs <= GROUP_GAP_MS
        add(ChatRow.Message(message, showHeader = !grouped))
        previous = message
    }
    pending.forEachIndexed { index, send ->
        add(ChatRow.Pending(send, showHeader = index == 0 && previous?.own != true))
    }
    hints.forEach { add(ChatRow.Hint(it)) }
}

private fun ChatMessage.day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(sentAtMs).atZone(zone).toLocalDate()

private fun ChatMessage.sameSender(other: ChatMessage): Boolean =
    own == other.own && senderName == other.senderName && senderCertHash == other.senderCertHash

/** How a day separator names its day. */
sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class Date(val day: LocalDate) : DayLabel
}

fun dayLabel(day: LocalDate, today: LocalDate): DayLabel = when (day) {
    today -> DayLabel.Today
    today.minusDays(1) -> DayLabel.Yesterday
    else -> DayLabel.Date(day)
}

// ----- writing -----

/**
 * What was typed after the `@` the cursor is in, or null when the cursor is not in a mention.
 * A mention starts at the beginning of the text or after a blank, so mail addresses do not
 * open the list.
 */
fun mentionQuery(text: String, cursor: Int): String? {
    val end = cursor.coerceIn(0, text.length)
    var start = end
    while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '@') start--
    if (start == 0 || text[start - 1] != '@') return null
    val at = start - 1
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    return text.substring(start, end)
}

/** [text] with the mention under the cursor completed to `@name ` and the new cursor position. */
fun insertMention(text: String, cursor: Int, name: String): Pair<String, Int> {
    val end = cursor.coerceIn(0, text.length)
    val query = mentionQuery(text, end) ?: return text to end
    val at = end - query.length - 1
    val completed = text.substring(0, at) + "@" + name + " "
    return completed + text.substring(end) to completed.length
}

/**
 * How many characters are left, shown once the text gets close to the server's limit; negative
 * when it is over. Null while there is room, or when the server has no limit.
 */
fun charactersLeft(text: String, limits: ChatLimits?): Int? {
    val limit = limits?.text?.toInt() ?: return null
    if (limit <= 0) return null
    val length = text.codePointCount(0, text.length)
    return (limit - length).takeIf { length >= limit * COUNTER_FROM }
}

fun canSend(text: String, limits: ChatLimits?): Boolean {
    if (text.isBlank()) return false
    val limit = limits?.text?.toInt() ?: 0
    return limit <= 0 || text.codePointCount(0, text.length) <= limit
}

// ----- the screen -----

/** The sheet over the chat. At most one at a time. */
sealed interface ChatSheet {
    data class Gifs(val favourites: List<GifFavourite>) : ChatSheet

    data object ImageSource : ChatSheet

    data class LinkWarning(val link: SafeLink) : ChatSheet
}

/** The chat screen as a value. */
data class ChatUiState(
    val chat: ChatId,
    /** The channel's name; untrusted text. */
    val title: String,
    val people: Int,
    val serverName: String,
    /** The user's own name, for the messages that are still on their way. */
    val selfName: String,
    val rows: List<ChatRow>,
    val loading: Boolean,
    val loadingOlder: Boolean,
    val loadFailed: Boolean,
    /** Older messages can still be read from the phone's history. */
    val hasMore: Boolean,
    /** The oldest message kept on the phone is in the list, after reading back. */
    val historyStart: Boolean,
    val draft: String,
    val cursor: Int,
    val draftRevision: Int,
    val charactersLeft: Int?,
    val canSend: Boolean,
    val reply: ReplyTarget?,
    val mentions: List<MentionCandidate>,
    val newMessages: Int,
    val scrollToken: Int,
    val sheet: ChatSheet?,
    val viewer: ChatImageSource?,
    val urlToOpen: String?,
    val notice: ChatNotice?,
) {
    /** Nothing was ever written here, and nothing is on its way. */
    val empty: Boolean
        get() = !loading && !loadFailed && rows.isEmpty()
}

/**
 * The Chat tab: the chat of the channel the user is in. Null while no server is shown or the
 * user's channel is unknown.
 */
fun chatUiState(session: SessionState, local: ChatLocal, serverName: String, zone: ZoneId): ChatUiState? {
    val server = session.server?.takeIf { session.showsServer } ?: return null
    val channelId = server.selfChannelId ?: return null
    val chat = local.chat?.takeIf { it == ChatId.Channel(channelId) } ?: return null
    val linkWarning = local.linkWarning
    return ChatUiState(
        chat = chat,
        title = server.channels[channelId]?.name.orEmpty(),
        people = server.people.values.count { it.channelId == channelId },
        serverName = serverName,
        selfName = server.people[server.selfSession]?.name.orEmpty(),
        rows = chatRows(local.messages, local.pending, session.hints, zone),
        loading = local.loading,
        loadingOlder = local.loadingOlder,
        loadFailed = local.loadFailed,
        hasMore = local.hasMore,
        historyStart = local.pagedOlder && !local.hasMore,
        draft = local.draft,
        cursor = local.cursor,
        draftRevision = local.draftRevision,
        charactersLeft = charactersLeft(local.draft, local.limits),
        canSend = canSend(local.draft, local.limits),
        reply = local.reply,
        mentions = local.mentions,
        newMessages = local.newBelow,
        scrollToken = local.scrollToken,
        sheet = when {
            linkWarning != null -> ChatSheet.LinkWarning(linkWarning)
            local.sheet == ChatSheetKind.Gifs -> ChatSheet.Gifs(local.gifs)
            local.sheet == ChatSheetKind.ImageSource -> ChatSheet.ImageSource
            else -> null
        },
        viewer = local.viewer,
        urlToOpen = local.urlToOpen,
        notice = local.notice,
    )
}

/**
 * The number on the Chat tab: unread messages of the current channel's chat, and nothing while
 * that tab is open.
 */
fun chatTabBadge(unread: Map<ChatId, Int>, session: SessionState, chatTabOpen: Boolean): Int {
    if (chatTabOpen) return 0
    val channelId = session.server?.takeIf { session.showsServer }?.selfChannelId ?: return 0
    return unread[ChatId.Channel(channelId)] ?: 0
}
