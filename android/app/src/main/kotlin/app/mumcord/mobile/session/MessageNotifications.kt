// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import android.content.Context
import androidx.core.content.edit
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatNotification
import app.mumcord.mobile.bridge.NotificationKind
import app.mumcord.mobile.platform.notify.MessageNotice
import app.mumcord.mobile.platform.notify.MessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The settings of message notifications. The one place they are read and written.
 */
interface MessageNotificationSettings {
    /** "Show sender only": notifications name who wrote and leave the message out (FR-5.55). */
    val senderOnly: StateFlow<Boolean>

    fun setSenderOnly(senderOnly: Boolean)
}

/** The "Show sender only" choice an earlier version of the app kept on the phone itself. */
interface StoredSenderOnly {
    /** The stored choice, or null when there is none (any more). */
    fun read(): Boolean?

    fun clear()
}

/** The app's own preferences, where the choice lived before the engine had the setting. */
class PreferencesStoredSenderOnly(context: Context) : StoredSenderOnly {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): Boolean? =
        if (preferences.contains(KEY_SENDER_ONLY)) preferences.getBoolean(KEY_SENDER_ONLY, false) else null

    override fun clear() = preferences.edit { remove(KEY_SENDER_ONLY) }

    private companion object {
        const val FILE = "message_notifications"
        const val KEY_SENDER_ONLY = "sender_only"
    }
}

/**
 * Kept in the engine's phone settings (`notifySenderOnly`), next to all other settings. A
 * choice [stored] by an earlier version is moved there once and then forgotten on the phone.
 *
 * The engine reports changed settings as an event that the state does not follow yet (see
 * the reducer); this switch is the only thing that changes the setting, so the value read at
 * the start and the user's own changes are all there is to know.
 *
 * @param scope lives as long as the process.
 */
class EngineMessageNotificationSettings(
    private val repository: SessionRepository,
    private val stored: StoredSenderOnly,
    private val scope: CoroutineScope,
) : MessageNotificationSettings {
    private val mutableSenderOnly = MutableStateFlow(stored.read() ?: false)
    override val senderOnly: StateFlow<Boolean> = mutableSenderOnly.asStateFlow()

    // The user's choice is newer than what the engine is still being asked for.
    @Volatile
    private var chosen = false

    init {
        scope.launch {
            val earlier = stored.read()
            if (earlier != null) {
                save(earlier)
            } else {
                val settings = repository.mobileSettings() ?: return@launch
                if (!chosen) mutableSenderOnly.value = settings.notifySenderOnly
            }
        }
    }

    override fun setSenderOnly(senderOnly: Boolean) {
        chosen = true
        mutableSenderOnly.value = senderOnly
        scope.launch { save(senderOnly) }
    }

    /** A failure is logged by the repository; the stored choice then stays for the next start. */
    private suspend fun save(senderOnly: Boolean) {
        val saved = repository.updateMobileSettings { it.copy(notifySenderOnly = senderOnly) }
        if (saved is Outcome.Ok) stored.clear()
    }
}

/** The name of a chat's notification, which also tells a tap which chat to open. */
fun ChatId.notificationKey(): String = when (this) {
    is ChatId.Channel -> "$KEY_CHANNEL$channelId"
    is ChatId.Person -> "$KEY_PERSON$certHash"
    is ChatId.Session -> "$KEY_SESSION$session"
}

/** The chat a notification key names, or null for anything else. */
fun chatFromNotificationKey(key: String): ChatId? = when {
    key.startsWith(KEY_CHANNEL) -> key.removePrefix(KEY_CHANNEL).toUIntOrNull()?.let { ChatId.Channel(it) }
    key.startsWith(KEY_PERSON) -> key.removePrefix(KEY_PERSON).takeIf { it.isNotEmpty() }?.let { ChatId.Person(it) }
    key.startsWith(KEY_SESSION) -> key.removePrefix(KEY_SESSION).toUIntOrNull()?.let { ChatId.Session(it) }
    else -> null
}

private const val KEY_CHANNEL = "c:"
private const val KEY_PERSON = "p:"
private const val KEY_SESSION = "s:"

/**
 * System notifications for private messages and mentions, only while connected (FR-5.54):
 *
 * - The engine reports a message that is worth one; it leaves out own messages, blocked
 *   people, messages held back by the private message rule and the chat that is on screen. A
 *   chat that is not on screen gets its notification also while the app is in front.
 * - One notification per conversation, with its latest messages; with "Show sender only" the
 *   text is left out.
 * - A conversation's notification goes away when the chat is opened or read, and all of them
 *   when the connection ends. What an earlier run of the app left behind is removed at once.
 *
 * It follows the session by itself once it has been created. Do Not Disturb is the system's
 * business: it holds notifications back by itself, and the app's badges count either way.
 *
 * @param permitted whether the phone lets the app show notifications right now.
 * @param scope lives as long as the process.
 */
class MessageNotificationCenter(
    private val repository: SessionRepository,
    private val notifier: MessageNotifier,
    private val settings: MessageNotificationSettings,
    private val permitted: () -> Boolean,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val chats = repository.chat

    /** The lines of every notification that is shown, by its key. */
    private val shown = HashMap<String, List<MessageNotice.Line>>()
    private var lastUnread: Map<ChatId, Int> = emptyMap()

    init {
        scope.launch {
            repository.state.map { it.showsServer }.distinctUntilChanged().collect { connected ->
                if (!connected) clearAll()
            }
        }
        scope.launch { chats.notifications.collect(::notify) }
        scope.launch { chats.unread.collect(::onUnread) }
        scope.launch { chats.openChat.collect { chat -> if (chat != null) clear(chat) } }
    }

    @Synchronized
    private fun notify(notification: ChatNotification) {
        val session = repository.state.value
        // Nothing after the connection ended, even for a message that was still on its way.
        val server = session.server?.takeIf { session.showsServer } ?: return
        if (!permitted()) return
        val chat = notification.chat
        val key = chat.notificationKey()
        val line = MessageNotice.Line(
            sender = notification.senderName,
            text = notification.preview.takeIf { it.isNotEmpty() && !settings.senderOnly.value },
            atMillis = now(),
        )
        val lines = (shown[key].orEmpty() + line).takeLast(MAX_LINES)
        shown[key] = lines
        val channelName = (chat as? ChatId.Channel)?.let { server.channels[it.channelId]?.name.orEmpty() }
        notifier.show(
            MessageNotice(
                key = key,
                title = channelName ?: notification.senderName,
                group = notification.kind == NotificationKind.MENTION,
                lines = lines,
            ),
        )
    }

    /** A chat whose unread count went back to nothing was read, here or on the chat's screen. */
    @Synchronized
    private fun onUnread(unread: Map<ChatId, Int>) {
        lastUnread.keys.filterNot { it in unread }.forEach(::clear)
        lastUnread = unread
    }

    @Synchronized
    private fun clear(chat: ChatId) {
        val key = chat.notificationKey()
        if (shown.remove(key) != null) notifier.cancel(key)
    }

    @Synchronized
    private fun clearAll() {
        shown.clear()
        lastUnread = emptyMap()
        notifier.cancelAll()
    }

    private companion object {
        /** How many messages of a conversation its notification lists. */
        const val MAX_LINES = 6
    }
}

/** Kept for the life of the process only: for screens drawn without a phone behind them, and tests. */
class InMemoryMessageNotificationSettings(senderOnly: Boolean = false) : MessageNotificationSettings {
    private val mutableSenderOnly = MutableStateFlow(senderOnly)
    override val senderOnly: StateFlow<Boolean> = mutableSenderOnly.asStateFlow()

    override fun setSenderOnly(senderOnly: Boolean) {
        mutableSenderOnly.value = senderOnly
    }
}
