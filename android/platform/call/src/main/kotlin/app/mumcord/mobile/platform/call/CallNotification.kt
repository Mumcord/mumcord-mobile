// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

internal const val CALL_NOTIFICATION_ID = 1

private const val CHANNEL_ID = "voice_session"
private const val ACTION_MUTE = "app.mumcord.mobile.platform.call.MUTE"
private const val ACTION_DEAFEN = "app.mumcord.mobile.platform.call.DEAFEN"
private const val ACTION_LEAVE = "app.mumcord.mobile.platform.call.LEAVE"

/** The channel of the session's notification: silent, so that its updates never make a noise. */
internal fun createCallChannel(context: Context) {
    val channel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.call_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    )
    channel.description = context.getString(R.string.call_channel_description)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/**
 * The session as an ongoing call (FR-5.32): server and channel, the time it has been running,
 * the system's hang-up button as Leave, and Mute and Deafen as the two further buttons a call
 * notification may have. Tapping it opens [content].
 */
internal fun callNotification(
    context: Context,
    info: CallInfo,
    startedAtMillis: Long,
    content: PendingIntent,
): Notification {
    val model = callNotificationModel(info)
    val person = Person.Builder().setName(model.title).setImportant(true).build()
    val mute = when (model.muteAction) {
        CallNotificationModel.MuteAction.Mute -> R.string.call_action_mute
        CallNotificationModel.MuteAction.Unmute -> R.string.call_action_unmute
    }
    val deafen = when (model.deafenAction) {
        CallNotificationModel.DeafenAction.Deafen -> R.string.call_action_deafen
        CallNotificationModel.DeafenAction.Undeafen -> R.string.call_action_undeafen
    }
    val status = when (model.status) {
        CallNotificationModel.Status.Connected -> R.string.call_status_connected
        CallNotificationModel.Status.Muted -> R.string.call_status_muted
        CallNotificationModel.Status.Deafened -> R.string.call_status_deafened
    }
    return Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_call_session)
        .setStyle(Notification.CallStyle.forOngoingCall(person, action(context, ACTION_LEAVE)))
        .setContentText(context.getString(status))
        .addAction(button(context, R.drawable.ic_call_mute, mute, ACTION_MUTE))
        .addAction(button(context, R.drawable.ic_call_deafen, deafen, ACTION_DEAFEN))
        .setContentIntent(content)
        .setCategory(Notification.CATEGORY_CALL)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setWhen(startedAtMillis)
        .setShowWhen(true)
        .setUsesChronometer(true)
        // Shown at once; the system otherwise waits ten seconds with a service's notification.
        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

private fun button(context: Context, icon: Int, label: Int, action: String): Notification.Action =
    Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), action(context, action))
        .build()

private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, CallActionReceiver::class.java).setAction(action),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** Receives the notification's buttons. Without a session (a stale notification) nothing happens. */
internal class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = CallSession.active?.actions ?: return
        when (intent.action) {
            ACTION_MUTE -> actions.toggleMute()
            ACTION_DEAFEN -> actions.toggleDeafen()
            ACTION_LEAVE -> actions.leave()
        }
    }
}
