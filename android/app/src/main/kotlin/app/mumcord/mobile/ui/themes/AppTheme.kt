// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeSettings
import app.mumcord.mobile.bridge.ThemeTokens
import app.mumcord.mobile.bridge.ThemeVariant
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.DarkColors
import app.mumcord.mobile.design.tokens.LightColors
import app.mumcord.mobile.design.tokens.MumcordColors

/**
 * The colours the app is drawn in: both versions of the shown theme, read from the engine
 * whenever the themes or the shown server change, so that following the phone's dark mode needs
 * no engine call.
 */
data class AppTheme(val mode: ThemeMode, val dark: ThemeTokens, val light: ThemeTokens) {
    fun tokens(systemDark: Boolean): ThemeTokens =
        if (themeVariant(mode, systemDark) == ThemeVariant.DARK) dark else light
}

/**
 * The theme a screen is shown in (Spec 02 FR-2.17): the one the user picked for the shown
 * server, otherwise the app theme. A server's theme that no longer exists is left to the
 * engine, which then answers with the app theme.
 */
fun shownThemeId(settings: ThemeSettings, shownServerId: Long?): String =
    shownServerId?.let { settings.serverThemes[it] } ?: settings.appThemeId

/** Which version of a theme is drawn: System follows the phone's dark mode. */
fun themeVariant(mode: ThemeMode, systemDark: Boolean): ThemeVariant = when (mode) {
    ThemeMode.DARK -> ThemeVariant.DARK
    ThemeMode.LIGHT -> ThemeVariant.LIGHT
    ThemeMode.SYSTEM -> if (systemDark) ThemeVariant.DARK else ThemeVariant.LIGHT
}

/** Whether the colours of [this] are dark; it picks the opacities, shadows and system bars. */
val ThemeTokens.isDark: Boolean
    get() = base == ThemeVariant.DARK

/**
 * The design system's colours for these tokens. The engine gives the colours a theme decides;
 * the rest (avatar colours, the backdrop, media and live colours) comes from the app's own
 * dark or light set that matches the theme's brightness, as on the desktop.
 */
fun ThemeTokens.toColors(): MumcordColors = (if (isDark) DarkColors else LightColors).copy(
    bgServers = hexColor(bgServers),
    bgSidebar = hexColor(bgSidebar),
    bgChat = hexColor(bgChat),
    bgElevated = hexColor(bgElevated),
    bgHover = hexColor(bgHover),
    bgSelected = hexColor(bgSelected),
    text = hexColor(text),
    textSecondary = hexColor(textSecondary),
    textMuted = hexColor(textMuted),
    accent = hexColor(accent),
    onAccent = hexColor(onAccent),
    accentText = hexColor(accentText),
    success = hexColor(success),
    warning = hexColor(warning),
    danger = hexColor(danger),
    localMuted = hexColor(localMuted),
    talking = hexColor(talking),
    accentStop1 = hexColor(accentStop1),
    accentStop2 = hexColor(accentStop2),
    accentStop3 = hexColor(accentStop3),
    primaryBg = hexColor(primaryBg),
    primaryText = hexColor(primaryText),
    border = hexColor(border),
    borderStrong = hexColor(borderStrong),
    focus = hexColor(focus),
)

private const val OPAQUE = 0xFF000000

/** A `#rrggbb` colour as the engine writes it. Anything else is a fault of the engine. */
fun hexColor(value: String): Color {
    require(value.length == HEX_LENGTH && value[0] == '#') { "not a #rrggbb colour" }
    return Color(OPAQUE or value.substring(1).toLong(HEX_RADIX))
}

private const val HEX_LENGTH = 7
private const val HEX_RADIX = 16

/**
 * The app's theme around [content]: the shown theme in the version the appearance mode and the
 * phone's dark mode pick, or the app's own colours while the engine has not answered yet.
 */
@Composable
fun AppThemeHost(theme: AppTheme?, systemDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val tokens = theme?.tokens(systemDark)
    if (tokens == null) {
        MumcordTheme(darkTheme = systemDark, content = content)
        return
    }
    val colors = remember(tokens) { tokens.toColors() }
    MumcordTheme(darkTheme = tokens.isDark, colors = colors, content = content)
}
