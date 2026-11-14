// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.content.pm.ServiceInfo

/**
 * The voice session as the phone system shows it. [serverName] and [channelName] come from
 * the server and are untrusted text; they are only ever shown as plain text.
 */
data class CallInfo(
    val serverName: String,
    /** The channel the user is in, or null while the server has not said. */
    val channelName: String?,
    val muted: Boolean,
    val deafened: Boolean,
) {
    /** The name of the call: "Server · Channel". */
    val title: String
        get() = if (channelName.isNullOrEmpty()) serverName else "$serverName · $channelName"
}

/** What the buttons of the session's notification do; they work with the app in the background. */
interface CallActions {
    fun toggleMute()

    fun toggleDeafen()

    /** Leaves the server, also when the phone system ends the call (a car's hang-up button). */
    fun leave()
}

/** What the foreground service has to do after the session changed from [previous] to [next]. */
enum class ServiceStep { Start, Update, Stop, None }

/**
 * The service runs exactly while a session exists. [running] is false also when starting it
 * was refused (the app was not on screen), so that a later change tries again.
 */
fun serviceStep(running: Boolean, previous: CallInfo?, next: CallInfo?): ServiceStep = when {
    next == null -> if (running || previous != null) ServiceStep.Stop else ServiceStep.None
    !running -> ServiceStep.Start
    next != previous -> ServiceStep.Update
    else -> ServiceStep.None
}

/**
 * The foreground service types of a session. The microphone type may only be used with the
 * microphone permission (the system refuses the service otherwise); a session without it
 * still plays what the others say.
 */
fun foregroundServiceTypes(microphoneGranted: Boolean): Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
        if (microphoneGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

/** The labels of the notification's two own buttons and its status line. */
data class CallNotificationModel(
    val title: String,
    val status: Status,
    val muteAction: MuteAction,
    val deafenAction: DeafenAction,
) {
    enum class Status { Connected, Muted, Deafened }

    enum class MuteAction { Mute, Unmute }

    enum class DeafenAction { Deafen, Undeafen }
}

/** Deafened includes muted, and the mute button then offers to unmute, as in the app. */
fun callNotificationModel(info: CallInfo): CallNotificationModel = CallNotificationModel(
    title = info.title,
    status = when {
        info.deafened -> CallNotificationModel.Status.Deafened
        info.muted -> CallNotificationModel.Status.Muted
        else -> CallNotificationModel.Status.Connected
    },
    muteAction = if (info.muted || info.deafened) {
        CallNotificationModel.MuteAction.Unmute
    } else {
        CallNotificationModel.MuteAction.Mute
    },
    deafenAction = if (info.deafened) {
        CallNotificationModel.DeafenAction.Undeafen
    } else {
        CallNotificationModel.DeafenAction.Deafen
    },
)
