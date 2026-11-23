// SPDX-License-Identifier: AGPL-3.0-only

//! The theme logic against the desktop app: the vectors in `vectors.json` are the desktop
//! code's own outputs (`scripts/theme-vectors.mjs`), so every comparison here is "the same
//! input gives the same output as on the desktop". The named tests below repeat the desktop's
//! unit tests (`derive.test.ts`, `versions.test.ts`, `shareCodec.test.ts`, `appTheme.test.ts`).

use std::collections::{BTreeMap, HashMap};

use serde_json::Value;

use super::code::{self, DecodeError, MAX_CODE_LENGTH};
use super::colors::{ColorSet, Key, PartialSet, Rgb, contrast_ratio, mix, relative_luminance};
use super::derive::{
    derive_tokens, from_single_set, generate_version, is_valid, key_issues, theme_base,
    token_failures, variant_of,
};
use super::model::{
    MUMCORD, MUMCORD_DARK_TOKENS, MUMCORD_LIGHT_TOKENS, PRESETS, Token, Tokens, find_preset,
};
use super::{ThemeState, ThemeVariant, from_stored, to_stored};

const VECTORS: &str = include_str!("vectors.json");

fn vectors() -> Value {
    serde_json::from_str(VECTORS).expect("vectors.json")
}

fn list<'a>(vectors: &'a Value, name: &str) -> &'a Vec<Value> {
    vectors[name].as_array().expect(name)
}

fn color(value: &Value) -> Rgb {
    Rgb::parse(value.as_str().expect("colour")).expect("#rrggbb")
}

fn color_set(value: &Value) -> ColorSet {
    let mut set = ColorSet([Rgb([0; 3]); 7]);
    for key in Key::ALL {
        set[key] = color(&value[key.name()]);
    }
    set
}

fn partial_set(value: &Value) -> PartialSet {
    let mut set = PartialSet::default();
    for key in Key::ALL {
        set.0[key as usize] = value.get(key.name()).map(color);
    }
    set
}

fn variant(value: &Value) -> Option<ThemeVariant> {
    match value.as_str() {
        Some("dark") => Some(ThemeVariant::Dark),
        Some("light") => Some(ThemeVariant::Light),
        _ => None,
    }
}

fn tokens_of(value: &Value) -> Tokens {
    let mut tokens = Tokens([Rgb([0; 3]); 25]);
    for (index, name) in Token::NAMES.iter().enumerate() {
        tokens.0[index] = color(&value[*name]);
    }
    tokens
}

#[test]
fn shades_contrast_and_generated_versions_match_the_desktop() {
    let vectors = vectors();
    let sets = list(&vectors, "colorSets");
    assert!(sets.len() >= 50);
    for (index, case) in sets.iter().enumerate() {
        let colors = color_set(&case["colors"]);
        assert_eq!(Some(theme_base(&colors)), variant(&case["base"]), "{index}");
        assert_eq!(
            derive_tokens(&colors),
            tokens_of(&case["tokens"]),
            "{index}"
        );
        assert_eq!(
            is_valid(&colors),
            case["ok"].as_bool().expect("ok"),
            "{index}"
        );
        let issues: Vec<&str> = key_issues(&colors).iter().map(|key| key.name()).collect();
        let expected: Vec<&str> = list(case, "issues")
            .iter()
            .map(|key| key.as_str().expect("key"))
            .collect();
        assert_eq!(issues, expected, "{index}");
        assert_eq!(
            token_failures(&derive_tokens(&colors)) as u64,
            case["tokenFailures"].as_u64().expect("count"),
            "{index}"
        );
        for (target, variant_key, version_key) in [
            (ThemeVariant::Dark, "variantDark", "versionDark"),
            (ThemeVariant::Light, "variantLight", "versionLight"),
        ] {
            assert_eq!(
                super::derive::generate_variant(&colors, target),
                color_set(&case[variant_key]),
                "{index} {variant_key}"
            );
            assert_eq!(
                generate_version(&colors, target),
                color_set(&case[version_key]),
                "{index} {version_key}"
            );
        }
    }
}

#[test]
fn luminance_matches_the_desktop() {
    let vectors = vectors();
    for case in list(&vectors, "luminance") {
        let expected = case["luminance"].as_f64().expect("number");
        let actual = relative_luminance(color(&case["color"]));
        // `powf` and JavaScript's `**` may differ in the last bit; no shade depends on that.
        assert!((actual - expected).abs() <= 1e-15, "{}", case["color"]);
    }
}

#[test]
fn single_sets_become_the_same_two_versions() {
    let vectors = vectors();
    for (index, case) in list(&vectors, "singleSets").iter().enumerate() {
        let versions = from_single_set(&partial_set(&case["colors"]));
        assert_eq!(versions.dark, color_set(&case["dark"]), "{index}");
        assert_eq!(versions.light, color_set(&case["light"]), "{index}");
        assert_eq!(versions.generated, variant(&case["generated"]), "{index}");
    }
}

#[test]
fn names_are_cleaned_like_on_the_desktop() {
    let vectors = vectors();
    for case in list(&vectors, "cleanNames") {
        let name = case["name"].as_str().expect("name");
        assert_eq!(
            code::clean_name(name),
            case["clean"].as_str().expect("clean")
        );
    }
}

#[test]
fn codes_and_messages_are_the_desktops_byte_for_byte() {
    let vectors = vectors();
    let encoded = list(&vectors, "encoded");
    assert!(encoded.len() >= 30);
    for case in encoded {
        let theme = &case["theme"];
        let name = theme["name"].as_str().expect("name");
        let (dark, light) = (color_set(&theme["dark"]), color_set(&theme["light"]));
        let version = case["version"].as_str().expect("version");
        assert_eq!(
            code::encode(name, &dark, &light, version),
            case["code"].as_str().expect("code")
        );
        assert_eq!(
            code::share_message(name, &dark, &light, version),
            case["message"].as_str().expect("message")
        );
    }
}

#[test]
fn codes_are_read_like_on_the_desktop() {
    let vectors = vectors();
    let decoded = list(&vectors, "decoded");
    let mut accepted = 0;
    for (index, case) in decoded.iter().enumerate() {
        let code = case["code"].as_str().expect("code");
        let expected = &case["result"];
        match code::decode(code, "0.4.0") {
            Ok(theme) => {
                assert_eq!(expected["ok"], true, "{index} accepted");
                let shared = &expected["theme"];
                assert_eq!(
                    theme.name,
                    shared["name"].as_str().expect("name"),
                    "{index}"
                );
                assert_eq!(theme.versions.dark, color_set(&shared["dark"]), "{index}");
                assert_eq!(theme.versions.light, color_set(&shared["light"]), "{index}");
                assert_eq!(
                    theme.versions.generated,
                    variant(&shared["generated"]),
                    "{index}"
                );
                assert_eq!(theme.created_with, expected["createdWith"], "{index}");
                assert_eq!(theme.made_with_newer, expected["madeWithNewer"], "{index}");
                accepted += 1;
            }
            Err(error) => {
                assert_eq!(expected["ok"], false, "{index} refused");
                let kind = match error {
                    DecodeError::Invalid => "invalid",
                    DecodeError::Unreadable => "unreadable",
                };
                assert_eq!(expected["error"], kind, "{index}");
            }
        }
    }
    assert!(accepted >= 40 && decoded.len() - accepted >= 100);
}

#[test]
fn codes_are_found_in_texts_and_versions_compared_like_on_the_desktop() {
    let vectors = vectors();
    for case in list(&vectors, "found") {
        let text = case["text"].as_str().expect("text");
        assert_eq!(code::find_code(text), case["code"].as_str());
    }
    for case in list(&vectors, "newer") {
        let (version, current) = (case["version"].as_str(), case["current"].as_str());
        assert_eq!(
            code::is_newer_version(version.expect("version"), current.expect("current")),
            case["newer"].as_bool().expect("newer"),
            "{case}"
        );
    }
}

/// A small deterministic generator, so a failing input can be found again.
struct XorShift(u64);

impl XorShift {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, limit: usize) -> usize {
        (self.next() % limit as u64) as usize
    }
}

#[test]
fn malformed_codes_are_refused_without_panicking() {
    let mut random = XorShift(0x5eed_7e57_c0de);
    let valid = code::encode("Fuzz", &MUMCORD.dark, &MUMCORD.light, "0.9.0");
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_+/=. \"{}";
    let fragments = [
        "{",
        "}",
        "[",
        "]",
        "\"",
        ":",
        ",",
        "\"v\"",
        "\"n\"",
        "\"c\"",
        "\"dark\"",
        "\"light\"",
        "\"0.9.0\"",
        "\"#ffffff\"",
        "\"#000\"",
        "null",
        "true",
        "1e999",
        "-0",
        "\\u0000",
        "\\ud800",
        "\"background\"",
        "\"text\"",
        "\u{feff}",
        "é",
        "🎨",
    ];
    for _ in 0..20_000 {
        let code = match random.below(4) {
            // Random characters after the prefix.
            0 => {
                let length = random.below(MAX_CODE_LENGTH + 64);
                let body: String = (0..length)
                    .map(|_| char::from(alphabet[random.below(alphabet.len())]))
                    .collect();
                format!("mct1.{body}")
            }
            // A valid code with some characters changed, cut or doubled.
            1 => {
                let mut bytes = valid.clone().into_bytes();
                for _ in 0..=random.below(4) {
                    let at = 5 + random.below(bytes.len() - 5);
                    match random.below(3) {
                        0 => bytes[at] = alphabet[random.below(64)],
                        1 => bytes.truncate(at),
                        _ => bytes.insert(at, alphabet[random.below(64)]),
                    }
                    if bytes.len() <= 5 {
                        break;
                    }
                }
                String::from_utf8(bytes).expect("ASCII")
            }
            // Random JSON-ish payloads, properly encoded.
            2 => {
                let json: String = (0..random.below(80))
                    .map(|_| fragments[random.below(fragments.len())])
                    .collect();
                format!("mct1.{}", base64_url(json.as_bytes()))
            }
            // Random bytes, properly encoded.
            _ => {
                let bytes: Vec<u8> = (0..random.below(600))
                    .map(|_| random.next() as u8)
                    .collect();
                format!("mct1.{}", base64_url(&bytes))
            }
        };
        if let Ok(theme) = code::decode(&code, "0.9.0") {
            assert!(is_valid(&theme.versions.dark) && is_valid(&theme.versions.light));
            assert!(!theme.name.is_empty());
        }
        let _ = code::find_code(&code);
    }
    // Deep nesting and a huge text stay refused and harmless.
    let deep = format!("{}{}", "[".repeat(1000), "]".repeat(1000));
    assert_eq!(
        code::decode(&format!("mct1.{}", base64_url(deep.as_bytes())), "0.9.0"),
        Err(DecodeError::Invalid)
    );
    let huge = format!("mct1.{}", "A".repeat(1 << 20));
    assert_eq!(code::decode(&huge, "0.9.0"), Err(DecodeError::Invalid));
    assert_eq!(
        code::find_code(&huge).map(str::len),
        Some(5 + MAX_CODE_LENGTH)
    );
}

fn base64_url(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

// derive.test.ts

#[test]
fn presets_pass_aa_without_warnings_and_have_unique_ids() {
    for preset in &PRESETS {
        for (colors, base) in [
            (preset.dark, ThemeVariant::Dark),
            (preset.light, ThemeVariant::Light),
        ] {
            assert!(is_valid(&colors), "{}", preset.name);
            assert_eq!(key_issues(&colors), [], "{}", preset.name);
            assert_eq!(
                token_failures(&derive_tokens(&colors)),
                0,
                "{}",
                preset.name
            );
            assert_eq!(theme_base(&colors), base, "{}", preset.name);
        }
    }
    let mut ids: Vec<&str> = PRESETS.iter().map(|preset| preset.id).collect();
    ids.sort_unstable();
    ids.dedup();
    assert_eq!(ids.len(), PRESETS.len());
    assert!(ids.iter().all(|id| id.starts_with("preset:")));
}

#[test]
fn mix_interpolates_between_two_colours() {
    let (black, white) = (Rgb([0; 3]), Rgb([255; 3]));
    assert_eq!(mix(black, white, 0.0), black);
    assert_eq!(mix(black, white, 1.0), white);
    assert_eq!(mix(black, white, 0.5).hex(), "#808080");
}

#[test]
fn a_low_contrast_accent_is_adjusted_and_warned_about() {
    let mut colors = MUMCORD.dark;
    colors[Key::Accent] = Rgb([0x2a, 0x20, 0x60]);
    assert!(is_valid(&colors));
    assert_eq!(key_issues(&colors), [Key::Accent]);
    let tokens = derive_tokens(&colors);
    assert_ne!(tokens[Token::Accent], colors[Key::Accent]);
    assert!(contrast_ratio(tokens[Token::AccentText], tokens[Token::BgChat]) >= 4.5);
    assert_eq!(tokens[Token::PrimaryBg], tokens[Token::Accent]);
    assert!(contrast_ratio(tokens[Token::PrimaryText], tokens[Token::PrimaryBg]) >= 4.5);
    assert_eq!(token_failures(&tokens), 0);
}

#[test]
fn unreadable_text_is_refused() {
    let mut murky = MUMCORD.dark;
    murky[Key::Text] = Rgb([0x3a, 0x30, 0x70]);
    assert!(!is_valid(&murky));
    assert!(key_issues(&murky).contains(&Key::Text));
    let mut dark_surface = MUMCORD.light;
    dark_surface[Key::Surface] = Rgb([0x3d, 0x35, 0x66]);
    assert!(!is_valid(&dark_surface));
}

// versions.test.ts

#[test]
fn generated_versions_pass_aa_and_have_the_right_brightness() {
    for preset in &PRESETS {
        for (from, to) in [
            (preset.dark, ThemeVariant::Light),
            (preset.light, ThemeVariant::Dark),
        ] {
            let generated = generate_version(&from, to);
            assert_eq!(theme_base(&generated), to, "{}", preset.name);
            assert!(is_valid(&generated) && key_issues(&generated).is_empty());
        }
    }
    let mut red = MUMCORD.dark;
    red[Key::Accent] = Rgb([0xff, 0x30, 0x30]);
    let [r, g, b] = generate_version(&red, ThemeVariant::Light)[Key::Accent].0;
    assert!(r > g && r > b);
    let harsh = ColorSet(
        [
            0x000000, 0x000000, 0xffffff, 0x000080, 0x003300, 0x330000, 0x110000,
        ]
        .map(super::colors::rgb),
    );
    assert!(is_valid(&generate_version(&harsh, ThemeVariant::Light)));
}

#[test]
fn a_single_set_is_kept_as_the_version_matching_its_brightness() {
    let light = PartialSet(MUMCORD.light.0.map(Some));
    assert_eq!(variant_of(&light), ThemeVariant::Light);
    let versions = from_single_set(&light);
    assert_eq!(versions.light, MUMCORD.light);
    assert_eq!(versions.generated, Some(ThemeVariant::Dark));
    assert_eq!(theme_base(&versions.dark), ThemeVariant::Dark);
}

// shareCodec.test.ts

#[test]
fn a_code_round_trips_name_both_versions_and_version() {
    let ocean = find_preset("preset:ocean").expect("ocean");
    let code = code::encode("Nächtliche Ozeane 🌊", &ocean.dark, &ocean.light, "0.4.0");
    assert!(code.starts_with("mct1."));
    let theme = code::decode(&code, "0.4.0").expect("decoded");
    assert_eq!(theme.name, "Nächtliche Ozeane 🌊");
    assert_eq!(
        (theme.versions.dark, theme.versions.light),
        (ocean.dark, ocean.light)
    );
    assert_eq!(theme.versions.generated, None);
    assert_eq!(
        (theme.created_with.as_str(), theme.made_with_newer),
        ("0.4.0", false)
    );
    let newer = code::decode(&code, "0.3.9").expect("decoded");
    assert!(newer.made_with_newer);
}

#[test]
fn a_share_message_is_found_in_a_text() {
    let message = code::share_message("Deep Ocean", &MUMCORD.dark, &MUMCORD.light, "0.4.0");
    let code = code::encode("Deep Ocean", &MUMCORD.dark, &MUMCORD.light, "0.4.0");
    assert_eq!(message, format!("🎨 Mumcord theme \"Deep Ocean\" – {code}"));
    assert_eq!(
        code::find_code(&format!("look: {message} nice")),
        Some(code.as_str())
    );
    assert_eq!(code::find_code("no theme here, mct1. nothing"), None);
}

#[test]
fn hidden_characters_are_stripped_and_names_capped() {
    assert_eq!(code::clean_name("  Evil\u{202e}\u{0}Name  "), "EvilName");
    assert_eq!(code::clean_name(&"🎨".repeat(100)), "🎨".repeat(64));
}

#[test]
fn versions_compare_by_release_numbers() {
    assert!(code::is_newer_version("0.10.0", "0.9.9"));
    assert!(!code::is_newer_version("1.0.0", "1.0.0"));
    assert!(!code::is_newer_version("0.3.1", "0.4.0"));
    assert!(code::is_newer_version("0.5.0-beta.1", "0.4.0"));
    assert!(!code::is_newer_version("garbage", "0.4.0"));
}

// appTheme.test.ts and the store form

fn stored_map(set: &ColorSet) -> BTreeMap<String, String> {
    Key::ALL
        .iter()
        .map(|key| (key.name().to_owned(), set[*key].hex()))
        .collect()
}

#[test]
fn custom_themes_round_trip_through_the_store_form() {
    let mut accent = MUMCORD.dark;
    accent[Key::Accent] = Rgb([0xff, 0x00, 0xaa]);
    let mine = super::ThemeDef {
        id: "custom:1".into(),
        name: "Mine".into(),
        dark: accent,
        light: MUMCORD.light,
        generated: Some(ThemeVariant::Light),
    };
    let stored = to_stored(&mine);
    assert!(stored.colors.is_empty() && stored.light_generated && !stored.dark_generated);
    assert_eq!(stored.colors_dark, stored_map(&accent));
    assert_eq!(from_stored(&stored), mine);
}

#[test]
fn a_stored_single_set_theme_gets_its_other_version_generated() {
    let ocean = find_preset("preset:ocean").expect("ocean");
    let mut colors = stored_map(&ocean.dark);
    // Upper case and junk from an old or foreign writer.
    colors.insert("accent".into(), "#4CC3E6".into());
    colors.insert("glow".into(), "url(x)".into());
    let stored = mumcord_engine::store::Theme {
        id: "custom:old".into(),
        name: "Old".into(),
        colors,
        colors_dark: BTreeMap::new(),
        colors_light: BTreeMap::new(),
        light_generated: false,
        dark_generated: false,
    };
    let theme = from_stored(&stored);
    assert_eq!(theme.dark, ocean.dark);
    assert_eq!(theme.generated, Some(ThemeVariant::Light));
    assert_eq!(
        theme.light,
        generate_version(&ocean.dark, ThemeVariant::Light)
    );
}

fn state(app_theme_id: &str, server_themes: &[(i64, &str)]) -> ThemeState {
    let settings = mumcord_engine::Settings {
        app_theme_id: app_theme_id.into(),
        custom_themes: vec![to_stored(&super::ThemeDef {
            id: "custom:1".into(),
            name: "Mine".into(),
            dark: MUMCORD.dark,
            light: MUMCORD.light,
            generated: None,
        })],
        ..Default::default()
    };
    let ids: HashMap<i64, String> = server_themes
        .iter()
        .map(|(server, id)| (*server, (*id).to_owned()))
        .collect();
    ThemeState::new(&settings, ids)
}

#[test]
fn the_shown_theme_falls_back_like_on_the_desktop() {
    // The store's first-run default and old preset ids all mean Mumcord.
    for id in ["mumcord-dark", "preset:dark", "preset:light", "custom:gone"] {
        assert_eq!(
            state(id, &[]).settings().app_theme_id,
            "preset:mumcord",
            "{id}"
        );
    }
    let themes = state(
        "custom:1",
        &[(1, "preset:forest"), (2, "custom:gone"), (3, "preset:dark")],
    );
    let settings = themes.settings();
    assert_eq!(settings.app_theme_id, "custom:1");
    assert_eq!(
        settings.server_themes,
        HashMap::from([(1, "preset:forest".into()), (3, "preset:mumcord".into())])
    );
    assert_eq!(themes.shown(Some("preset:forest")).id, "preset:forest");
    assert_eq!(themes.shown(Some("custom:gone")).id, "custom:1");
    assert_eq!(themes.shown(None).id, "custom:1");
}

#[test]
fn mumcord_is_drawn_with_its_hand_tuned_sets_and_others_with_derived_shades() {
    let mumcord = PRESETS[0].def();
    let dark = super::tokens(&mumcord, ThemeVariant::Dark);
    assert_eq!(
        (dark.base, dark.bg_chat.as_str()),
        (ThemeVariant::Dark, "#1a1342")
    );
    assert_eq!(dark.primary_bg, "#ffd9c2");
    let light = super::tokens(&mumcord, ThemeVariant::Light);
    assert_eq!(
        (light.base, light.primary_bg.as_str()),
        (ThemeVariant::Light, "#17113a")
    );
    let forest = find_preset("preset:forest").expect("forest").def();
    let tokens = super::tokens(&forest, ThemeVariant::Light);
    assert_eq!(tokens.base, ThemeVariant::Light);
    assert_eq!(tokens.bg_chat, forest.light[Key::Background].hex());
    assert_eq!(tokens.text, forest.light[Key::Text].hex());
    assert_eq!(
        tokens.focus,
        derive_tokens(&forest.light)[Token::Focus].hex()
    );
}

#[test]
fn mumcords_token_sets_match_the_design_tokens_of_the_apps() {
    let design: Value =
        serde_json::from_str(include_str!("../../../design/tokens.json")).expect("tokens.json");
    for (name, set) in [
        ("dark", MUMCORD_DARK_TOKENS),
        ("light", MUMCORD_LIGHT_TOKENS),
    ] {
        let colors = &design["color"][name];
        for (index, token) in Token::NAMES.iter().enumerate() {
            assert_eq!(
                set.0[index].hex(),
                colors[*token].as_str().expect(token),
                "{name} {token}"
            );
        }
    }
}
