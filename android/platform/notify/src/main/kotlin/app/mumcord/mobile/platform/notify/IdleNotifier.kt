// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** The two notifications of "disconnect when alone" (FR-5.42). */
interface IdleNotifier {
    /** "Disconnecting in 1 minute", with the action "Stay connected". */
    fun showWarning()

    fun hideWarning()

    /** Says that the app disconnected because the user was alone. */
    fun showDisconnected()

    fun hideDisconnected()
}

private const val CHANNEL_ID = "session_notices"

// 1 is the session's call notification, 2 the message notifications.
private const val WARNING_NOTIFICATION_ID = 3
private const val DISCONNECTED_NOTIFICATION_ID = 4
private const val ACTION_STAY = "app.mumcord.mobile.platform.notify.STAY_CONNECTED"

/**
 * The notifications on Android, in a channel of their own with high importance: the warning
 * leaves one minute to react, so it has to be noticed.
 *
 * @param open what a tap on either notification does: it opens the app.
 * @param onStay the "Stay connected" button; it works with the app in the background.
 */
class AndroidIdleNotifier(
    context: Context,
    private val open: PendingIntent,
    internal val onStay: () -> Unit,
) : IdleNotifier {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    init {
        active = this
        val channel = NotificationChannel(
            CHANNEL_ID,
            this.context.getString(R.string.idle_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = this.context.getString(R.string.idle_channel_description)
        manager.createNotificationChannel(channel)
    }

    override fun showWarning() {
        val stay = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, IdleActionReceiver::class.java).setAction(ACTION_STAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = builder(R.string.idle_warning_title, R.string.idle_warning_text)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_message),
                    context.getString(R.string.idle_stay_connected),
                    stay,
                ).build(),
            )
            .build()
        // Without the permission the system drops it; the app still disconnects as announced
        // in the settings.
        manager.notify(WARNING_NOTIFICATION_ID, notification)
    }

    override fun hideWarning() = manager.cancel(WARNING_NOTIFICATION_ID)

    override fun showDisconnected() {
        val notification = builder(R.string.idle_disconnected_title, R.string.idle_disconnected_text)
            .setAutoCancel(true)
            .build()
        manager.notify(DISCONNECTED_NOTIFICATION_ID, notification)
    }

    override fun hideDisconnected() = manager.cancel(DISCONNECTED_NOTIFICATION_ID)

    private fun builder(title: Int, text: Int): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(true)

    internal companion object {
        /**
         * The one notifier of the process, for the warning's button. It only holds the
         * application's context, which lives as long as the process anyway.
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        var active: AndroidIdleNotifier? = null
            private set
    }
}

/** Receives "Stay connected". Without a session (a stale notification) nothing happens. */
internal class IdleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STAY) AndroidIdleNotifier.active?.onStay?.invoke()
    }
}
