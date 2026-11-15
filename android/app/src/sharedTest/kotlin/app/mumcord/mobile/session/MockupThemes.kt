// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeColors
import app.mumcord.mobile.bridge.ThemeTokens
import app.mumcord.mobile.bridge.ThemeVariant

// The built-in themes as the bridge ships them (`bridge/src/themes/model.rs`), for screenshots of
// the themes screen, and the colours of "Deep Ocean" as the desktop app derives them
// (`bridge/src/themes/vectors.json`), for screenshots of screens in a theme other than Mumcord.

val MOCKUP_BUILT_IN_THEMES: List<Theme> = listOf(
    Theme(
        id = "preset:mumcord",
        name = "Mumcord",
        builtIn = true,
        dark = ThemeColors(background = "#1a1342", surface = "#231b55", text = "#f6f0e8", accent = "#a78bff", talking = "#3ee39e", warning = "#fbbf24", error = "#ff8f8f"),
        light = ThemeColors(background = "#ffffff", surface = "#eee9f8", text = "#17113a", accent = "#6a40e0", talking = "#15803d", warning = "#7f4d00", error = "#b01c1c"),
    ),
    Theme(
        id = "preset:ocean",
        name = "Deep Ocean",
        builtIn = true,
        dark = ThemeColors(background = "#0b1d2e", surface = "#12293f", text = "#e6f1fa", accent = "#4cc3e6", talking = "#4ade80", warning = "#facc15", error = "#fb8c8c"),
        light = ThemeColors(background = "#f8fbfd", surface = "#e6eff8", text = "#12202b", accent = "#1990b3", talking = "#1c9b4b", warning = "#7e6503", error = "#d20707"),
    ),
    Theme(
        id = "preset:forest",
        name = "Forest",
        builtIn = true,
        dark = ThemeColors(background = "#132018", surface = "#1c2d22", text = "#eef3e6", accent = "#9bd46a", talking = "#5eead4", warning = "#fcd34d", error = "#fca5a5"),
        light = ThemeColors(background = "#fafcfb", surface = "#edf4ef", text = "#202518", accent = "#5e982c", talking = "#149b86", warning = "#856602", error = "#d80707"),
    ),
    Theme(
        id = "preset:paper",
        name = "Paper",
        builtIn = true,
        dark = ThemeColors(background = "#221a09", surface = "#352a14", text = "#f0edea", accent = "#c55822", talking = "#218a42", warning = "#d68300", error = "#e7726c"),
        light = ThemeColors(background = "#fbf7ef", surface = "#f1e9da", text = "#2b2118", accent = "#b4501f", talking = "#1d7a3a", warning = "#7a4b00", error = "#a8231c"),
    ),
)

val OCEAN_DARK_TOKENS = ThemeTokens(
    themeId = "preset:ocean",
    variant = ThemeVariant.DARK,
    base = ThemeVariant.DARK,
    bgServers = "#061019",
    bgSidebar = "#091725",
    bgChat = "#0b1d2e",
    bgElevated = "#12293f",
    bgHover = "#23394e",
    bgSelected = "#1b4058",
    text = "#e6f1fa",
    textSecondary = "#bac7d1",
    textMuted = "#9eabb7",
    accent = "#4cc3e6",
    onAccent = "#000000",
    accentText = "#4cc3e6",
    success = "#4ade80",
    warning = "#facc15",
    danger = "#fb8c8c",
    localMuted = "#fb8c8c",
    talking = "#4ade80",
    accentStop1 = "#a3c87e",
    accentStop2 = "#a4a8b9",
    accentStop3 = "#4cc3e6",
    primaryBg = "#4cc3e6",
    primaryText = "#000000",
    border = "#2b4155",
    borderStrong = "#778998",
    focus = "#73cfeb",
)

val OCEAN_LIGHT_TOKENS = ThemeTokens(
    themeId = "preset:ocean",
    variant = ThemeVariant.LIGHT,
    base = ThemeVariant.LIGHT,
    bgServers = "#e1e5e8",
    bgSidebar = "#eff5fb",
    bgChat = "#f8fbfd",
    bgElevated = "#e6eff8",
    bgHover = "#d5dee8",
    bgSelected = "#c7e1ee",
    text = "#12202b",
    textSecondary = "#404c55",
    textMuted = "#566169",
    accent = "#1885a5",
    onAccent = "#000000",
    accentText = "#176983",
    success = "#186a3e",
    warning = "#735e07",
    danger = "#bf0a0b",
    localMuted = "#bf0a0b",
    talking = "#1b8f48",
    accentStop1 = "#5d8365",
    accentStop2 = "#916b78",
    accentStop3 = "#1885a5",
    primaryBg = "#1885a5",
    primaryText = "#000000",
    border = "#cdd6df",
    borderStrong = "#707b85",
    focus = "#177491",
)
