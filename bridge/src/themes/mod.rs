// SPDX-License-Identifier: AGPL-3.0-only

//! Themes (FR-5.81, Spec 02 FR-2.14 … FR-2.17): the appearance mode, the app theme, the
//! user's own themes, theme codes and the theme a server is shown in.
//!
//! The logic is the desktop app's (`ui/src/theme/`), ported step by step: the same key colours
//! give the same shades on both, and a theme code made on one opens on the other. The test
//! vectors in `vectors.json` come from the desktop code itself (`scripts/theme-vectors.mjs`).
//! Everything is stored in the engine's settings, in the desktop's form.

mod code;
mod colors;
mod derive;
mod model;
#[cfg(test)]
mod tests;

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};

use mumcord_engine::Settings;
use mumcord_engine::store::{Theme as StoredTheme, ThemePreference};

use crate::engine::{MumcordEngine, Saved};
use crate::error::BridgeError;
use crate::events::{BridgeEvent, lock};

use colors::{ColorSet, Key, PartialSet, Rgb};
use derive::Versions;
use model::{
    MUMCORD_DARK_TOKENS, MUMCORD_ID, MUMCORD_LIGHT_TOKENS, PRESETS, ThemeDef, Token, find_preset,
    is_preset_id,
};

const CUSTOM_ID_PREFIX: &str = "custom:";

/// Shared by the engine object and its commands.
pub(crate) type SharedThemes = Arc<Mutex<ThemeState>>;

/// The Dark / Light / System switch: which version of the shown theme the app draws.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ThemeMode {
    /// Follows the phone's dark mode setting.
    System,
    Dark,
    Light,
}

/// One of the two versions every theme has.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ThemeVariant {
    Dark,
    Light,
}

impl ThemeVariant {
    pub(crate) fn other(self) -> Self {
        match self {
            Self::Dark => Self::Light,
            Self::Light => Self::Dark,
        }
    }
}

/// The seven key colours of one version, each `#rrggbb` in lower case.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeColors {
    pub background: String,
    pub surface: String,
    pub text: String,
    pub accent: String,
    /// Talking and online.
    pub talking: String,
    pub warning: String,
    pub error: String,
}

/// A theme of the gallery: built in, or one of the user's own (imported).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Theme {
    /// `preset:…` for the built-in themes, `custom:…` for the user's own.
    pub id: String,
    /// Plain text from whoever made the theme, at most 64 visible characters.
    pub name: String,
    /// Built-in themes cannot be deleted.
    pub built_in: bool,
    pub dark: ThemeColors,
    pub light: ThemeColors,
}

/// What the theme screens and the app's look follow. Arrives again as
/// [`BridgeEvent::ThemesChanged`] after every change.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeSettings {
    pub mode: ThemeMode,
    /// The theme of the whole app; always one of the built-in or custom themes.
    pub app_theme_id: String,
    /// The user's own themes, in the order they were added.
    pub custom_themes: Vec<Theme>,
    /// Saved server id → the theme the user picked for that server; a server without an entry
    /// is shown in the app theme.
    pub server_themes: HashMap<i64, String>,
}

/// The colours the app draws with: the desktop's `--color-*` tokens of one version of a theme.
/// Each is `#rrggbb` in lower case and meets WCAG AA where the desktop checks it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeTokens {
    /// The theme these come from.
    pub theme_id: String,
    pub variant: ThemeVariant,
    /// The app's own dark or light colour set that the values not listed here (avatar colours,
    /// shadows, the backdrop) come from: by the brightness of the theme's colours.
    pub base: ThemeVariant,
    pub bg_servers: String,
    pub bg_sidebar: String,
    pub bg_chat: String,
    pub bg_elevated: String,
    pub bg_hover: String,
    pub bg_selected: String,
    pub text: String,
    pub text_secondary: String,
    pub text_muted: String,
    pub accent: String,
    pub on_accent: String,
    pub accent_text: String,
    pub success: String,
    pub warning: String,
    pub danger: String,
    pub local_muted: String,
    pub talking: String,
    pub accent_stop_1: String,
    pub accent_stop_2: String,
    pub accent_stop_3: String,
    pub primary_bg: String,
    pub primary_text: String,
    pub border: String,
    pub border_strong: String,
    pub focus: String,
}

/// A theme code read from a pasted text, before it is imported (the preview).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeCodePreview {
    pub name: String,
    pub dark: ThemeColors,
    pub light: ThemeColors,
    /// The Mumcord version that made the theme.
    pub created_with: String,
    /// Made by a newer Mumcord: colours this version does not know were left out ("Made with
    /// Mumcord ‹version› – update to see it fully").
    pub made_with_newer: bool,
    /// The custom theme with the same name and colours, when it is already imported.
    pub imported_id: Option<String>,
}

/// A theme ready to share.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ThemeShare {
    /// The bare `mct1.` code.
    pub code: String,
    /// `🎨 Mumcord theme "‹name›" – mct1.…`: what the share sheet sends. Mumcord shows it as a
    /// preview card, other Mumble clients as plain text.
    pub message: String,
}

/// Why a pasted text was not accepted as a theme.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum ThemeCodeError {
    /// No theme code, or one that is damaged or carries more than colours.
    #[error("this is not a valid theme code")]
    Invalid,
    /// A theme code whose text would not be readable (below WCAG AA contrast).
    #[error("the text of this theme would not be readable")]
    Unreadable,
}

impl From<code::DecodeError> for ThemeCodeError {
    fn from(error: code::DecodeError) -> Self {
        match error {
            code::DecodeError::Invalid => Self::Invalid,
            code::DecodeError::Unreadable => Self::Unreadable,
        }
    }
}

impl From<ThemeCodeError> for BridgeError {
    fn from(error: ThemeCodeError) -> Self {
        Self::invalid(&error.to_string())
    }
}

/// The themes as last saved, in the desktop's form, and the theme ids of the saved servers as
/// the engine keeps them (possibly naming a theme that was deleted since).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ThemeState {
    mode: ThemeMode,
    app_theme_id: String,
    custom: Vec<ThemeDef>,
    server_theme_ids: HashMap<i64, String>,
}

impl ThemeState {
    pub(crate) fn new(settings: &Settings, server_theme_ids: HashMap<i64, String>) -> Self {
        Self {
            mode: match settings.theme {
                ThemePreference::System => ThemeMode::System,
                ThemePreference::Dark => ThemeMode::Dark,
                ThemePreference::Light => ThemeMode::Light,
            },
            app_theme_id: settings.app_theme_id.clone(),
            custom: settings.custom_themes.iter().map(from_stored).collect(),
            server_theme_ids,
        }
    }

    fn with_settings(&self, settings: &Settings) -> Self {
        Self::new(settings, self.server_theme_ids.clone())
    }

    fn find(&self, id: &str) -> Option<ThemeDef> {
        find_theme(id, &self.custom)
    }

    /// The theme a server or the app is shown in: the given theme while it exists, otherwise
    /// the app theme, otherwise Mumcord.
    fn shown(&self, theme_id: Option<&str>) -> ThemeDef {
        theme_id
            .and_then(|id| self.find(id))
            .or_else(|| self.find(&self.app_theme_id))
            .unwrap_or_else(|| PRESETS[0].def())
    }

    fn settings(&self) -> ThemeSettings {
        ThemeSettings {
            mode: self.mode,
            app_theme_id: self.shown(None).id,
            custom_themes: self.custom.iter().map(theme).collect(),
            server_themes: self
                .server_theme_ids
                .iter()
                .filter_map(|(server, id)| Some((*server, self.find(id)?.id)))
                .collect(),
        }
    }
}

impl Saved {
    /// Takes over the themes as they were just saved and reports them when they changed.
    pub(crate) fn set_themes(&self, themes: ThemeState) {
        let mut current = lock(&self.themes);
        let before = current.settings();
        *current = themes;
        let settings = current.settings();
        drop(current);
        if settings != before {
            // The event thread is gone only while the engine object is being released.
            let _ = self.events.send(BridgeEvent::ThemesChanged { settings });
        }
    }

    /// Takes over the servers' theme ids after the server list was read again.
    pub(crate) fn set_server_themes(&self, server_theme_ids: HashMap<i64, String>) {
        let themes = ThemeState {
            server_theme_ids,
            ..lock(&self.themes).clone()
        };
        self.set_themes(themes);
    }
}

fn unknown_theme() -> BridgeError {
    BridgeError::NotFound {
        detail: "unknown theme".to_owned(),
    }
}

fn find_theme(id: &str, custom: &[ThemeDef]) -> Option<ThemeDef> {
    find_preset(id)
        .map(model::Preset::def)
        .or_else(|| custom.iter().find(|theme| theme.id == id).cloned())
}

/// The custom theme with the same name and colours, so importing twice adds it only once.
fn find_imported<'a>(decoded: &code::Decoded, custom: &'a [ThemeDef]) -> Option<&'a ThemeDef> {
    custom.iter().find(|theme| {
        theme.name == decoded.name
            && theme.dark == decoded.versions.dark
            && theme.light == decoded.versions.light
    })
}

fn colors(set: &ColorSet) -> ThemeColors {
    let hex = |key: Key| set[key].hex();
    ThemeColors {
        background: hex(Key::Background),
        surface: hex(Key::Surface),
        text: hex(Key::Text),
        accent: hex(Key::Accent),
        talking: hex(Key::Talking),
        warning: hex(Key::Warning),
        error: hex(Key::Error),
    }
}

fn theme(def: &ThemeDef) -> Theme {
    Theme {
        id: def.id.clone(),
        name: def.name.clone(),
        built_in: is_preset_id(&def.id),
        dark: colors(&def.dark),
        light: colors(&def.light),
    }
}

/// The valid colours of a stored set; the store keeps whatever was written.
fn stored_colors(raw: &BTreeMap<String, String>) -> PartialSet {
    let mut set = PartialSet::default();
    for key in Key::ALL {
        set.0[key as usize] = raw
            .get(key.name())
            .and_then(|value| Rgb::parse(&value.to_lowercase()));
    }
    set
}

/// A stored custom theme as a full theme. A theme saved with one set (before themes had two
/// versions) gets its other version generated, and missing colours come from Mumcord's matching
/// version, as on the desktop.
fn from_stored(stored: &StoredTheme) -> ThemeDef {
    let dark = stored_colors(&stored.colors_dark);
    let light = stored_colors(&stored.colors_light);
    let generated = if stored.light_generated {
        Some(ThemeVariant::Light)
    } else if stored.dark_generated {
        Some(ThemeVariant::Dark)
    } else {
        None
    };
    let Versions {
        dark,
        light,
        generated,
    } = if dark.is_empty() && light.is_empty() {
        derive::from_single_set(&stored_colors(&stored.colors))
    } else {
        derive::complete_versions(&dark, &light, generated)
    };
    ThemeDef {
        id: stored.id.clone(),
        name: stored.name.clone(),
        dark,
        light,
        generated,
    }
}

fn to_stored(def: &ThemeDef) -> StoredTheme {
    let map = |set: &ColorSet| {
        Key::ALL
            .iter()
            .map(|key| (key.name().to_owned(), set[*key].hex()))
            .collect()
    };
    StoredTheme {
        id: def.id.clone(),
        name: def.name.clone(),
        colors: BTreeMap::new(),
        colors_dark: map(&def.dark),
        colors_light: map(&def.light),
        light_generated: def.generated == Some(ThemeVariant::Light),
        dark_generated: def.generated == Some(ThemeVariant::Dark),
    }
}

fn tokens(def: &ThemeDef, variant: ThemeVariant) -> ThemeTokens {
    let colors = def.version(variant);
    // Mumcord is drawn with its hand-tuned sets, every other theme with derived shades.
    let (base, set) = if def.id == MUMCORD_ID {
        let set = match variant {
            ThemeVariant::Dark => MUMCORD_DARK_TOKENS,
            ThemeVariant::Light => MUMCORD_LIGHT_TOKENS,
        };
        (variant, set)
    } else {
        (derive::theme_base(&colors), derive::derive_tokens(&colors))
    };
    let hex = |token: Token| set[token].hex();
    ThemeTokens {
        theme_id: def.id.clone(),
        variant,
        base,
        bg_servers: hex(Token::BgServers),
        bg_sidebar: hex(Token::BgSidebar),
        bg_chat: hex(Token::BgChat),
        bg_elevated: hex(Token::BgElevated),
        bg_hover: hex(Token::BgHover),
        bg_selected: hex(Token::BgSelected),
        text: hex(Token::Text),
        text_secondary: hex(Token::TextSecondary),
        text_muted: hex(Token::TextMuted),
        accent: hex(Token::Accent),
        on_accent: hex(Token::OnAccent),
        accent_text: hex(Token::AccentText),
        success: hex(Token::Success),
        warning: hex(Token::Warning),
        danger: hex(Token::Danger),
        local_muted: hex(Token::LocalMuted),
        talking: hex(Token::Talking),
        accent_stop_1: hex(Token::AccentStop1),
        accent_stop_2: hex(Token::AccentStop2),
        accent_stop_3: hex(Token::AccentStop3),
        primary_bg: hex(Token::PrimaryBg),
        primary_text: hex(Token::PrimaryText),
        border: hex(Token::Border),
        border_strong: hex(Token::BorderStrong),
        focus: hex(Token::Focus),
    }
}

/// The Mumcord version written into theme codes; a code from a newer one gets the update hint.
fn mumcord_version() -> &'static str {
    env!("MUMCORD_ENGINE_VERSION")
}

fn decode(text: &str) -> Result<code::Decoded, ThemeCodeError> {
    let found = code::find_code(text).ok_or(ThemeCodeError::Invalid)?;
    Ok(code::decode(found, mumcord_version())?)
}

/// `custom:` and a random UUID, as the desktop names the themes it saves.
fn new_custom_id() -> Result<String, BridgeError> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes)
        .map_err(|e| BridgeError::internal(format!("no random numbers: {e}")))?;
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    Ok(format!(
        "{CUSTOM_ID_PREFIX}{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    ))
}

impl MumcordEngine {
    /// Changes the saved settings under the gate and reports the themes afterwards.
    async fn change_themes<T: Send + 'static>(
        &self,
        change: impl FnOnce(&mut Settings, &ThemeState) -> Result<T, BridgeError> + Send + 'static,
    ) -> Result<T, BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let mut all = engine.settings()?;
            let state = lock(&saved.themes).with_settings(&all);
            let result = change(&mut all, &state)?;
            let all = engine.update_settings(all).await?;
            let themes = lock(&saved.themes).with_settings(&all);
            saved.set_themes(themes);
            Ok(result)
        })
        .await
    }
}

#[uniffi::export]
impl MumcordEngine {
    /// The appearance mode, the app theme, the user's own themes and the servers' themes.
    pub fn theme_settings(&self) -> ThemeSettings {
        lock(&self.saved.themes).settings()
    }

    /// The themes that come with the app: Mumcord first, then the curated ones.
    pub fn built_in_themes(&self) -> Vec<Theme> {
        PRESETS.iter().map(|preset| theme(&preset.def())).collect()
    }

    /// The colours to draw with: of the theme `theme_id` (a server's theme), or of the app theme
    /// for `None` or an id that names no theme (any longer), in `variant`. The app picks the
    /// variant from [`ThemeSettings::mode`] and, for `System`, the phone's dark mode.
    pub fn theme_tokens(&self, theme_id: Option<String>, variant: ThemeVariant) -> ThemeTokens {
        tokens(
            &lock(&self.saved.themes).shown(theme_id.as_deref()),
            variant,
        )
    }

    /// Saves the appearance mode; arrives as [`BridgeEvent::ThemesChanged`].
    pub async fn set_theme_mode(&self, mode: ThemeMode) -> Result<(), BridgeError> {
        self.change_themes(move |settings, _| {
            settings.theme = match mode {
                ThemeMode::System => ThemePreference::System,
                ThemeMode::Dark => ThemePreference::Dark,
                ThemeMode::Light => ThemePreference::Light,
            };
            Ok(())
        })
        .await
    }

    /// Makes `theme_id` the theme of the whole app. Fails with `NotFound` for an id that names
    /// no theme.
    pub async fn set_app_theme(&self, theme_id: String) -> Result<(), BridgeError> {
        self.change_themes(move |settings, themes| {
            let theme = themes.find(&theme_id).ok_or_else(unknown_theme)?;
            settings.app_theme_id = theme.id;
            Ok(())
        })
        .await
    }

    /// Reads the first theme code in `text` (a pasted share message or a bare code) for a
    /// preview, without importing it.
    pub fn theme_code_preview(&self, text: String) -> Result<ThemeCodePreview, ThemeCodeError> {
        let decoded = decode(&text)?;
        let imported_id =
            find_imported(&decoded, &lock(&self.saved.themes).custom).map(|theme| theme.id.clone());
        Ok(ThemeCodePreview {
            name: decoded.name,
            dark: colors(&decoded.versions.dark),
            light: colors(&decoded.versions.light),
            created_with: decoded.created_with,
            made_with_newer: decoded.made_with_newer,
            imported_id,
        })
    }

    /// Imports the first theme code in `text` into the user's own themes and returns it. A
    /// theme with the same name and colours is not added twice; that one is returned instead.
    /// The app theme stays: [`MumcordEngine::set_app_theme`] applies it. Fails with
    /// `InvalidInput` for a text that [`MumcordEngine::theme_code_preview`] refuses.
    pub async fn import_theme_code(&self, text: String) -> Result<Theme, BridgeError> {
        let decoded = decode(&text)?;
        let id = new_custom_id()?;
        self.change_themes(move |settings, themes| {
            if let Some(existing) = find_imported(&decoded, &themes.custom) {
                return Ok(theme(existing));
            }
            let Versions {
                dark,
                light,
                generated,
            } = decoded.versions;
            let def = ThemeDef {
                id,
                name: decoded.name,
                dark,
                light,
                generated,
            };
            settings.custom_themes.push(to_stored(&def));
            Ok(theme(&def))
        })
        .await
    }

    /// The code and share message of a theme, built in or the user's own. Fails with `NotFound`
    /// for an id that names no theme.
    pub fn theme_share(&self, theme_id: String) -> Result<ThemeShare, BridgeError> {
        let theme = lock(&self.saved.themes)
            .find(&theme_id)
            .ok_or_else(unknown_theme)?;
        let version = mumcord_version();
        Ok(ThemeShare {
            code: code::encode(&theme.name, &theme.dark, &theme.light, version),
            message: code::share_message(&theme.name, &theme.dark, &theme.light, version),
        })
    }

    /// Deletes one of the user's own themes. When it was the app theme, the app goes back to
    /// Mumcord; servers shown in it go back to the app theme. Fails with `InvalidInput` for a
    /// built-in theme and `NotFound` for an unknown id.
    pub async fn delete_custom_theme(&self, theme_id: String) -> Result<(), BridgeError> {
        self.change_themes(move |settings, themes| {
            if is_preset_id(&theme_id) {
                return Err(BridgeError::invalid("built-in themes cannot be deleted"));
            }
            if !themes.custom.iter().any(|theme| theme.id == theme_id) {
                return Err(unknown_theme());
            }
            settings.custom_themes.retain(|theme| theme.id != theme_id);
            if settings.app_theme_id == theme_id {
                settings.app_theme_id = MUMCORD_ID.to_owned();
            }
            Ok(())
        })
        .await
    }

    /// Sets the theme a saved server is shown in; `None` shows it in the app theme. Fails with
    /// `NotFound` for an unknown server or theme. Arrives as [`BridgeEvent::ThemesChanged`].
    pub async fn set_server_theme(
        &self,
        server_id: i64,
        theme_id: Option<String>,
    ) -> Result<(), BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let theme_id = match theme_id {
                Some(id) => Some(lock(&saved.themes).find(&id).ok_or_else(unknown_theme)?.id),
                None => None,
            };
            let server = engine.server_set_theme(server_id, theme_id).await?;
            let mut ids = lock(&saved.themes).server_theme_ids.clone();
            match server.theme_id {
                Some(id) => ids.insert(server_id, id),
                None => ids.remove(&server_id),
            };
            saved.set_server_themes(ids);
            Ok(())
        })
        .await
    }
}

/// The theme ids the saved servers name, from the engine's server list.
pub(crate) fn server_theme_ids(servers: &[mumcord_engine::ServerView]) -> HashMap<i64, String> {
    servers
        .iter()
        .filter_map(|server| Some((server.id, server.theme_id.clone()?)))
        .collect()
}
