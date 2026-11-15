// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import androidx.compose.ui.graphics.Color
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeSettings
import app.mumcord.mobile.bridge.ThemeTokens
import app.mumcord.mobile.bridge.ThemeVariant
import app.mumcord.mobile.design.tokens.DarkColors
import app.mumcord.mobile.design.tokens.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which theme and which version of it the app is drawn in, and how its colours reach the design system. */
class AppThemeTest {
    private val settings = ThemeSettings(
        mode = ThemeMode.SYSTEM,
        appThemeId = "preset:paper",
        customThemes = emptyList(),
        serverThemes = mapOf(1L to "custom:1"),
    )

    @Test
    fun withoutAShownServerTheAppThemeIsShown() {
        assertEquals("preset:paper", shownThemeId(settings, shownServerId = null))
    }

    @Test
    fun aServerWithItsOwnThemeIsShownInIt() {
        assertEquals("custom:1", shownThemeId(settings, shownServerId = 1L))
    }

    @Test
    fun aServerWithoutItsOwnThemeIsShownInTheAppTheme() {
        assertEquals("preset:paper", shownThemeId(settings, shownServerId = 2L))
    }

    @Test
    fun darkAndLightAreFixedAndSystemFollowsThePhone() {
        assertEquals(ThemeVariant.DARK, themeVariant(ThemeMode.DARK, systemDark = false))
        assertEquals(ThemeVariant.LIGHT, themeVariant(ThemeMode.LIGHT, systemDark = true))
        assertEquals(ThemeVariant.DARK, themeVariant(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(ThemeVariant.LIGHT, themeVariant(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun theAppThemePicksTheVersionByModeAndPhone() {
        val dark = tokens(ThemeVariant.DARK, base = ThemeVariant.DARK)
        val light = tokens(ThemeVariant.LIGHT, base = ThemeVariant.LIGHT)
        assertSame(dark, AppTheme(ThemeMode.SYSTEM, dark, light).tokens(systemDark = true))
        assertSame(light, AppTheme(ThemeMode.SYSTEM, dark, light).tokens(systemDark = false))
        assertSame(light, AppTheme(ThemeMode.LIGHT, dark, light).tokens(systemDark = true))
    }

    @Test
    fun theThemesColoursReplaceTheirTokensAndTheRestComesFromTheMatchingBase() {
        val colors = tokens(ThemeVariant.DARK, base = ThemeVariant.DARK, background = "#0f2a3a").toColors()
        assertEquals(Color(0xFF0F2A3A), colors.bgChat)
        assertEquals(Color(0xFF123456), colors.accent)
        assertEquals("Avatar colours come from the dark set", DarkColors.avatar1, colors.avatar1)
        assertEquals(DarkColors.backdrop, colors.backdrop)

        val light = tokens(ThemeVariant.DARK, base = ThemeVariant.LIGHT).toColors()
        assertEquals("A dark version with light colours takes the light set", LightColors.avatar1, light.avatar1)
        assertFalse(tokens(ThemeVariant.DARK, base = ThemeVariant.LIGHT).isDark)
        assertTrue(tokens(ThemeVariant.LIGHT, base = ThemeVariant.DARK).isDark)
    }

    @Test
    fun theMumcordTokensGiveTheAppsOwnColours() {
        val mumcord = ThemeTokens(
            themeId = "preset:mumcord",
            variant = ThemeVariant.DARK,
            base = ThemeVariant.DARK,
            bgServers = "#0e0a26",
            bgSidebar = "#150f38",
            bgChat = "#1a1342",
            bgElevated = "#231b55",
            bgHover = "#2c2366",
            bgSelected = "#2a1f5e",
            text = "#f6f0e8",
            textSecondary = "#cbc3e3",
            textMuted = "#aaa2cc",
            accent = "#a78bff",
            onAccent = "#0e0a26",
            accentText = "#ffb08a",
            success = "#3ee39e",
            warning = "#fbbf24",
            danger = "#ff8f8f",
            localMuted = "#ff7b7b",
            talking = "#3ee39e",
            accentStop1 = "#ffb08a",
            accentStop2 = "#ff6fa8",
            accentStop3 = "#9b7bff",
            primaryBg = "#ffd9c2",
            primaryText = "#0e0a26",
            border = "#31285f",
            borderStrong = "#8a80b8",
            focus = "#ffb08a",
        )
        assertEquals(DarkColors, mumcord.toColors())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aColourThatIsNotRrggbbIsRefused() {
        hexColor("red")
    }

    private fun tokens(variant: ThemeVariant, base: ThemeVariant, background: String = "#000000") = ThemeTokens(
        themeId = "custom:1",
        variant = variant,
        base = base,
        bgServers = background,
        bgSidebar = background,
        bgChat = background,
        bgElevated = background,
        bgHover = background,
        bgSelected = background,
        text = "#ffffff",
        textSecondary = "#ffffff",
        textMuted = "#ffffff",
        accent = "#123456",
        onAccent = "#000000",
        accentText = "#123456",
        success = "#00ff00",
        warning = "#ffff00",
        danger = "#ff0000",
        localMuted = "#ff0000",
        talking = "#00ff00",
        accentStop1 = "#123456",
        accentStop2 = "#123456",
        accentStop3 = "#123456",
        primaryBg = "#ffffff",
        primaryText = "#000000",
        border = "#333333",
        borderStrong = "#999999",
        focus = "#123456",
    )
}
