// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.session.SessionState

/** What the More tab shows: its list, or one of the screens it leads to. */
enum class MorePage { Main, Activity, Settings }

/** The choices of the settings that open a sheet. */
enum class SettingsSheet { TalkMode, DefaultOutput, HistoryAge }

/** The days after which chat history is deleted, as the desktop app offers them; null keeps it. */
val HISTORY_AGE_DAYS: List<UInt?> = listOf(null, 7u, 30u, 90u, 365u)

data class MoreLocal(
    val page: MorePage = MorePage.Main,
    val filter: ActivityFilter = ActivityFilter.All,
    /** The newest activity entry when the log was last looked at; null before that. */
    val seenActivityId: ULong? = null,
    val sheet: SettingsSheet? = null,
    /** Read from the engine each time the settings open; null until it answered. */
    val noiseSuppression: Boolean? = null,
)

/** The settings screen (mockup `16-settings`). */
data class SettingsUiState(
    val mobile: MobileSettings,
    val app: AppSettings,
    val talkMode: TalkMode,
    /** Null while it is being read: the row waits for it. */
    val noiseSuppression: Boolean?,
    /** "Earpiece" is only offered on a phone that has one. */
    val hasEarpiece: Boolean,
    val senderOnly: Boolean,
    val sheet: SettingsSheet?,
)

data class MoreUiState(
    val page: MorePage,
    val serverName: String,
    /** Entries by others since the log was last looked at. */
    val activityBadge: Int,
    /** The log of the connection, newest first. */
    val activity: List<ActivityEntry>,
    val filter: ActivityFilter,
    /** Null while the settings are closed or not read yet. */
    val settings: SettingsUiState?,
)

/** The More tab as a value, or null while no server is shown. */
fun moreUiState(
    session: SessionState,
    local: MoreLocal,
    serverName: String,
    output: OutputState,
    senderOnly: Boolean,
): MoreUiState? {
    if (!session.showsServer) return null
    val mobile = session.mobileSettings
    val app = session.appSettings
    val settings = if (local.page == MorePage.Settings && mobile != null && app != null) {
        SettingsUiState(
            mobile = mobile,
            app = app,
            talkMode = session.talkMode,
            noiseSuppression = local.noiseSuppression,
            hasEarpiece = output.available.any { it.kind == OutputKind.Earpiece },
            senderOnly = senderOnly,
            sheet = local.sheet,
        )
    } else {
        null
    }
    return MoreUiState(
        page = local.page,
        serverName = serverName,
        activityBadge = if (local.page == MorePage.Activity) 0 else unseenActivity(session.activity, local.seenActivityId),
        activity = session.activity,
        filter = local.filter,
        settings = settings,
    )
}
