// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import MumcordBridge

// The built-in themes as the bridge ships them (`bridge/src/themes/model.rs`), the colours of
// Mumcord as the design tokens have them and of "Deep Ocean" as the desktop app derives them
// (`bridge/src/themes/vectors.json`), for the fake engine, as in the Android app's
// `MockupThemes.kt`.

enum MockupThemes {
    static let mumcordId = "preset:mumcord"
    static let oceanId = "preset:ocean"

    static let builtIn: [Theme] = [
        Theme(
            id: mumcordId,
            name: "Mumcord",
            builtIn: true,
            dark: colors("#1a1342", "#231b55", "#f6f0e8", "#a78bff", "#3ee39e", "#fbbf24", "#ff8f8f"),
            light: colors("#ffffff", "#eee9f8", "#17113a", "#6a40e0", "#15803d", "#7f4d00", "#b01c1c")
        ),
        Theme(
            id: oceanId,
            name: "Deep Ocean",
            builtIn: true,
            dark: colors("#0b1d2e", "#12293f", "#e6f1fa", "#4cc3e6", "#4ade80", "#facc15", "#fb8c8c"),
            light: colors("#f8fbfd", "#e6eff8", "#12202b", "#1990b3", "#1c9b4b", "#7e6503", "#d20707")
        ),
        Theme(
            id: "preset:forest",
            name: "Forest",
            builtIn: true,
            dark: colors("#132018", "#1c2d22", "#eef3e6", "#9bd46a", "#5eead4", "#fcd34d", "#fca5a5"),
            light: colors("#fafcfb", "#edf4ef", "#202518", "#5e982c", "#149b86", "#856602", "#d80707")
        ),
        Theme(
            id: "preset:paper",
            name: "Paper",
            builtIn: true,
            dark: colors("#221a09", "#352a14", "#f0edea", "#c55822", "#218a42", "#d68300", "#e7726c"),
            light: colors("#fbf7ef", "#f1e9da", "#2b2118", "#b4501f", "#1d7a3a", "#7a4b00", "#a8231c")
        ),
    ]

    /// A theme made on the desktop and pasted on the phone, for "Your themes".
    static func fromTheDesktop(id: String) -> Theme {
        var dark = builtIn[2].dark
        dark.background = "#120d0c"
        dark.accent = "#f3521d"
        var light = builtIn[2].light
        light.accent = "#b4501f"
        return Theme(id: id, name: "From the desktop", builtIn: false, dark: dark, light: light)
    }

    /// The design tokens' own colours (`design/tokens.json`): the app looks the same in the
    /// built-in Mumcord theme as before the engine answered.
    static let mumcordDark = tokens(
        mumcordId, .dark, .dark,
        ["#0e0a26", "#150f38", "#1a1342", "#231b55", "#2c2366", "#2a1f5e", "#f6f0e8", "#cbc3e3", "#aaa2cc",
         "#a78bff", "#0e0a26", "#ffb08a", "#3ee39e", "#fbbf24", "#ff8f8f", "#ff7b7b", "#3ee39e", "#ffb08a",
         "#ff6fa8", "#9b7bff", "#ffd9c2", "#0e0a26", "#31285f", "#8a80b8", "#ffb08a"]
    )

    static let mumcordLight = tokens(
        mumcordId, .light, .light,
        ["#dfd8f1", "#f5f2fb", "#ffffff", "#eee9f8", "#e2daf3", "#e6dcfa", "#17113a", "#3d3566", "#544c7c",
         "#6a40e0", "#ffffff", "#5b34d6", "#136634", "#7f4d00", "#b01c1c", "#b3121f", "#15803d", "#c2410c",
         "#c21f62", "#6a40e0", "#17113a", "#ffffff", "#d6cfe8", "#716894", "#c21f62"]
    )

    static let oceanDark = tokens(
        oceanId, .dark, .dark,
        ["#061019", "#091725", "#0b1d2e", "#12293f", "#23394e", "#1b4058", "#e6f1fa", "#bac7d1", "#9eabb7",
         "#4cc3e6", "#000000", "#4cc3e6", "#4ade80", "#facc15", "#fb8c8c", "#fb8c8c", "#4ade80", "#a3c87e",
         "#a4a8b9", "#4cc3e6", "#4cc3e6", "#000000", "#2b4155", "#778998", "#73cfeb"]
    )

    static let oceanLight = tokens(
        oceanId, .light, .light,
        ["#e1e5e8", "#eff5fb", "#f8fbfd", "#e6eff8", "#d5dee8", "#c7e1ee", "#12202b", "#404c55", "#566169",
         "#1885a5", "#000000", "#176983", "#186a3e", "#735e07", "#bf0a0b", "#bf0a0b", "#1b8f48", "#5d8365",
         "#916b78", "#1885a5", "#1885a5", "#000000", "#cdd6df", "#707b85", "#177491"]
    )

    /// The colours of a theme without hand-made tokens: its key colours spread over the tokens,
    /// enough to see a theme other than Mumcord. The real engine derives finer shades.
    static func derived(_ theme: Theme, _ variant: ThemeVariant) -> ThemeTokens {
        let key = variant == .dark ? theme.dark : theme.light
        let onAccent = variant == .dark ? "#000000" : "#ffffff"
        return tokens(
            theme.id, variant, variant,
            [key.background, key.background, key.background, key.surface, key.surface, key.surface, key.text,
             key.text, key.text, key.accent, onAccent, key.accent, key.talking, key.warning, key.error, key.error,
             key.talking, key.accent, key.accent, key.accent, key.accent, onAccent, key.surface, key.text,
             key.accent]
        )
    }

    private static func colors(
        _ background: String,
        _ surface: String,
        _ text: String,
        _ accent: String,
        _ talking: String,
        _ warning: String,
        _ error: String
    ) -> ThemeColors {
        ThemeColors(
            background: background,
            surface: surface,
            text: text,
            accent: accent,
            talking: talking,
            warning: warning,
            error: error
        )
    }

    /// The 25 tokens in the order of `ThemeTokens`.
    private static func tokens(_ id: String, _ variant: ThemeVariant, _ base: ThemeVariant, _ v: [String]) -> ThemeTokens {
        ThemeTokens(
            themeId: id,
            variant: variant,
            base: base,
            bgServers: v[0],
            bgSidebar: v[1],
            bgChat: v[2],
            bgElevated: v[3],
            bgHover: v[4],
            bgSelected: v[5],
            text: v[6],
            textSecondary: v[7],
            textMuted: v[8],
            accent: v[9],
            onAccent: v[10],
            accentText: v[11],
            success: v[12],
            warning: v[13],
            danger: v[14],
            localMuted: v[15],
            talking: v[16],
            accentStop1: v[17],
            accentStop2: v[18],
            accentStop3: v[19],
            primaryBg: v[20],
            primaryText: v[21],
            border: v[22],
            borderStrong: v[23],
            focus: v[24]
        )
    }
}
#endif
