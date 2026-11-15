// SPDX-License-Identifier: AGPL-3.0-only

//! Theme codes (Spec 02 FR-2.16): `🎨 Mumcord theme "‹name›" – mct1.‹code›`, where the code is
//! base64url of UTF-8 JSON `{"v": creator version, "n": name, "dark": {key colour: "#rrggbb"},
//! "light": {…}}`. Codes from older builds carry one set (`c`, or `colors`) and are read as the
//! version matching its brightness, the other version generated. The desktop's `shareCodec.ts`,
//! byte for byte: a code made on one opens on the other.
//!
//! Codes arrive from anyone, so reading accepts colour values only, treats the name as plain
//! text and never panics. It is stricter than the desktop in three cases no Mumcord writes: JSON
//! nested deeper than 128 levels, numbers beyond the range of a float, and unpaired UTF-16
//! escapes are refused.

use base64::Engine as _;
use base64::alphabet::URL_SAFE;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use icu_properties::CodePointMapData;
use icu_properties::props::GeneralCategory;
use serde_json::{Map, Value};
use unicode_segmentation::UnicodeSegmentation;

use super::colors::{ColorSet, Key, PartialSet, Rgb};
use super::derive::{Versions, complete_versions, is_valid};

const PREFIX: &str = "mct1.";
/// Two sets of seven colours, a full-length name and a version fit in well under half of this.
pub(crate) const MAX_CODE_LENGTH: usize = 2048;
pub(crate) const MAX_NAME_LENGTH: usize = 64;
const MAX_VERSION_LENGTH: usize = 40;

/// The desktop decodes with the browser's `atob`, which ignores leftover bits at the end.
const BASE64URL: GeneralPurpose = GeneralPurpose::new(
    &URL_SAFE,
    GeneralPurposeConfig::new()
        .with_encode_padding(false)
        .with_decode_allow_trailing_bits(true)
        .with_decode_padding_mode(DecodePaddingMode::RequireNone),
);

/// Why a code was not read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DecodeError {
    /// Not a theme code, or it carries something other than colours.
    Invalid,
    /// A theme code whose text would not be readable (WCAG AA) in one of its versions.
    Unreadable,
}

/// A theme as a code carries it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Decoded {
    pub(crate) name: String,
    pub(crate) versions: Versions,
    /// Mumcord version that created the theme.
    pub(crate) created_with: String,
    /// Created by a newer Mumcord: colours this version doesn't know were ignored.
    pub(crate) made_with_newer: bool,
}

/// Strips control and format characters (bidi overrides among them could disguise the name),
/// trims and caps the length in visible characters.
pub(crate) fn clean_name(name: &str) -> String {
    let categories = CodePointMapData::<GeneralCategory>::new();
    let visible: String = name
        .chars()
        .filter(|c| {
            !matches!(
                categories.get(*c),
                GeneralCategory::Control | GeneralCategory::Format
            )
        })
        .collect();
    visible
        .trim()
        .graphemes(true)
        .take(MAX_NAME_LENGTH)
        .collect()
}

/// The release numbers of `1.2.3`, `1.2.3-beta.1` or `1.2.3+build`. Numbers as the desktop reads
/// them (JavaScript numbers), so very long ones compare the same.
fn parse_version(version: &str) -> Option<[f64; 3]> {
    let end = version.find(['-', '+']).unwrap_or(version.len());
    let (numbers, suffix) = version.split_at(end);
    let suffix_ok = suffix
        .chars()
        .skip(1)
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-');
    if !suffix_ok {
        return None;
    }
    let mut parts = numbers.split('.');
    let mut release = [0.0; 3];
    for number in &mut release {
        let part = parts.next()?;
        if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        *number = part.parse().ok()?;
    }
    parts.next().is_none().then_some(release)
}

/// True when `version` is a higher release than `current`; pre-release suffixes are ignored.
pub(crate) fn is_newer_version(version: &str, current: &str) -> bool {
    let (Some(a), Some(b)) = (parse_version(version), parse_version(current)) else {
        return false;
    };
    a.iter()
        .zip(b.iter())
        .find(|(a, b)| a != b)
        .is_some_and(|(a, b)| a > b)
}

fn json_string(text: &str) -> String {
    // A string always serialises.
    serde_json::to_string(text).unwrap_or_default()
}

fn json_colors(colors: &ColorSet) -> String {
    let entries: Vec<String> = Key::ALL
        .iter()
        .map(|key| format!("\"{}\":\"{}\"", key.name(), colors[*key].hex()))
        .collect();
    format!("{{{}}}", entries.join(","))
}

/// The code of a theme; `version` is the Mumcord version that writes it. Written by hand rather
/// than through a JSON map: the desktop's key order is part of the code.
pub(crate) fn encode(name: &str, dark: &ColorSet, light: &ColorSet, version: &str) -> String {
    let json = format!(
        "{{\"v\":{},\"n\":{},\"dark\":{},\"light\":{}}}",
        json_string(version),
        json_string(&clean_name(name)),
        json_colors(dark),
        json_colors(light),
    );
    format!("{PREFIX}{}", BASE64URL.encode(json))
}

/// The text the share sheet sends: readable in any Mumble client, a preview card in Mumcord.
pub(crate) fn share_message(
    name: &str,
    dark: &ColorSet,
    light: &ColorSet,
    version: &str,
) -> String {
    format!(
        "🎨 Mumcord theme \"{}\" – {}",
        clean_name(name),
        encode(name, dark, light, version)
    )
}

fn is_code_char(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_'
}

/// The first theme code in a text (a pasted share message or a bare code), if any.
pub(crate) fn find_code(text: &str) -> Option<&str> {
    let bytes = text.as_bytes();
    let mut from = 0;
    while let Some(found) = text[from..].find(PREFIX) {
        let start = from + found;
        let body = start + PREFIX.len();
        let length = bytes[body..]
            .iter()
            .take(MAX_CODE_LENGTH)
            .take_while(|b| is_code_char(**b))
            .count();
        if length > 0 {
            return Some(&text[start..body + length]);
        }
        from = body;
    }
    None
}

fn parse_payload(code: &str) -> Option<Value> {
    // The desktop counts UTF-16 units; a longer code has non-ASCII characters and fails below.
    if code.len() > MAX_CODE_LENGTH {
        return None;
    }
    let body = code.strip_prefix(PREFIX)?;
    if body.is_empty() || !body.bytes().all(is_code_char) {
        return None;
    }
    let bytes = BASE64URL.decode(body).ok()?;
    let text = std::str::from_utf8(&bytes).ok()?;
    // The browser's UTF-8 decoder drops a leading byte order mark.
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    serde_json::from_str(text).ok()
}

/// The colours of one set; `None` when any value is not a colour; a missing set is empty.
fn read_colors(raw: Option<&Value>) -> Option<PartialSet> {
    let Some(raw) = raw else {
        return Some(PartialSet::default());
    };
    let object = raw.as_object()?;
    let mut colors = PartialSet::default();
    for key in Key::ALL {
        let Some(value) = object.get(key.name()) else {
            continue;
        };
        colors.0[key as usize] = Some(Rgb::parse(&value.as_str()?.to_lowercase())?);
    }
    Some(colors)
}

/// The single set of an older code: `c`, or `colors` when `c` is missing or null.
fn single_set(payload: &Map<String, Value>) -> Option<&Value> {
    payload
        .get("c")
        .filter(|value| !value.is_null())
        .or_else(|| payload.get("colors"))
}

pub(crate) fn decode(code: &str, current_version: &str) -> Result<Decoded, DecodeError> {
    let payload = parse_payload(code).ok_or(DecodeError::Invalid)?;
    let payload = payload.as_object().ok_or(DecodeError::Invalid)?;
    let version = payload
        .get("v")
        .and_then(Value::as_str)
        .filter(|v| v.len() <= MAX_VERSION_LENGTH && parse_version(v).is_some())
        .ok_or(DecodeError::Invalid)?;
    let raw_name = payload
        .get("n")
        .and_then(Value::as_str)
        .ok_or(DecodeError::Invalid)?;
    let name = clean_name(raw_name);
    if name.is_empty() {
        return Err(DecodeError::Invalid);
    }

    let dark = read_colors(payload.get("dark")).ok_or(DecodeError::Invalid)?;
    let light = read_colors(payload.get("light")).ok_or(DecodeError::Invalid)?;
    let single = read_colors(single_set(payload)).ok_or(DecodeError::Invalid)?;
    let two_sets = !dark.is_empty() || !light.is_empty();
    if !two_sets && single.is_empty() {
        return Err(DecodeError::Invalid);
    }
    // A code with one set (older builds, or only `dark` / `light`) gets the other version
    // generated.
    let versions = if two_sets {
        complete_versions(&dark, &light, None)
    } else {
        complete_versions(&single, &PartialSet::default(), None)
    };
    if !is_valid(&versions.dark) || !is_valid(&versions.light) {
        return Err(DecodeError::Unreadable);
    }
    Ok(Decoded {
        name,
        versions,
        created_with: version.to_owned(),
        made_with_newer: is_newer_version(version, current_version),
    })
}
