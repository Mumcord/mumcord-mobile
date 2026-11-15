// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/** The server of a running voice session, written down so that a later start knows of it. */
data class SessionMarker(val serverId: Long, val serverName: String)

/** A session that ended because the app was closed while it ran (FR-5.37). */
data class SessionEndedNotice(val serverId: Long, val serverName: String)

/**
 * Whether the last start of the app ended in the middle of a session without the user having
 * asked for it. [marker] is what the last run left behind; [exitReason] is the system's reason
 * why that run ended (`ApplicationExitInfo.getReason`), or null when the system does not say.
 * A run the user ended themselves (force stop, removed from the recent apps) gets no notice.
 */
fun sessionEndedNotice(marker: SessionMarker?, exitReason: Int?): SessionEndedNotice? = when {
    marker == null -> null
    exitReason == ApplicationExitInfo.REASON_USER_REQUESTED ||
        exitReason == ApplicationExitInfo.REASON_USER_STOPPED -> null
    else -> SessionEndedNotice(marker.serverId, marker.serverName)
}

/**
 * Remembers on disk that a session runs. The mark is removed when the session ends in an
 * orderly way; a mark found at the start of the app is therefore a session that was cut off.
 */
class SessionMarkerStore(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mark(marker: SessionMarker) = preferences.edit()
        .putLong(KEY_SERVER_ID, marker.serverId)
        .putString(KEY_SERVER_NAME, marker.serverName)
        .apply()

    fun clear() = preferences.edit().clear().apply()

    fun read(): SessionMarker? {
        if (!preferences.contains(KEY_SERVER_ID)) return null
        return SessionMarker(
            serverId = preferences.getLong(KEY_SERVER_ID, 0),
            serverName = preferences.getString(KEY_SERVER_NAME, null).orEmpty(),
        )
    }

    /** Why the previous run of the app ended, or null when the system has no record of it. */
    fun lastExitReason(): Int? =
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 1)
            .firstOrNull()
            ?.reason

    private companion object {
        const val FILE = "voice_session"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_SERVER_NAME = "server_name"
    }
}
