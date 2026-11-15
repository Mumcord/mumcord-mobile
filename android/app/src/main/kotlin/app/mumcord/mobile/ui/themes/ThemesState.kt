// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.ThemeCodeRead

/** The sheets of the themes screen. */
sealed interface ThemesSheet {
    /**
     * "Paste theme code": the pasted text (untrusted), what the engine read in it, and whether it
     * is being applied.
     */
    data class Paste(val text: String = "", val read: ThemeCodeRead? = null, val applying: Boolean = false) : ThemesSheet

    /** What can be done with one theme: share it, and delete it when it is the user's own. */
    data class Actions(val themeId: String) : ThemesSheet

    data class ConfirmDelete(val themeId: String) : ThemesSheet
}

data class ThemesLocal(
    val sheet: ThemesSheet? = null,
    /** A share message waiting for the system's share sheet; handed over once. */
    val shareText: String? = null,
)

/** The themes screen (mockup `19-themes`, FR-5.81). */
data class ThemesUiState(
    val mode: ThemeMode,
    val appThemeId: String,
    val builtIn: List<Theme>,
    val custom: List<Theme>,
    /** The shown server's own theme, which covers the app theme while connected to it. */
    val serverTheme: Theme?,
    val serverName: String,
    val sheet: ThemesSheet?,
) {
    fun find(themeId: String): Theme? = (builtIn + custom).firstOrNull { it.id == themeId }
}

/** The themes screen as a value; null until the themes have been read. */
fun themesUiState(session: SessionState, local: ThemesLocal, serverName: String): ThemesUiState? {
    val settings = session.themes ?: return null
    val all = session.builtInThemes + settings.customThemes
    val serverThemeId = session.activeServerId?.takeIf { session.showsServer }?.let { settings.serverThemes[it] }
    return ThemesUiState(
        mode = settings.mode,
        appThemeId = settings.appThemeId,
        builtIn = session.builtInThemes,
        custom = settings.customThemes,
        serverTheme = serverThemeId?.let { id -> all.firstOrNull { it.id == id } },
        serverName = serverName,
        sheet = local.sheet,
    )
}

/** The name of the app theme, for the Themes row of the More tab; null while not known. */
fun appThemeName(session: SessionState): String? {
    val settings = session.themes ?: return null
    return (session.builtInThemes + settings.customThemes).firstOrNull { it.id == settings.appThemeId }?.name
}
