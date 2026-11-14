// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.messages

import app.mumcord.mobile.bridge.BlockedPerson
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.Conversation
import app.mumcord.mobile.bridge.MessageRequests
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.session.ServerModel
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.ui.chat.plainText

/** What the Messages tab shows while no conversation is open. */
enum class MessagesPage { List, Requests, Picker }

/**
 * The conversation that is open. [name] and [session] are what was known when it was opened
 * (from the person sheet, the people picker or a request); the conversation list knows better
 * once it has the conversation.
 */
data class OpenConversation(val chat: ChatId, val name: String = "", val session: UInt? = null)

/** Why a chat request did not get out. */
enum class ChatRequestError {
    /** The other person's app cannot receive chat requests. */
    Refused,

    /** Something else went wrong; trying again may work. */
    Failed,
}

/** What the user did on the Messages tab and with blocking; everything else follows from the engine. */
data class MessagesLocal(
    val page: MessagesPage = MessagesPage.List,
    val open: OpenConversation? = null,
    val searching: Boolean = false,
    val query: String = "",
    val pickerQuery: String = "",
    /** The person the "Block?" question is about. */
    val blockSession: UInt? = null,
    val blockFailed: Boolean = false,
    /** The person the chat request being written goes to. */
    val requestSession: UInt? = null,
    val requestNote: String = "",
    val requestSending: Boolean = false,
    val requestError: ChatRequestError? = null,
    /** The list of blocked people is open on the More tab. */
    val blockedOpen: Boolean = false,
    /** Notifications name the sender and leave the message out. */
    val senderOnly: Boolean = false,
)

/** The last message of a conversation as the list shows it. */
sealed interface ConversationPreview {
    data class Text(val text: String) : ConversationPreview

    /** A picture without words. */
    data object Picture : ConversationPreview

    data object Nothing : ConversationPreview
}

/** One line of the conversation list. [name] is untrusted text and may be empty. */
data class ConversationRow(
    val chat: ChatId,
    val name: String,
    val session: UInt?,
    val preview: ConversationPreview,
    val sentAtMs: Long,
    val unread: Int,
    val online: Boolean,
)

/** One held request. [text] is the note of a chat request or the first held message. */
data class RequestRow(
    val id: ULong,
    val name: String,
    /** A chat request of another Mumcord user; otherwise held messages. */
    val chatRequest: Boolean,
    val text: ConversationPreview,
    val messageCount: Int,
)

/** A person on the server who can be written to. */
data class PickerPerson(val session: UInt, val name: String)

data class BlockedRow(val id: Long, val name: String, val serverLabel: String)

/** Whether the person sheet offers a chat request. */
enum class ChatRequestOffer {
    /** None was sent yet. */
    Offer,

    /** One is on its way; a decline is never told, so it stays like this. */
    Pending,

    /** The other person accepted, or wrote first: nothing to ask for. */
    Accepted,

    /** Their app does not understand chat requests (another Mumble client): nothing to offer. */
    Unsupported,
    ;

    /** The person sheet has a row for it. */
    val shown: Boolean
        get() = this == Offer || this == Pending
}

/** What the person sheet shows about messages and blocking for one person. */
data class PersonMessaging(
    /** Blocked by the user, on this connection or an earlier one: the sheet offers "Unblock". */
    val blocked: Boolean,
    val chatRequest: ChatRequestOffer,
)

/** A question in a bottom sheet that belongs to messages and blocking. */
sealed interface MessagesSheet {
    data class BlockConfirm(val session: UInt, val name: String, val failed: Boolean) : MessagesSheet

    data class ChatRequest(
        val session: UInt,
        val name: String,
        val note: String,
        val sending: Boolean,
        val error: ChatRequestError?,
    ) : MessagesSheet
}

data class MessagesUiState(
    val page: MessagesPage,
    /** The open conversation with the other person's name and whether they are on the server. */
    val open: OpenConversation?,
    val openOnline: Boolean,
    /** The conversations that match the search. */
    val rows: List<ConversationRow>,
    /** There are conversations at all, whatever the search hides. */
    val hasConversations: Boolean,
    val searching: Boolean,
    val query: String,
    val requests: List<RequestRow>,
    val pickerQuery: String,
    val pickerPeople: List<PickerPerson>,
    /** The phone may not show notifications for the app. */
    val notificationsOff: Boolean,
    /** The number on the Messages tab. */
    val badge: Int,
    val sheet: MessagesSheet?,
    val blocked: List<BlockedRow>,
    val blockedOpen: Boolean,
    val senderOnly: Boolean,
    private val people: Map<UInt, Person>,
    private val sentRequests: Map<UInt, Boolean>,
) {
    /** Something on the Messages or More tab that the back button closes before it leaves the tab. */
    val canGoBack: Boolean
        get() = open != null || page != MessagesPage.List || searching || blockedOpen

    fun person(session: UInt): PersonMessaging {
        val person = people[session]
        return PersonMessaging(
            blocked = person?.blocked == true,
            chatRequest = when {
                person?.supportsChatRequests != true -> ChatRequestOffer.Unsupported
                sentRequests[session] == null -> ChatRequestOffer.Offer
                sentRequests[session] == false -> ChatRequestOffer.Pending
                else -> ChatRequestOffer.Accepted
            },
        )
    }
}

/** The Messages tab and the blocking screens as a value, or null while no server is shown. */
fun messagesUiState(
    session: SessionState,
    local: MessagesLocal,
    conversations: List<Conversation>,
    requests: MessageRequests,
    blocked: List<BlockedPerson>,
    unread: Map<ChatId, Int>,
    notificationsGranted: Boolean,
): MessagesUiState? {
    val server = session.server?.takeIf { session.showsServer } ?: return null
    val all = conversations.map { it.toRow(server, unread) }
    val needle = local.query.trim()
    val open = local.open?.let { open ->
        val listed = all.firstOrNull { it.chat == open.chat }
        val sessionNow = listed?.session ?: open.session ?: (open.chat as? ChatId.Session)?.session
        open.copy(
            name = listed?.name?.ifEmpty { null } ?: open.name.ifEmpty { sessionNow?.let(server.people::get)?.name.orEmpty() },
            session = sessionNow?.takeIf { it in server.people },
        )
    }
    return MessagesUiState(
        page = local.page,
        open = open,
        openOnline = open?.session != null,
        rows = if (local.searching && needle.isNotEmpty()) all.filter { it.name.contains(needle, ignoreCase = true) } else all,
        hasConversations = all.isNotEmpty(),
        searching = local.searching,
        query = local.query,
        requests = requests.incoming.map { request ->
            RequestRow(
                id = request.id,
                name = request.name,
                chatRequest = request.chatRequest,
                text = request.note?.takeIf { it.isNotBlank() }?.let(ConversationPreview::Text)
                    ?: request.messages.firstOrNull()?.preview()
                    ?: ConversationPreview.Nothing,
                messageCount = request.messages.size,
            )
        },
        pickerQuery = local.pickerQuery,
        pickerPeople = pickerPeople(server, local.pickerQuery),
        notificationsOff = !notificationsGranted,
        badge = messagesTabBadge(unread),
        sheet = sheet(server, local),
        blocked = blocked.map { BlockedRow(it.id, it.name, it.serverLabel) },
        blockedOpen = local.blockedOpen,
        senderOnly = local.senderOnly,
        people = server.people,
        sentRequests = requests.sent.associate { it.session to it.accepted },
    )
}

private fun sheet(server: ServerModel, local: MessagesLocal): MessagesSheet? {
    val blockPerson = local.blockSession?.let(server.people::get)
    val requestPerson = local.requestSession?.let(server.people::get)
    return when {
        blockPerson != null -> MessagesSheet.BlockConfirm(blockPerson.session, blockPerson.name, local.blockFailed)
        requestPerson != null -> MessagesSheet.ChatRequest(
            session = requestPerson.session,
            name = requestPerson.name,
            note = local.requestNote,
            sending = local.requestSending,
            error = local.requestError,
        )
        else -> null
    }
}

private fun Conversation.toRow(server: ServerModel, unread: Map<ChatId, Int>): ConversationRow {
    val person = peerSession?.let(server.people::get)
    return ConversationRow(
        chat = chat,
        // Empty when only own messages are stored; the person may still be on the server.
        name = peerName.ifEmpty { person?.name.orEmpty() },
        session = person?.session,
        preview = last.preview(),
        sentAtMs = last.sentAtMs,
        // The live count: the one in the list is as old as the list.
        unread = unread[chat] ?: 0,
        online = person != null,
    )
}

private fun ChatMessage.preview(): ConversationPreview {
    val text = content.plainText()
    return when {
        text.isNotEmpty() -> ConversationPreview.Text(text)
        content.blocks.any { it is ContentBlock.Image } -> ConversationPreview.Picture
        else -> ConversationPreview.Nothing
    }
}

/** Everybody else on the server by name; a non-blank [query] filters by name, ignoring case. */
fun pickerPeople(server: ServerModel, query: String): List<PickerPerson> {
    val needle = query.trim()
    return server.people.values
        .filter { it.session != server.selfSession && it.name.contains(needle, ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .map { PickerPerson(it.session, it.name) }
}

/** The number on the Messages tab: unread private messages of all conversations. */
fun messagesTabBadge(unread: Map<ChatId, Int>): Int =
    unread.entries.sumOf { (chat, count) -> if (chat is ChatId.Channel) 0 else count }

/** The longest note of a chat request, in characters (the engine's limit). */
const val CHAT_REQUEST_NOTE_MAX = 200
