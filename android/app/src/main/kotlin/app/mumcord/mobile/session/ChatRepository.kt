// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ChatPage
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.ImageData
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.ReplyQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
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
 * channel list. Channel chats and private conversations both go through it.
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

    init {
        scope.launch {
            for (event in session.chatEvents) {
                when (event) {
                    is BridgeEvent.ChatMessageReceived -> mutableIncoming.emit(event.message)
                    is BridgeEvent.ChatUnreadChanged -> mutableUnread.update { it.withUnread(event.chat, event.count.toInt()) }
                    else -> Unit
                }
            }
        }
        // The counts that were there before this connection was looked at (history of an
        // earlier run); later changes arrive as events.
        scope.launch {
            session.state.map { it.showsServer }.distinctUntilChanged().collect { shown ->
                if (!shown) {
                    mutableUnread.value = emptyMap()
                    return@collect
                }
                val snapshot = session.chatCall("chat unread") { it.snapshot().chatUnread }
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
        session.chatCall("chat page") { it.chatPage(chat, before, PAGE_SIZE.toUInt()) }

    suspend fun send(chat: ChatId, text: String, replyTo: ReplyQuote?): Outcome<ChatMessage> =
        session.chatCall("send message") { it.sendMessage(chat, text, replyTo) }

    suspend fun sendImage(chat: ChatId, bytes: ByteArray, mime: String): Outcome<ChatMessage> =
        session.chatCall("send image") { it.sendImage(chat, bytes, mime) }

    suspend fun sendGif(chat: ChatId, url: String): Outcome<ChatMessage> =
        session.chatCall("send gif") { it.sendGifFavourite(chat, url) }

    /** The bytes of an image that was sent inside a message. */
    suspend fun image(chat: ChatId, messageId: String, imageId: String): ImageData? =
        session.chatCall("chat image") { it.chatImage(chat, messageId, imageId) }.valueOrNull()

    /** An image or GIF from the web, or null when it cannot or may not be loaded. */
    suspend fun remoteImage(url: String): ImageData? =
        session.chatCall("remote image") { it.loadRemoteImage(url, mediaPolicy.loadMedia()) }.valueOrNull()

    suspend fun mentionCandidates(query: String): List<MentionCandidate> =
        session.chatCall("mention candidates") { it.mentionCandidates(query, MENTION_LIMIT.toUInt()) }.valueOrNull().orEmpty()

    suspend fun limits(): ChatLimits? = session.chatCall("chat limits") { it.chatLimits() }.valueOrNull()

    suspend fun markRead(chat: ChatId) {
        session.chatCall("mark chat read") { it.markChatRead(chat) }
    }

    /** Tells the engine which chat is on screen, or that none is. */
    fun setOpenChat(chat: ChatId?) = session.setOpenChat(chat)

    suspend fun gifFavourites(): List<GifFavourite> =
        session.chatCall("gif favourites") { it.gifFavourites() }.valueOrNull().orEmpty()

    suspend fun addGifFavourite(url: String): Outcome<Unit> =
        session.chatCall("add gif favourite") { it.addGifFavourite(url) }

    suspend fun removeGifFavourite(url: String): Outcome<Unit> =
        session.chatCall("remove gif favourite") { it.removeGifFavourite(url) }

    private fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value

    companion object {
        const val PAGE_SIZE = 50
        const val MENTION_LIMIT = 8
        private const val INCOMING_BUFFER = 256
    }
}

/** The unread counts with [chat] set to [count]; a chat without unread messages is left out. */
fun Map<ChatId, Int>.withUnread(chat: ChatId, count: Int): Map<ChatId, Int> =
    if (count > 0) this + (chat to count) else this - chat
