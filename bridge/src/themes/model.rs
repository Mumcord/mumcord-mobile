// SPDX-License-Identifier: AGPL-3.0-only

//! The built-in themes and the shades a theme is drawn with, as the desktop app defines them
//! (`ui/src/theme/themeModel.ts`, `derive.ts`, `tokens.css`).

use std::ops::{Index, IndexMut};

use super::ThemeVariant;
use super::colors::{ColorSet, Rgb, rgb};

pub(crate) const MUMCORD_ID: &str = "preset:mumcord";
const PRESET_PREFIX: &str = "preset:";
/// The former separate Mumcord Dark and Light presets, still named by older settings.
const LEGACY_MUMCORD_IDS: [&str; 2] = ["preset:dark", "preset:light"];

/// A theme as the logic sees it: both versions, and which one was generated from the other and
/// not edited by hand since (kept so a theme saved here stays the same for the desktop editor).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ThemeDef {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) dark: ColorSet,
    pub(crate) light: ColorSet,
    pub(crate) generated: Option<ThemeVariant>,
}

impl ThemeDef {
    pub(crate) fn version(&self, variant: ThemeVariant) -> ColorSet {
        match variant {
            ThemeVariant::Dark => self.dark,
            ThemeVariant::Light => self.light,
        }
    }
}

pub(crate) struct Preset {
    pub(crate) id: &'static str,
    pub(crate) name: &'static str,
    pub(crate) dark: ColorSet,
    pub(crate) light: ColorSet,
}

impl Preset {
    pub(crate) fn def(&self) -> ThemeDef {
        ThemeDef {
            id: self.id.to_owned(),
            name: self.name.to_owned(),
            dark: self.dark,
            light: self.light,
            generated: None,
        }
    }

    pub(crate) fn version(&self, variant: ThemeVariant) -> ColorSet {
        match variant {
            ThemeVariant::Dark => self.dark,
            ThemeVariant::Light => self.light,
        }
    }
}

const fn set(colors: [u32; 7]) -> ColorSet {
    let mut set = [Rgb([0; 3]); 7];
    let mut index = 0;
    while index < 7 {
        set[index] = rgb(colors[index]);
        index += 1;
    }
    ColorSet(set)
}

/// Mumcord repeats the key colours of its hand-tuned token sets ([`MUMCORD_DARK_TOKENS`],
/// [`MUMCORD_LIGHT_TOKENS`]); showing it uses those sets instead of derived shades.
pub(crate) const MUMCORD: Preset = Preset {
    id: MUMCORD_ID,
    name: "Mumcord",
    dark: set([
        0x1a1342, 0x231b55, 0xf6f0e8, 0xa78bff, 0x3ee39e, 0xfbbf24, 0xff8f8f,
    ]),
    light: set([
        0xffffff, 0xeee9f8, 0x17113a, 0x6a40e0, 0x15803d, 0x7f4d00, 0xb01c1c,
    ]),
};

/// The other version of each curated preset started as the generated one and is shipped fixed.
pub(crate) const PRESETS: [Preset; 4] = [
    MUMCORD,
    Preset {
        id: "preset:ocean",
        name: "Deep Ocean",
        dark: set([
            0x0b1d2e, 0x12293f, 0xe6f1fa, 0x4cc3e6, 0x4ade80, 0xfacc15, 0xfb8c8c,
        ]),
        light: set([
            0xf8fbfd, 0xe6eff8, 0x12202b, 0x1990b3, 0x1c9b4b, 0x7e6503, 0xd20707,
        ]),
    },
    Preset {
        id: "preset:forest",
        name: "Forest",
        dark: set([
            0x132018, 0x1c2d22, 0xeef3e6, 0x9bd46a, 0x5eead4, 0xfcd34d, 0xfca5a5,
        ]),
        light: set([
            0xfafcfb, 0xedf4ef, 0x202518, 0x5e982c, 0x149b86, 0x856602, 0xd80707,
        ]),
    },
    Preset {
        id: "preset:paper",
        name: "Paper",
        dark: set([
            0x221a09, 0x352a14, 0xf0edea, 0xc55822, 0x218a42, 0xd68300, 0xe7726c,
        ]),
        light: set([
            0xfbf7ef, 0xf1e9da, 0x2b2118, 0xb4501f, 0x1d7a3a, 0x7a4b00, 0xa8231c,
        ]),
    },
];

pub(crate) fn is_preset_id(id: &str) -> bool {
    id.starts_with(PRESET_PREFIX)
}

pub(crate) fn find_preset(id: &str) -> Option<&'static Preset> {
    if LEGACY_MUMCORD_IDS.contains(&id) {
        return Some(&PRESETS[0]);
    }
    PRESETS.iter().find(|preset| preset.id == id)
}

/// The shades a theme sets (the desktop's `--color-*` tokens), in the order of `THEME_TOKENS`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Token {
    BgServers,
    BgSidebar,
    BgChat,
    BgElevated,
    BgHover,
    BgSelected,
    Text,
    TextSecondary,
    TextMuted,
    Accent,
    OnAccent,
    AccentText,
    Success,
    Warning,
    Danger,
    LocalMuted,
    Talking,
    AccentStop1,
    AccentStop2,
    AccentStop3,
    PrimaryBg,
    PrimaryText,
    Border,
    BorderStrong,
    Focus,
}

#[cfg(test)]
impl Token {
    /// The token names of the desktop, without the `--color-` prefix.
    pub(crate) const NAMES: [&'static str; 25] = [
        "bg-servers",
        "bg-sidebar",
        "bg-chat",
        "bg-elevated",
        "bg-hover",
        "bg-selected",
        "text",
        "text-secondary",
        "text-muted",
        "accent",
        "on-accent",
        "accent-text",
        "success",
        "warning",
        "danger",
        "local-muted",
        "talking",
        "accent-stop-1",
        "accent-stop-2",
        "accent-stop-3",
        "primary-bg",
        "primary-text",
        "border",
        "border-strong",
        "focus",
    ];
}

/// A full set of shades, indexed by [`Token`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Tokens(pub(crate) [Rgb; 25]);

impl Index<Token> for Tokens {
    type Output = Rgb;

    fn index(&self, token: Token) -> &Rgb {
        &self.0[token as usize]
    }
}

impl IndexMut<Token> for Tokens {
    fn index_mut(&mut self, token: Token) -> &mut Rgb {
        &mut self.0[token as usize]
    }
}

const fn tokens(colors: [u32; 25]) -> Tokens {
    let mut set = [Rgb([0; 3]); 25];
    let mut index = 0;
    while index < 25 {
        set[index] = rgb(colors[index]);
        index += 1;
    }
    Tokens(set)
}

/// The hand-tuned token sets of `tokens.css` (also in `design/tokens.json`, checked by a test).
pub(crate) const MUMCORD_DARK_TOKENS: Tokens = tokens([
    0x0e0a26, 0x150f38, 0x1a1342, 0x231b55, 0x2c2366, 0x2a1f5e, 0xf6f0e8, 0xcbc3e3, 0xaaa2cc,
    0xa78bff, 0x0e0a26, 0xffb08a, 0x3ee39e, 0xfbbf24, 0xff8f8f, 0xff7b7b, 0x3ee39e, 0xffb08a,
    0xff6fa8, 0x9b7bff, 0xffd9c2, 0x0e0a26, 0x31285f, 0x8a80b8, 0xffb08a,
]);

pub(crate) const MUMCORD_LIGHT_TOKENS: Tokens = tokens([
    0xdfd8f1, 0xf5f2fb, 0xffffff, 0xeee9f8, 0xe2daf3, 0xe6dcfa, 0x17113a, 0x3d3566, 0x544c7c,
    0x6a40e0, 0xffffff, 0x5b34d6, 0x136634, 0x7f4d00, 0xb01c1c, 0xb3121f, 0x15803d, 0xc2410c,
    0xc21f62, 0x6a40e0, 0x17113a, 0xffffff, 0xd6cfe8, 0x716894, 0xc21f62,
]);
