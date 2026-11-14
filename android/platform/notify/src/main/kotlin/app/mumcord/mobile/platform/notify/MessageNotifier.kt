// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context

/**
 * What one conversation's notification says.
 *
 * @param key names the conversation: a later notice with the same key replaces this one.
 * @param title the channel's name when [group] is set; a private conversation is named by the
 *   sender of its lines.
 * @param group several people write here (a channel); otherwise a conversation of two.
 * @param lines the messages, oldest first.
 */
data class MessageNotice(
    val key: String,
    val title: String,
    val group: Boolean,
    val lines: List<Line>,
) {
    /** One message. [text] is null when only the sender may be shown, or it has no words. */
    data class Line(val sender: String, val text: String?, val atMillis: Long)
}

/** Shows and removes the notifications for messages. */
interface MessageNotifier {
    fun show(notice: MessageNotice)

    /** Removes the notification of one conversation, if there is one. */
    fun cancel(key: String)

    /** Removes all message notifications, also those an earlier run of the app left behind. */
    fun cancelAll()
}

private const val CHANNEL_ID = "messages"

// The call notification of the voice session is 1; all message notifications share this id and
// differ by their tag, the conversation's key.
private const val MESSAGE_NOTIFICATION_ID = 2

/**
 * Message notifications on Android: their own channel "Messages" with the system's default
 * importance and sound, and one notification per conversation in the messaging style. Sender
 * and text are handed to the system as they are, with the default (private) visibility, so the
 * phone's lock-screen setting decides what is readable there.
 *
 * @param open what a tap on the notification of the conversation with that key does.
 */
class AndroidMessageNotifier(
    context: Context,
    private val open: (String) -> PendingIntent,
) : MessageNotifier {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            this.context.getString(R.string.message_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = this.context.getString(R.string.message_channel_description)
        manager.createNotificationChannel(channel)
    }

    override fun show(notice: MessageNotice) {
        val newest = notice.lines.lastOrNull() ?: return
        val style = Notification.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.message_you)).build(),
        )
        if (notice.group) {
            style.setGroupConversation(true)
            style.setConversationTitle(notice.title)
        }
        notice.lines.forEach { line ->
            style.addMessage(
                line.text ?: context.getString(R.string.message_new),
                line.atMillis,
                Person.Builder().setName(line.sender).build(),
            )
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setStyle(style)
            .setContentIntent(open(notice.key))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(newest.atMillis)
            .setShowWhen(true)
            .build()
        // Without the permission the system drops it; the app's own badges still count.
        manager.notify(notice.key, MESSAGE_NOTIFICATION_ID, notification)
    }

    override fun cancel(key: String) = manager.cancel(key, MESSAGE_NOTIFICATION_ID)

    override fun cancelAll() {
        manager.activeNotifications
            .filter { it.id == MESSAGE_NOTIFICATION_ID }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
