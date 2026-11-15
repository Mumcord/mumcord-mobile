// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.ui.about.AboutLocal
import app.mumcord.mobile.ui.about.AboutUiState
import app.mumcord.mobile.ui.about.Licences
import app.mumcord.mobile.ui.about.aboutUiState
import app.mumcord.mobile.ui.crash.CrashLocal
import app.mumcord.mobile.ui.profile.OwnProfile
import app.mumcord.mobile.ui.profile.ProfileEdit
import app.mumcord.mobile.ui.profile.ProfileEditUiState
import app.mumcord.mobile.ui.profile.profileEditUiState
import app.mumcord.mobile.ui.themes.ThemesLocal
import app.mumcord.mobile.ui.themes.ThemesUiState
import app.mumcord.mobile.ui.themes.appThemeName
import app.mumcord.mobile.ui.themes.themesUiState

/** What the More tab shows: its list, or one of the screens it leads to. */
enum class MorePage { Main, Activity, Settings, EditProfile, Themes, About, Licences, CrashReports }

/** The choices of the settings that open a sheet. */
enum class SettingsSheet { TalkMode, DefaultOutput, HistoryAge, Language }

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
    /** Whether the language row is shown; on Android 12 the app follows the phone's language. */
    val languageChoice: Boolean = false,
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
    /** The user's own profile for the header; null until it was read. */
    val profile: OwnProfile? = null,
    /** Null while the editor is closed. */
    val profileEdit: ProfileEditUiState? = null,
    /** The name of the app theme for the Themes row; null until the themes were read. */
    val themeName: String? = null,
    /** Null while the themes screen is closed or the themes have not been read. */
    val themes: ThemesUiState? = null,
    /** The version on the "About Mumcord" row. */
    val appVersion: String = "",
    /** Null while the About screen is closed. */
    val about: AboutUiState? = null,
    /** Null while the licences screen is closed. */
    val licences: Licences? = null,
    /** Null while the crash reports are closed. */
    val crashReports: CrashLocal? = null,
)

/** What the profile and theme screens add to the More tab; kept apart from [MoreLocal]. */
data class MoreExtras(
    val profile: OwnProfile? = null,
    val profileEdit: ProfileEdit? = null,
    val themes: ThemesLocal = ThemesLocal(),
    /** Null where no About screen exists (screenshots of other screens). */
    val about: AboutLocal? = null,
    val crashes: CrashLocal = CrashLocal(),
)

/** The More tab as a value, or null while no server is shown. */
fun moreUiState(
    session: SessionState,
    local: MoreLocal,
    serverName: String,
    output: OutputState,
    senderOnly: Boolean,
    extras: MoreExtras = MoreExtras(),
    languageChoice: Boolean = false,
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
            languageChoice = languageChoice,
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
        profile = extras.profile,
        profileEdit = if (local.page == MorePage.EditProfile) {
            extras.profile?.let { own -> extras.profileEdit?.let { profileEditUiState(own, it) } }
        } else {
            null
        },
        themeName = appThemeName(session),
        themes = if (local.page == MorePage.Themes) themesUiState(session, extras.themes, serverName) else null,
        appVersion = extras.about?.info?.appVersion.orEmpty(),
        about = extras.about?.takeIf { local.page == MorePage.About }?.let(::aboutUiState),
        licences = extras.about?.takeIf { local.page == MorePage.Licences }?.licences,
        crashReports = extras.crashes.takeIf { local.page == MorePage.CrashReports },
    )
}
