// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.messages

import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.session.ChatRepository
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.MessageNotificationSettings
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user does with private messages: the conversation list, message requests, starting
 * a conversation, chat requests, blocking, and the "Show sender only" switch. The messages of
 * the open conversation are the business of a `ChatController` that follows [local].
 */
class MessagesController(
    private val repository: SessionRepository,
    private val connected: ConnectedController,
    private val settings: MessageNotificationSettings,
    private val scope: CoroutineScope,
) {
    private val chats: ChatRepository = repository.chat
    private val mutableLocal = MutableStateFlow(MessagesLocal(senderOnly = settings.senderOnly.value))
    val local: StateFlow<MessagesLocal> = mutableLocal.asStateFlow()

    init {
        scope.launch {
            repository.state.map { it.showsServer }.distinctUntilChanged().collect { shown ->
                if (!shown) mutableLocal.update { MessagesLocal(senderOnly = it.senderOnly) }
            }
        }
        // Who is online is part of the conversation list, which has no event of its own.
        scope.launch {
            repository.state.map { it.server?.people?.keys }.distinctUntilChanged().drop(1).collect {
                chats.refreshConversations()
            }
        }
        // The last request was answered: there is nothing left to look at on that page.
        scope.launch {
            chats.requests.map { it.incoming.isEmpty() }.distinctUntilChanged().collect { none ->
                if (none) mutableLocal.update { if (it.page == MessagesPage.Requests) it.copy(page = MessagesPage.List) else it }
            }
        }
        // The setting is read from the engine, which can take longer than drawing the switch.
        scope.launch {
            settings.senderOnly.collect { senderOnly -> mutableLocal.update { it.copy(senderOnly = senderOnly) } }
        }
    }

    // ----- the list and its pages -----

    fun openRequests() = mutableLocal.update { it.copy(page = MessagesPage.Requests) }

    fun openPicker() = mutableLocal.update { it.copy(page = MessagesPage.Picker, pickerQuery = "") }

    fun changePickerQuery(query: String) = mutableLocal.update { it.copy(pickerQuery = query) }

    fun openSearch() = mutableLocal.update { it.copy(searching = true) }

    fun closeSearch() = mutableLocal.update { it.copy(searching = false, query = "") }

    fun changeQuery(query: String) = mutableLocal.update { it.copy(query = query) }

    /** One step back: out of the conversation, a page, the search or the blocked list. */
    fun back() = mutableLocal.update {
        when {
            it.open != null -> it.copy(open = null)
            it.page != MessagesPage.List -> it.copy(page = MessagesPage.List, pickerQuery = "")
            it.searching -> it.copy(searching = false, query = "")
            else -> it.copy(blockedOpen = false)
        }
    }

    // ----- conversations -----

    /** Opens a conversation of the list, or the one a notification is about. */
    fun openConversation(chat: ChatId, name: String = "", session: UInt? = null) {
        if (chat is ChatId.Channel) return
        mutableLocal.update {
            it.copy(open = OpenConversation(chat, name, session), page = MessagesPage.List, pickerQuery = "")
        }
    }

    /** "Send private message": shows the Messages tab with the conversation with that person. */
    fun messagePerson(session: UInt) {
        val name = repository.state.value.server?.people?.get(session)?.name ?: return
        scope.launch {
            val chat = chats.chatWith(session)
            if (chat is Outcome.Ok) {
                connected.showTab(ConnectedTab.Messages)
                openConversation(chat.value, name, session)
            } else {
                // The person left in this very moment; the lists show that already.
                repository.reportFailure()
            }
        }
    }

    // ----- message requests -----

    fun acceptRequest(id: ULong) {
        scope.launch { report(chats.acceptRequest(id)) }
    }

    /** Declines a request. The other person is never told. */
    fun declineRequest(id: ULong) {
        scope.launch { report(chats.declineRequest(id)) }
    }

    // ----- chat requests -----

    /** Opens the sheet for a chat request to a person; nothing is sent before "Send". */
    fun askChatRequest(session: UInt) {
        connected.dismissSheet()
        mutableLocal.update {
            it.copy(requestSession = session, requestNote = "", requestSending = false, requestError = null)
        }
    }

    fun changeRequestNote(note: String) =
        mutableLocal.update { it.copy(requestNote = note.take(CHAT_REQUEST_NOTE_MAX)) }

    fun sendChatRequest() {
        val state = mutableLocal.value
        val session = state.requestSession ?: return
        if (state.requestSending) return
        mutableLocal.update { it.copy(requestSending = true, requestError = null) }
        scope.launch {
            val sent = chats.sendChatRequest(session, state.requestNote.trim().ifEmpty { null })
            mutableLocal.update {
                when {
                    it.requestSession != session -> it
                    sent is Outcome.Ok -> it.copy(requestSession = null, requestNote = "", requestSending = false)
                    else -> it.copy(
                        requestSending = false,
                        requestError = if ((sent as Outcome.Failed).error == ConnectionError.InvalidInput) {
                            ChatRequestError.Refused
                        } else {
                            ChatRequestError.Failed
                        },
                    )
                }
            }
        }
    }

    // ----- blocking -----

    /** Asks before blocking: the sheet says what a block does. */
    fun askBlock(session: UInt) {
        connected.dismissSheet()
        mutableLocal.update { it.copy(blockSession = session, blockFailed = false) }
    }

    fun confirmBlock() {
        val session = mutableLocal.value.blockSession ?: return
        scope.launch {
            if (chats.block(session) is Outcome.Failed) {
                mutableLocal.update { if (it.blockSession == session) it.copy(blockFailed = true) else it }
                return@launch
            }
            chats.readBlocked()
            mutableLocal.update { if (it.blockSession == session) it.copy(blockSession = null) else it }
            // Their conversation is no longer one to show unread messages of.
            chats.refreshConversations()
        }
    }

    /**
     * Lifts every block that covers a person on the server. The list is read first: which
     * person an entry covers changes as people come and go.
     */
    fun unblockPerson(session: UInt) {
        scope.launch {
            val blocked = chats.readBlocked()
            if (blocked !is Outcome.Ok) return@launch repository.reportFailure()
            blocked.value.filter { it.session == session }.forEach { report(chats.unblock(it.id)) }
            chats.readBlocked()
        }
    }

    /** Lifts a block of the blocked list. */
    fun unblock(id: Long) {
        scope.launch {
            report(chats.unblock(id))
            chats.readBlocked()
        }
    }

    fun openBlocked() {
        mutableLocal.update { it.copy(blockedOpen = true) }
        scope.launch { chats.readBlocked() }
    }

    /** Closes whichever sheet is open. */
    fun dismissSheet() = mutableLocal.update {
        it.copy(blockSession = null, blockFailed = false, requestSession = null, requestNote = "", requestError = null)
    }

    // ----- notifications -----

    fun setSenderOnly(senderOnly: Boolean) {
        settings.setSenderOnly(senderOnly)
    }

    private fun report(outcome: Outcome<Unit>) {
        if (outcome is Outcome.Failed) repository.reportFailure()
    }
}
