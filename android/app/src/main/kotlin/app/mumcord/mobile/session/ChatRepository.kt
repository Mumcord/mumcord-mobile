// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BlockedPerson
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ChatNotification
import app.mumcord.mobile.bridge.ChatPage
import app.mumcord.mobile.bridge.Conversation
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.ImageData
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.MessageRequests
import app.mumcord.mobile.bridge.PrivateMessageSettings
import app.mumcord.mobile.bridge.ReplyQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether images and GIFs from the web may be loaded right now. */
fun interface MediaPolicy {
    fun loadMedia(): Boolean
}

/**
 * The chat of the connection: unread counts, arriving messages and the chat commands. It has
 * its own flows, apart from [SessionRepository.state], so chat traffic never redraws the
 * channel list. Channel chats and private conversations both go through it, and so do the list
 * of private conversations, the message requests and the blocked people.
 *
 * @param mediaPolicy the one place that decides about loading images and GIFs from the web;
 *   the "Load images and GIFs" setting plugs in here.
 */
class ChatRepository internal constructor(
    private val session: SessionRepository,
    scope: CoroutineScope,
    private val mediaPolicy: MediaPolicy,
) {
    private val mutableUnread = MutableStateFlow<Map<ChatId, Int>>(emptyMap())

    /** Unread messages per chat; a chat without any is not in the map. */
    val unread: StateFlow<Map<ChatId, Int>> = mutableUnread.asStateFlow()

    private val mutableIncoming = MutableSharedFlow<ChatMessage>(
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Messages of others as they arrive. Own messages are returned by the send commands. */
    val incoming: SharedFlow<ChatMessage> = mutableIncoming.asSharedFlow()

    private val mutableNotifications = MutableSharedFlow<ChatNotification>(
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Messages worth a system notification: private messages and mentions in a chat that is not on screen. */
    val notifications: SharedFlow<ChatNotification> = mutableNotifications.asSharedFlow()

    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())

    /** The stored private conversations of the connected server, newest first. */
    val conversations: StateFlow<List<Conversation>> = mutableConversations.asStateFlow()

    private val mutableRequests = MutableStateFlow(NO_REQUESTS)

    /** The held message requests and the chat requests the user sent; they last for the connection. */
    val requests: StateFlow<MessageRequests> = mutableRequests.asStateFlow()

    private val mutableBlocked = MutableStateFlow<List<BlockedPerson>>(emptyList())

    /** The people the user blocked with the identity of this connection. */
    val blocked: StateFlow<List<BlockedPerson>> = mutableBlocked.asStateFlow()

    private val mutableOpenChat = MutableStateFlow<ChatId?>(null)

    /** The chat that is on screen in front of the user, or null. */
    val openChat: StateFlow<ChatId?> = mutableOpenChat.asStateFlow()

    // Several screens can show a chat (the Chat tab, a private conversation), but only one is
    // on screen at a time. Each keeps its own entry, so one that goes away cannot take the
    // open chat of the other with it.
    private val openChats = LinkedHashMap<Any, ChatId>()

    // The engine has no event for a changed conversation list; it is read again when asked
    // for here. Conflated: several reasons in a row cost one read.
    private val conversationReads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (event in session.chatEvents) {
                when (event) {
                    is BridgeEvent.ChatMessageReceived -> {
                        mutableIncoming.emit(event.message)
                        if (event.message.chat !is ChatId.Channel) refreshConversations()
                    }
                    is BridgeEvent.ChatUnreadChanged -> mutableUnread.update { it.withUnread(event.chat, event.count.toInt()) }
                    is BridgeEvent.ChatNotification -> mutableNotifications.emit(event.notification)
                    is BridgeEvent.MessageRequestsChanged -> {
                        mutableRequests.value = event.requests
                        // An accepted request turns its held messages into a conversation.
                        refreshConversations()
                    }
                    is BridgeEvent.BlockedPeopleChanged -> mutableBlocked.value = event.people
                    else -> Unit
                }
            }
        }
        scope.launch {
            for (read in conversationReads) {
                if (!session.state.value.showsServer) continue
                val list = session.call("private conversations") { it.privateConversations() }
                // A read that was still on its way when the connection ended shows nothing.
                if (list is Outcome.Ok && session.state.value.showsServer) mutableConversations.value = list.value
            }
        }
        // The counts that were there before this connection was looked at (history of an
        // earlier run); later changes arrive as events.
        scope.launch {
            session.state.map { it.showsServer }.distinctUntilChanged().collect { shown ->
                if (!shown) {
                    mutableUnread.value = emptyMap()
                    mutableConversations.value = emptyList()
                    mutableRequests.value = NO_REQUESTS
                    mutableBlocked.value = emptyList()
                    return@collect
                }
                refreshConversations()
                // Requests held before this connection was looked at; later ones arrive as events.
                val held = session.call("message requests") { it.messageRequests() }
                if (held is Outcome.Ok) mutableRequests.update { if (it == NO_REQUESTS) held.value else it }
                val blockedNow = session.call("blocked people") { it.blockedPeople() }
                if (blockedNow is Outcome.Ok) mutableBlocked.update { it.ifEmpty { blockedNow.value } }
                val snapshot = session.call("chat unread") { it.snapshot().chatUnread }
                if (snapshot is Outcome.Ok) {
                    mutableUnread.update { current ->
                        // An event that arrived in between is newer than the snapshot.
                        snapshot.value.associate { it.chat to it.count.toInt() }.filterValues { it > 0 } + current
                    }
                }
            }
        }
    }

    suspend fun page(chat: ChatId, before: String?): Outcome<ChatPage> =
        session.call("chat page") { it.chatPage(chat, before, PAGE_SIZE.toUInt()) }

    suspend fun send(chat: ChatId, text: String, replyTo: ReplyQuote?): Outcome<ChatMessage> =
        session.call("send message") { it.sendMessage(chat, text, replyTo) }.sentTo(chat)

    suspend fun sendImage(chat: ChatId, bytes: ByteArray, mime: String): Outcome<ChatMessage> =
        session.call("send image") { it.sendImage(chat, bytes, mime) }.sentTo(chat)

    suspend fun sendGif(chat: ChatId, url: String): Outcome<ChatMessage> =
        session.call("send gif") { it.sendGifFavourite(chat, url) }.sentTo(chat)

    /** A private message that got out is the conversation's newest one. */
    private fun Outcome<ChatMessage>.sentTo(chat: ChatId): Outcome<ChatMessage> = also {
        if (it is Outcome.Ok && chat !is ChatId.Channel) refreshConversations()
    }

    /** The bytes of an image that was sent inside a message. */
    suspend fun image(chat: ChatId, messageId: String, imageId: String): ImageData? =
        session.call("chat image") { it.chatImage(chat, messageId, imageId) }.valueOrNull()

    /** An image or GIF from the web, or null when it cannot or may not be loaded. */
    suspend fun remoteImage(url: String): ImageData? =
        session.call("remote image") { it.loadRemoteImage(url, mediaPolicy.loadMedia()) }.valueOrNull()

    suspend fun mentionCandidates(query: String): List<MentionCandidate> =
        session.call("mention candidates") { it.mentionCandidates(query, MENTION_LIMIT.toUInt()) }.valueOrNull().orEmpty()

    suspend fun limits(): ChatLimits? = session.call("chat limits") { it.chatLimits() }.valueOrNull()

    suspend fun markRead(chat: ChatId) {
        session.call("mark chat read") { it.markChatRead(chat) }
    }

    /**
     * [owner] (a screen that shows chats) has [chat] on screen in front of the user, or none.
     * The engine and [openChat] get the one chat that is on screen.
     */
    fun setOpenChat(owner: Any, chat: ChatId?) {
        val open = synchronized(openChats) {
            if (chat == null) openChats.remove(owner) else openChats[owner] = chat
            openChats.values.lastOrNull()
        }
        mutableOpenChat.value = open
        session.setOpenChat(open)
    }

    // ----- private conversations, requests, blocking -----

    /** Reads the list of private conversations again, soon. */
    fun refreshConversations() {
        conversationReads.trySend(Unit)
    }

    /** The private chat with a person on the server, for opening it from the people list. */
    suspend fun chatWith(session: UInt): Outcome<ChatId> =
        this.session.call("chat with") { it.chatWith(session) }

    /** Accepts a message request: its held messages become messages of the conversation. */
    suspend fun acceptRequest(id: ULong): Outcome<Unit> =
        session.call("accept message request") { it.acceptMessageRequest(id) }

    /** Declines a message request. The other person is never told. */
    suspend fun declineRequest(id: ULong): Outcome<Unit> =
        session.call("decline message request") { it.declineMessageRequest(id) }

    /** Asks another Mumcord user for a chat; fails with [ConnectionError.InvalidInput] when their app cannot take it. */
    suspend fun sendChatRequest(session: UInt, note: String?): Outcome<Unit> =
        this.session.call("send chat request") { it.sendChatRequest(session, note) }

    /** The blocked people as the engine has them right now. */
    suspend fun readBlocked(): Outcome<List<BlockedPerson>> =
        session.call("blocked people") { it.blockedPeople() }.also {
            if (it is Outcome.Ok) mutableBlocked.value = it.value
        }

    /** Blocks a person on the server. Nobody is told. */
    suspend fun block(session: UInt): Outcome<Unit> =
        this.session.call("block person") { it.blockPerson(session) }

    /** Lifts a block by the id of its list entry. */
    suspend fun unblock(id: Long): Outcome<Unit> =
        session.call("unblock person") { it.unblockPerson(id) }

    /** Who may send the user private messages; the settings screen reads and saves it here. */
    suspend fun privateMessageSettings(): Outcome<PrivateMessageSettings> =
        session.call("private message settings") { it.privateMessageSettings() }

    suspend fun setPrivateMessageSettings(settings: PrivateMessageSettings): Outcome<Unit> =
        session.call("set private message settings") { it.setPrivateMessageSettings(settings) }

    suspend fun gifFavourites(): List<GifFavourite> =
        session.call("gif favourites") { it.gifFavourites() }.valueOrNull().orEmpty()

    suspend fun addGifFavourite(url: String): Outcome<Unit> =
        session.call("add gif favourite") { it.addGifFavourite(url) }

    suspend fun removeGifFavourite(url: String): Outcome<Unit> =
        session.call("remove gif favourite") { it.removeGifFavourite(url) }

    private fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value

    companion object {
        const val PAGE_SIZE = 50
        const val MENTION_LIMIT = 8
        private const val INCOMING_BUFFER = 256
        private val NO_REQUESTS = MessageRequests(incoming = emptyList(), sent = emptyList())
    }
}

/** The unread counts with [chat] set to [count]; a chat without unread messages is left out. */
fun Map<ChatId, Int>.withUnread(chat: ChatId, count: Int): Map<ChatId, Int> =
    if (count > 0) this + (chat to count) else this - chat
