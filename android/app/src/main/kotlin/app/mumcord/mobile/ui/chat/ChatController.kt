// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.chat

import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.SafeLink
import app.mumcord.mobile.session.ChatRepository
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user does in one chat: reading back, writing, replying, sending pictures and GIFs.
 * It follows [openChat] (the chat to show, or null) and [shown] (its screen is the one the user
 * looks at), so the same controller serves the channel chat and a private conversation.
 */
class ChatController(
    private val chats: ChatRepository,
    openChat: Flow<ChatId?>,
    shown: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val mutableLocal = MutableStateFlow(ChatLocal())
    val local: StateFlow<ChatLocal> = mutableLocal.asStateFlow()

    /** The app is in front; a chat behind the lock screen is not being read. */
    private val resumed = MutableStateFlow(false)
    private var pageJob: Job? = null
    private var mentionJob: Job? = null
    private var nextPendingId = 1L

    init {
        scope.launch { openChat.distinctUntilChanged().collect(::open) }
        scope.launch {
            chats.incoming.collect { message ->
                mutableLocal.update { it.received(message) }
                markSeen()
            }
        }
        scope.launch {
            combine(shown, resumed) { tab, front -> tab && front }.distinctUntilChanged().collect { visible ->
                mutableLocal.update { it.copy(visible = visible) }
                syncOpenChat()
                markSeen()
            }
        }
        scope.launch { chats.unread.collect { markSeen() } }
    }

    private fun open(chat: ChatId?) {
        pageJob?.cancel()
        mentionJob?.cancel()
        // What was typed is kept: joining another channel must not throw a text away.
        mutableLocal.update {
            ChatLocal(
                chat = chat,
                loading = chat != null,
                draft = it.draft,
                cursor = it.cursor,
                draftRevision = it.draftRevision,
                visible = it.visible,
            )
        }
        syncOpenChat()
        if (chat == null) return
        pageJob = scope.launch {
            val page = chats.page(chat, before = null)
            mutableLocal.update {
                when {
                    it.chat != chat -> it
                    page is Outcome.Ok -> it.loaded(page.value)
                    else -> it.copy(loading = false, loadFailed = true)
                }
            }
            val limits = chats.limits()
            mutableLocal.update { if (it.chat == chat) it.copy(limits = limits) else it }
            markSeen()
        }
    }

    /** Reads the chat again after the first page could not be read. */
    fun reload() {
        val chat = mutableLocal.value.chat ?: return
        if (mutableLocal.value.loading) return
        open(chat)
    }

    /** Reads the next older page; called when the user gets close to the oldest message shown. */
    fun loadOlder() {
        val state = mutableLocal.value
        val chat = state.chat ?: return
        val oldest = state.messages.firstOrNull() ?: return
        if (!state.hasMore || state.loading || state.loadingOlder) return
        mutableLocal.update { it.copy(loadingOlder = true) }
        pageJob = scope.launch {
            val page = chats.page(chat, before = oldest.id)
            mutableLocal.update {
                when {
                    it.chat != chat -> it
                    page is Outcome.Ok -> it.prepended(page.value)
                    // Scrolling up again asks once more.
                    else -> it.copy(loadingOlder = false)
                }
            }
        }
    }

    // ----- seeing -----

    fun setResumed(front: Boolean) {
        resumed.value = front
    }

    /** The list reports whether the newest message is on screen. */
    fun setAtBottom(atBottom: Boolean) {
        if (mutableLocal.value.atBottom == atBottom) return
        mutableLocal.update { it.scrolled(atBottom) }
        markSeen()
    }

    /** The "New messages" pill: jump to the newest message. */
    fun scrollToNewest() = mutableLocal.update { it.copy(scrollToken = it.scrollToken + 1, newBelow = 0) }

    private fun syncOpenChat() {
        val state = mutableLocal.value
        chats.setOpenChat(state.chat.takeIf { state.visible })
    }

    /** Messages count as read once the newest one was on screen in front of the user. */
    private fun markSeen() {
        val state = mutableLocal.value
        val chat = state.chat ?: return
        if (!state.visible || !state.atBottom || state.loading) return
        if ((chats.unread.value[chat] ?: 0) == 0) return
        scope.launch { chats.markRead(chat) }
    }

    // ----- writing -----

    fun changeDraft(text: String, cursor: Int) {
        mutableLocal.update { it.copy(draft = text, cursor = cursor) }
        val query = mentionQuery(text, cursor)
        mentionJob?.cancel()
        if (query == null) {
            mutableLocal.update { it.copy(mentions = emptyList()) }
            return
        }
        mentionJob = scope.launch {
            val candidates = chats.mentionCandidates(query)
            mutableLocal.update { if (it.draft == text && it.cursor == cursor) it.copy(mentions = candidates) else it }
        }
    }

    fun pickMention(candidate: MentionCandidate) {
        mentionJob?.cancel()
        mutableLocal.update {
            val (text, cursor) = insertMention(it.draft, it.cursor, candidate.name)
            it.copy(draft = text, cursor = cursor, draftRevision = it.draftRevision + 1, mentions = emptyList())
        }
    }

    fun startReply(message: ChatMessage) =
        mutableLocal.update { it.copy(reply = ReplyTarget(message.id, message.quote)) }

    fun cancelReply() = mutableLocal.update { it.copy(reply = null) }

    /** Sends what was typed. The text leaves the field at once and stays visible until it is out. */
    fun send() {
        val state = mutableLocal.value
        if (state.chat == null || !canSend(state.draft, state.limits)) return
        mentionJob?.cancel()
        mutableLocal.update {
            it.copy(draft = "", cursor = 0, draftRevision = it.draftRevision + 1, reply = null, mentions = emptyList())
        }
        enqueue(PendingContent.Text(state.draft.trim(), state.reply?.quote))
    }

    /** A picture from the photo library or the camera. */
    fun sendImage(bytes: ByteArray, mime: String) {
        mutableLocal.update { it.copy(sheet = null) }
        enqueue(PendingContent.Image(bytes, mime))
    }

    /** The picture the user chose could not be read from the phone. */
    fun imageUnreadable() = mutableLocal.update { it.copy(sheet = null, notice = ChatNotice.ImageUnreadable) }

    fun sendGif(url: String) {
        mutableLocal.update { it.copy(sheet = null) }
        enqueue(PendingContent.Gif(url))
    }

    private fun enqueue(content: PendingContent) {
        val send = PendingSend(nextPendingId++, content)
        mutableLocal.update { it.sending(send) }
        deliver(send)
    }

    /** Tries a message that did not get out once more. */
    fun retry(pendingId: Long) {
        val send = mutableLocal.value.pending.firstOrNull { it.id == pendingId && it.failure != null } ?: return
        mutableLocal.update { state ->
            state.copy(pending = state.pending.map { if (it.id == pendingId) it.copy(failure = null) else it })
        }
        deliver(send)
    }

    /** Gives up on a message that did not get out. */
    fun discard(pendingId: Long) =
        mutableLocal.update { state -> state.copy(pending = state.pending.filterNot { it.id == pendingId && it.failure != null }) }

    private fun deliver(send: PendingSend) {
        val chat = mutableLocal.value.chat ?: return
        scope.launch {
            val result = when (val content = send.content) {
                is PendingContent.Text -> chats.send(chat, content.text, content.reply)
                is PendingContent.Image -> chats.sendImage(chat, content.bytes, content.mime)
                is PendingContent.Gif -> chats.sendGif(chat, content.url)
            }
            mutableLocal.update {
                when (result) {
                    is Outcome.Ok -> if (it.chat == chat) it.sent(send.id, result.value) else it
                    is Outcome.Failed -> it.sendFailed(send.id, result.error.toSendFailure())
                }
            }
        }
    }

    private fun ConnectionError.toSendFailure(): SendFailure = when (this) {
        ConnectionError.InvalidInput, ConnectionError.PermissionDenied -> SendFailure.Refused
        else -> SendFailure.NotSent
    }

    // ----- sheets, links, pictures -----

    fun openImageSources() = mutableLocal.update { it.copy(sheet = ChatSheetKind.ImageSource) }

    fun openGifs() {
        mutableLocal.update { it.copy(sheet = ChatSheetKind.Gifs) }
        refreshGifs()
    }

    private fun refreshGifs() {
        scope.launch {
            val favourites = chats.gifFavourites()
            mutableLocal.update { it.copy(gifs = favourites) }
        }
    }

    fun removeGif(url: String) {
        mutableLocal.update { state -> state.copy(gifs = state.gifs.filterNot { it.url == url }) }
        scope.launch {
            chats.removeGifFavourite(url)
            refreshGifs()
        }
    }

    /** Keeps a GIF of the chat as a favourite and says whether that worked. */
    fun addGif(url: String) {
        scope.launch {
            val added = chats.addGifFavourite(url)
            mutableLocal.update {
                it.copy(notice = if (added is Outcome.Ok) ChatNotice.GifSaved else ChatNotice.GifNotSaved)
            }
        }
    }

    fun dismissNotice() = mutableLocal.update { it.copy(notice = null) }

    /**
     * A link was tapped. It goes to the system browser; one whose text names another address
     * first shows where it really leads.
     */
    fun tapLink(link: SafeLink) = mutableLocal.update {
        if (link.misleading) it.copy(linkWarning = link) else it.copy(urlToOpen = link.url)
    }

    /** The user read the warning and wants to open the link anyway. */
    fun confirmLink() = mutableLocal.update { it.copy(urlToOpen = it.linkWarning?.url, linkWarning = null) }

    /** The address was handed to the system. */
    fun urlOpened() = mutableLocal.update { it.copy(urlToOpen = null) }

    fun openViewer(source: ChatImageSource) = mutableLocal.update { it.copy(viewer = source) }

    fun closeViewer() = mutableLocal.update { it.copy(viewer = null) }

    /** Closes whichever sheet is open. */
    fun dismissSheet() = mutableLocal.update { it.copy(sheet = null, linkWarning = null) }
}
