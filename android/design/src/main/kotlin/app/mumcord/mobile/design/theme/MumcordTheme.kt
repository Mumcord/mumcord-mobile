// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import app.mumcord.mobile.design.tokens.DarkColors
import app.mumcord.mobile.design.tokens.DarkOpacities
import app.mumcord.mobile.design.tokens.DarkShadows
import app.mumcord.mobile.design.tokens.LightColors
import app.mumcord.mobile.design.tokens.LightOpacities
import app.mumcord.mobile.design.tokens.LightShadows
import app.mumcord.mobile.design.tokens.MumcordColors
import app.mumcord.mobile.design.tokens.MumcordOpacities
import app.mumcord.mobile.design.tokens.MumcordShadows
import app.mumcord.mobile.design.tokens.MumcordTypography

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalOpacities = staticCompositionLocalOf { DarkOpacities }
private val LocalShadows = staticCompositionLocalOf { DarkShadows }
private val LocalTypography = staticCompositionLocalOf { MumcordTypographyTokens }
private val LocalIsDark = staticCompositionLocalOf { true }

/** Access to the current theme values: `MumcordTheme.colors.text`, `MumcordTheme.typography.body`. */
object MumcordTheme {
    val colors: MumcordColors
        @Composable @ReadOnlyComposable get() = LocalColors.current

    val opacities: MumcordOpacities
        @Composable @ReadOnlyComposable get() = LocalOpacities.current

    val shadows: MumcordShadows
        @Composable @ReadOnlyComposable get() = LocalShadows.current

    val typography: MumcordTypography
        @Composable @ReadOnlyComposable get() = LocalTypography.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDark.current
}

/** The colours of one theme, also usable outside a composition (tests, system bars). */
fun mumcordColors(darkTheme: Boolean): MumcordColors = if (darkTheme) DarkColors else LightColors

/**
 * The Mumcord theme. It follows the system's dark or light setting unless [darkTheme] is given.
 * Material components used inside (dialogs, text fields of screens that are not rebuilt yet)
 * get a colour scheme and type scale made from the same tokens.
 */
@Composable
fun MumcordTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = mumcordColors(darkTheme)
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalOpacities provides if (darkTheme) DarkOpacities else LightOpacities,
        LocalShadows provides if (darkTheme) DarkShadows else LightShadows,
        LocalTypography provides MumcordTypographyTokens,
        LocalIsDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme(colors, darkTheme),
            typography = materialTypography(MumcordTypographyTokens),
            content = content,
        )
    }
}

private fun materialColorScheme(colors: MumcordColors, darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.bgSelected,
        onPrimaryContainer = colors.text,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.bgSelected,
        onSecondaryContainer = colors.text,
        tertiary = colors.accentText,
        onTertiary = colors.bgSidebar,
        background = colors.bgSidebar,
        onBackground = colors.text,
        surface = colors.bgSidebar,
        onSurface = colors.text,
        surfaceVariant = colors.bgElevated,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainerLowest = colors.bgServers,
        surfaceContainerLow = colors.bgSidebar,
        surfaceContainer = colors.bgElevated,
        surfaceContainerHigh = colors.bgElevated,
        surfaceContainerHighest = colors.bgHover,
        error = colors.danger,
        onError = colors.onAccent,
        outline = colors.borderStrong,
        outlineVariant = colors.border,
        scrim = colors.backdrop,
    )
}

private fun materialTypography(typography: MumcordTypography): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = typography.displayLarge,
        headlineLarge = typography.displayLarge,
        headlineMedium = typography.title,
        headlineSmall = typography.title,
        titleLarge = typography.title,
        titleMedium = typography.bodyStrong,
        titleSmall = typography.smallStrong,
        bodyLarge = typography.body,
        bodyMedium = typography.small,
        bodySmall = typography.caption,
        labelLarge = typography.smallStrong,
        labelMedium = typography.captionStrong,
        labelSmall = typography.tab,
    )
}
