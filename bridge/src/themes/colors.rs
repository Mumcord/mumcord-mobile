// SPDX-License-Identifier: AGPL-3.0-only

//! Colours and their arithmetic: WCAG 2.x contrast, linear mixing and HSL, computed exactly as
//! the desktop app does (`ui/src/theme/contrast.ts`, `derive.ts`), so both make the same shades
//! from the same key colours.

use std::ops::{Index, IndexMut};

/// A `#rrggbb` colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Rgb(pub(crate) [u8; 3]);

pub(crate) const BLACK: Rgb = Rgb([0, 0, 0]);
pub(crate) const WHITE: Rgb = Rgb([255, 255, 255]);

/// `0xrrggbb` as a colour, for the built-in tables.
pub(crate) const fn rgb(value: u32) -> Rgb {
    Rgb([(value >> 16) as u8, (value >> 8) as u8, value as u8])
}

impl Rgb {
    /// Exactly `#` and six lower-case hex digits; the only form a theme carries.
    pub(crate) fn parse(text: &str) -> Option<Self> {
        let digits = text.strip_prefix('#')?.as_bytes();
        if digits.len() != 6
            || !digits
                .iter()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        {
            return None;
        }
        // Checked above: six ASCII hex digits.
        let byte = |i: usize| u8::from_str_radix(&text[1 + 2 * i..3 + 2 * i], 16).ok();
        Some(Self([byte(0)?, byte(1)?, byte(2)?]))
    }

    pub(crate) fn hex(self) -> String {
        let [r, g, b] = self.0;
        format!("#{r:02x}{g:02x}{b:02x}")
    }
}

/// The seven key colours of one version of a theme, in the order of the desktop's
/// `THEME_COLOR_KEYS` (which is also their order in a theme code).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Key {
    Background,
    Surface,
    Text,
    Accent,
    Talking,
    Warning,
    Error,
}

impl Key {
    pub(crate) const ALL: [Self; 7] = [
        Self::Background,
        Self::Surface,
        Self::Text,
        Self::Accent,
        Self::Talking,
        Self::Warning,
        Self::Error,
    ];

    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::Background => "background",
            Self::Surface => "surface",
            Self::Text => "text",
            Self::Accent => "accent",
            Self::Talking => "talking",
            Self::Warning => "warning",
            Self::Error => "error",
        }
    }
}

/// One version (dark or light) of a theme: its seven key colours.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct ColorSet(pub(crate) [Rgb; 7]);

impl Index<Key> for ColorSet {
    type Output = Rgb;

    fn index(&self, key: Key) -> &Rgb {
        &self.0[key as usize]
    }
}

impl IndexMut<Key> for ColorSet {
    fn index_mut(&mut self, key: Key) -> &mut Rgb {
        &mut self.0[key as usize]
    }
}

/// Some of the key colours: what an older or a newer theme code, or a stored theme, carries.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct PartialSet(pub(crate) [Option<Rgb>; 7]);

impl PartialSet {
    pub(crate) fn is_empty(&self) -> bool {
        self.0.iter().all(Option::is_none)
    }

    /// `base` with the colours this set has.
    pub(crate) fn over(&self, base: ColorSet) -> ColorSet {
        let mut set = base;
        for key in Key::ALL {
            if let Some(color) = self.0[key as usize] {
                set[key] = color;
            }
        }
        set
    }
}

fn channel(value: u8) -> f64 {
    let srgb = f64::from(value) / 255.0;
    if srgb <= 0.04045 {
        srgb / 12.92
    } else {
        ((srgb + 0.055) / 1.055).powf(2.4)
    }
}

pub(crate) fn relative_luminance(color: Rgb) -> f64 {
    let [r, g, b] = color.0.map(channel);
    0.2126 * r + 0.7152 * g + 0.0722 * b
}

pub(crate) fn contrast_ratio(foreground: Rgb, background: Rgb) -> f64 {
    let a = relative_luminance(foreground);
    let b = relative_luminance(background);
    (a.max(b) + 0.05) / (a.min(b) + 0.05)
}

pub(crate) fn min_contrast(color: Rgb, against: &[Rgb]) -> f64 {
    against
        .iter()
        .map(|other| contrast_ratio(color, *other))
        .fold(f64::INFINITY, f64::min)
}

/// JavaScript's `Math.round`: halves go up, also below zero.
fn js_round(value: f64) -> f64 {
    let floor = value.floor();
    if value - floor >= 0.5 {
        floor + 1.0
    } else {
        floor
    }
}

/// One channel from a computed value. The arithmetic stays within 0 … 255; the cast saturates
/// where the desktop would print an impossible value.
fn to_channel(value: f64) -> u8 {
    js_round(value) as u8
}

/// Linear sRGB mix: `amount` 0 keeps `from`, 1 gives `to`.
pub(crate) fn mix(from: Rgb, to: Rgb, amount: f64) -> Rgb {
    let mut mixed = [0; 3];
    for (index, out) in mixed.iter_mut().enumerate() {
        let a = f64::from(from.0[index]);
        let b = f64::from(to.0[index]);
        *out = to_channel(a + (b - a) * amount);
    }
    Rgb(mixed)
}

/// Hue in degrees, saturation and lightness in 0 … 1.
pub(crate) type Hsl = (f64, f64, f64);

pub(crate) fn to_hsl(color: Rgb) -> Hsl {
    let [r, g, b] = color.0.map(|value| f64::from(value) / 255.0);
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let lightness = (max + min) / 2.0;
    let delta = max - min;
    if delta == 0.0 {
        return (0.0, 0.0, lightness);
    }
    let saturation = delta / (1.0 - (2.0 * lightness - 1.0).abs());
    let sector = if max == r {
        ((g - b) / delta + 6.0) % 6.0
    } else if max == g {
        (b - r) / delta + 2.0
    } else {
        (r - g) / delta + 4.0
    };
    (sector * 60.0, saturation, lightness)
}

pub(crate) fn from_hsl((hue, saturation, lightness): Hsl) -> Rgb {
    let chroma = (1.0 - (2.0 * lightness - 1.0).abs()) * saturation;
    let x = chroma * (1.0 - ((hue / 60.0) % 2.0 - 1.0).abs());
    let m = lightness - chroma / 2.0;
    let sector = (hue / 60.0).floor() % 6.0;
    let (r, g, b) = match sector as i64 {
        0 => (chroma, x, 0.0),
        1 => (x, chroma, 0.0),
        2 => (0.0, chroma, x),
        3 => (0.0, x, chroma),
        4 => (x, 0.0, chroma),
        5 => (chroma, 0.0, x),
        _ => (0.0, 0.0, 0.0),
    };
    Rgb([r, g, b].map(|value| to_channel((value + m) * 255.0)))
}

pub(crate) fn clamp(value: f64) -> f64 {
    value.clamp(0.0, 1.0)
}
