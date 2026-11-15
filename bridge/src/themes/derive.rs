// SPDX-License-Identifier: AGPL-3.0-only

//! From key colours to every shade, kept at WCAG AA (Spec 02 FR-2.14, FR-2.15), and the other
//! version of a theme generated from one: the desktop's `derive.ts` and `versions.ts`, step by
//! step, so a theme looks the same on both.

use super::ThemeVariant;
use super::colors::{
    BLACK, ColorSet, Key, PartialSet, Rgb, WHITE, clamp, contrast_ratio, from_hsl, min_contrast,
    mix, relative_luminance, to_hsl,
};
use super::model::{MUMCORD, Token, Tokens};

pub(crate) const TEXT_MIN_RATIO: f64 = 4.5;
pub(crate) const UI_MIN_RATIO: f64 = 3.0;

// The same pairs as the desktop's contrast test checks for the built-in token sets.
const BACKGROUNDS: [Token; 6] = [
    Token::BgServers,
    Token::BgSidebar,
    Token::BgChat,
    Token::BgElevated,
    Token::BgHover,
    Token::BgSelected,
];
const TEXT: [Token; 8] = [
    Token::Text,
    Token::TextSecondary,
    Token::TextMuted,
    Token::AccentText,
    Token::Success,
    Token::Warning,
    Token::Danger,
    Token::LocalMuted,
];
const NON_TEXT: [Token; 5] = [
    Token::Focus,
    Token::BorderStrong,
    Token::Accent,
    Token::PrimaryBg,
    Token::Talking,
];
const GRADIENT_STOPS: [Token; 3] = [Token::AccentStop1, Token::AccentStop2, Token::AccentStop3];

/// Moves `color` towards `target` in small steps until it reaches `min` against every colour in
/// `against`. `target` is always a colour already known to pass, so the walk ends in a passing
/// value.
fn ensure_contrast(color: Rgb, target: Rgb, against: &[Rgb], min: f64) -> Rgb {
    for step in 0..=20 {
        let candidate = mix(color, target, f64::from(step) / 20.0);
        if min_contrast(candidate, against) >= min {
            return candidate;
        }
    }
    target
}

/// Text lighter than the background means a dark theme; it picks the matching base token set.
pub(crate) fn theme_base(colors: &ColorSet) -> ThemeVariant {
    if relative_luminance(colors[Key::Text]) > relative_luminance(colors[Key::Background]) {
        ThemeVariant::Dark
    } else {
        ThemeVariant::Light
    }
}

pub(crate) fn derive_tokens(colors: &ColorSet) -> Tokens {
    let dark = theme_base(colors) == ThemeVariant::Dark;
    let (background, surface, text) = (
        colors[Key::Background],
        colors[Key::Surface],
        colors[Key::Text],
    );

    // Backgrounds only move back towards the main background, where the text is known to pass.
    let backdrop_shade = |shade: Rgb| ensure_contrast(shade, background, &[text], TEXT_MIN_RATIO);
    let backgrounds = [
        backdrop_shade(mix(
            background,
            if dark { BLACK } else { text },
            if dark { 0.45 } else { 0.1 },
        )),
        backdrop_shade(mix(
            background,
            if dark { BLACK } else { surface },
            if dark { 0.2 } else { 0.5 },
        )),
        background,
        surface,
        backdrop_shade(mix(surface, text, 0.08)),
        backdrop_shade(mix(surface, colors[Key::Accent], 0.15)),
    ];
    let readable = |color: Rgb| ensure_contrast(color, text, &backgrounds, TEXT_MIN_RATIO);
    let visible = |color: Rgb| ensure_contrast(color, text, &backgrounds, UI_MIN_RATIO);

    let accent = visible(colors[Key::Accent]);
    let on_accent = if contrast_ratio(WHITE, accent) >= contrast_ratio(BLACK, accent) {
        WHITE
    } else {
        BLACK
    };
    // Gradient stops move away from the on-accent colour, ending at the opposite extreme (21:1).
    let opposite = if on_accent == WHITE { BLACK } else { WHITE };
    let stop = |color: Rgb| ensure_contrast(color, opposite, &[on_accent], TEXT_MIN_RATIO);
    let danger = readable(colors[Key::Error]);

    let mut tokens = Tokens([BLACK; 25]);
    for (token, shade) in BACKGROUNDS.into_iter().zip(backgrounds) {
        tokens[token] = shade;
    }
    tokens[Token::Text] = text;
    tokens[Token::TextSecondary] = readable(mix(text, background, 0.2));
    tokens[Token::TextMuted] = readable(mix(text, background, 0.33));
    tokens[Token::Accent] = accent;
    tokens[Token::OnAccent] = on_accent;
    tokens[Token::AccentText] = readable(colors[Key::Accent]);
    tokens[Token::Success] = readable(colors[Key::Talking]);
    tokens[Token::Warning] = readable(colors[Key::Warning]);
    tokens[Token::Danger] = danger;
    tokens[Token::LocalMuted] = danger;
    tokens[Token::Talking] = visible(colors[Key::Talking]);
    tokens[Token::AccentStop1] = stop(mix(accent, colors[Key::Warning], 0.5));
    tokens[Token::AccentStop2] = stop(mix(accent, colors[Key::Error], 0.5));
    tokens[Token::AccentStop3] = stop(accent);
    // The primary pill is the accent itself, so it inherits the accent/on-accent guarantees.
    tokens[Token::PrimaryBg] = accent;
    tokens[Token::PrimaryText] = on_accent;
    tokens[Token::Border] = mix(surface, text, 0.12);
    tokens[Token::BorderStrong] = visible(mix(surface, text, 0.45));
    tokens[Token::Focus] = visible(mix(colors[Key::Accent], text, 0.25));
    tokens
}

/// How many of the checked pairs miss AA; the desktop's `checkTokens(…).length`.
pub(crate) fn token_failures(tokens: &Tokens) -> usize {
    let pairs = BACKGROUNDS
        .iter()
        .flat_map(|bg| TEXT.iter().map(move |fg| (*fg, *bg, TEXT_MIN_RATIO)))
        .chain(
            BACKGROUNDS
                .iter()
                .flat_map(|bg| NON_TEXT.iter().map(move |fg| (*fg, *bg, UI_MIN_RATIO))),
        )
        .chain([
            (Token::OnAccent, Token::Accent, TEXT_MIN_RATIO),
            (Token::PrimaryText, Token::PrimaryBg, TEXT_MIN_RATIO),
        ])
        .chain(
            GRADIENT_STOPS
                .iter()
                .map(|stop| (Token::OnAccent, *stop, TEXT_MIN_RATIO)),
        );
    pairs
        .filter(|(fg, bg, min)| contrast_ratio(tokens[*fg], tokens[*bg]) < *min)
        .count()
}

// Accent and talking are drawn as UI colours (their text shades are derived); warning and error
// are also used as text.
const KEY_MIN_RATIOS: [(Key, f64); 5] = [
    (Key::Text, TEXT_MIN_RATIO),
    (Key::Accent, UI_MIN_RATIO),
    (Key::Talking, UI_MIN_RATIO),
    (Key::Warning, TEXT_MIN_RATIO),
    (Key::Error, TEXT_MIN_RATIO),
];

/// The key colours that miss AA on the background or the surface (the editor's warnings).
pub(crate) fn key_issues(colors: &ColorSet) -> Vec<Key> {
    let backdrops = [colors[Key::Background], colors[Key::Surface]];
    KEY_MIN_RATIOS
        .iter()
        .filter(|(key, min)| min_contrast(colors[*key], &backdrops) < *min)
        .map(|(key, _)| *key)
        .collect()
}

/// False when the text itself isn't readable: such a theme can't be saved or imported.
pub(crate) fn is_valid(colors: &ColorSet) -> bool {
    !key_issues(colors).contains(&Key::Text) && token_failures(&derive_tokens(colors)) == 0
}

/// Walks the lightness of `color` towards black (light themes) or white (dark themes) until it
/// reaches `min` against every colour in `against`. Black and white are the strongest contrast a
/// background allows, so a reachable minimum is always found.
fn walk_lightness(color: Rgb, target: ThemeVariant, against: &[Rgb], min: f64) -> Rgb {
    let (hue, saturation, lightness) = to_hsl(color);
    let direction = if target == ThemeVariant::Light {
        -1.0
    } else {
        1.0
    };
    for step in 0..=50 {
        let candidate = from_hsl((
            hue,
            saturation,
            clamp(lightness + (direction * f64::from(step)) / 50.0),
        ));
        if min_contrast(candidate, against) >= min {
            return candidate;
        }
    }
    if target == ThemeVariant::Light {
        BLACK
    } else {
        WHITE
    }
}

/// The other version of a theme: backgrounds and text swap lightness while hue and saturation
/// stay, so the theme keeps its character; key colours keep their hue and move just far enough
/// in lightness to meet the same contrast rules as a hand-made theme.
pub(crate) fn generate_variant(colors: &ColorSet, target: ThemeVariant) -> ColorSet {
    let light = target == ThemeVariant::Light;
    let (bg_hue, bg_saturation, bg_lightness) = to_hsl(colors[Key::Background]);
    let (surface_hue, surface_saturation, surface_lightness) = to_hsl(colors[Key::Surface]);
    // Light backgrounds sit near white, dark ones well below mid grey, whatever the source was;
    // the surface keeps at least a visible step away from the background.
    let background_lightness = if light {
        1.0 - bg_lightness.min(0.5) * 0.15
    } else {
        0.06 + (1.0 - bg_lightness.max(0.5)) * 0.6
    };
    let step = (surface_lightness - bg_lightness).abs().max(0.04);
    let background = from_hsl((bg_hue, bg_saturation, background_lightness));
    let surface = from_hsl((
        surface_hue,
        surface_saturation,
        clamp(background_lightness + if light { -step } else { step }),
    ));
    let backdrops = [background, surface];
    let (text_hue, text_saturation, _) = to_hsl(colors[Key::Text]);
    // Extra headroom over AA so the derived sidebar and hover shades keep the text readable too.
    let text = walk_lightness(
        from_hsl((
            text_hue,
            text_saturation * 0.6,
            if light { 0.12 } else { 0.93 },
        )),
        target,
        &backdrops,
        TEXT_MIN_RATIO + 2.0,
    );
    // Key colours start from themselves and only move as far as the new background needs.
    let key_color = |key: Key, min: f64| walk_lightness(colors[key], target, &backdrops, min);
    ColorSet([
        background,
        surface,
        text,
        key_color(Key::Accent, UI_MIN_RATIO),
        key_color(Key::Talking, UI_MIN_RATIO),
        key_color(Key::Warning, TEXT_MIN_RATIO),
        key_color(Key::Error, TEXT_MIN_RATIO),
    ])
}

/// `colors` turned into the `target` version; Mumcord's version in the unlikely case it can't
/// pass.
pub(crate) fn generate_version(colors: &ColorSet, target: ThemeVariant) -> ColorSet {
    let generated = generate_variant(colors, target);
    if is_valid(&generated) {
        generated
    } else {
        MUMCORD.version(target)
    }
}

/// Both versions of a theme and the one that was generated, if any.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Versions {
    pub(crate) dark: ColorSet,
    pub(crate) light: ColorSet,
    pub(crate) generated: Option<ThemeVariant>,
}

/// Which version a lone colour set is, by its own brightness; unknown colours count as
/// Mumcord's.
pub(crate) fn variant_of(colors: &PartialSet) -> ThemeVariant {
    theme_base(&colors.over(MUMCORD.dark))
}

/// Both versions from one colour set (a theme from before themes had two versions).
pub(crate) fn from_single_set(colors: &PartialSet) -> Versions {
    let variant = variant_of(colors);
    let given = colors.over(MUMCORD.version(variant));
    let other = variant.other();
    let generated = generate_version(&given, other);
    match variant {
        ThemeVariant::Dark => Versions {
            dark: given,
            light: generated,
            generated: Some(other),
        },
        ThemeVariant::Light => Versions {
            dark: generated,
            light: given,
            generated: Some(other),
        },
    }
}

/// Both versions from what a stored or shared theme carries. With only one set it is read like
/// a single-set theme; missing colours in a set are filled from Mumcord's matching version.
pub(crate) fn complete_versions(
    dark: &PartialSet,
    light: &PartialSet,
    generated: Option<ThemeVariant>,
) -> Versions {
    if !dark.is_empty() && !light.is_empty() {
        return Versions {
            dark: dark.over(MUMCORD.dark),
            light: light.over(MUMCORD.light),
            generated,
        };
    }
    from_single_set(if dark.is_empty() { light } else { dark })
}
