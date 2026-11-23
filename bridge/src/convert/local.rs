// SPDX-License-Identifier: AGPL-3.0-only

//! What is kept on the phone itself: identities, the phone settings, the public server list.

use mumcord_engine::public_servers::PublicServer as EnginePublicServer;
use mumcord_engine::store::{
    DefaultOutput as StoreDefaultOutput, MobileSettings as StoreMobileSettings, NotificationMode,
};
use mumcord_engine::{IdentityView, Settings};

use crate::error::BridgeError;
use crate::types::{AppSettings, DefaultOutput, Identity, MobileSettings, PublicServer};

/// The 24 languages of the desktop translations (the official EU languages), ISO 639-1.
const LANGUAGES: [&str; 24] = [
    "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "ga", "hr", "hu", "it", "lt", "lv",
    "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv",
];

pub(crate) fn identity(view: IdentityView) -> Identity {
    Identity {
        id: view.id,
        label: view.label,
        common_name: view.name,
        fingerprint: view.cert_hash,
        created_ms: view.added_at,
        is_default: view.is_default,
        server_ids: view.server_ids,
    }
}

pub(crate) fn mobile_settings(settings: &StoreMobileSettings) -> MobileSettings {
    MobileSettings {
        talk_mode_chosen: settings.talk_mode_chosen,
        default_output: match settings.default_output {
            StoreDefaultOutput::Ask => DefaultOutput::Ask,
            StoreDefaultOutput::Speaker => DefaultOutput::Speaker,
            StoreDefaultOutput::Earpiece => DefaultOutput::Earpiece,
        },
        mobile_data_autoplay_streams: settings.mobile_data_autoplay_streams,
        mobile_data_load_media: settings.mobile_data_load_media,
        mobile_data_reduced_voice: settings.mobile_data_reduced_voice,
        idle_disconnect: settings.idle_disconnect,
        notify_sender_only: settings.notify_sender_only,
        update_check: settings.update_check,
    }
}

/// The settings to save for what the app handed in. A talk mode that was chosen once stays
/// chosen: only `set_talk_mode` decides that, and an app working on an older copy of the
/// settings must not undo it.
pub(crate) fn store_mobile_settings(
    settings: MobileSettings,
    saved: &StoreMobileSettings,
) -> StoreMobileSettings {
    StoreMobileSettings {
        talk_mode_chosen: saved.talk_mode_chosen || settings.talk_mode_chosen,
        default_output: match settings.default_output {
            DefaultOutput::Ask => StoreDefaultOutput::Ask,
            DefaultOutput::Speaker => StoreDefaultOutput::Speaker,
            DefaultOutput::Earpiece => StoreDefaultOutput::Earpiece,
        },
        mobile_data_autoplay_streams: settings.mobile_data_autoplay_streams,
        mobile_data_load_media: settings.mobile_data_load_media,
        mobile_data_reduced_voice: settings.mobile_data_reduced_voice,
        idle_disconnect: settings.idle_disconnect,
        notify_sender_only: settings.notify_sender_only,
        update_check: settings.update_check,
    }
}

pub(crate) fn app_settings(settings: &Settings) -> AppSettings {
    AppSettings {
        language: settings.language.clone(),
        link_previews: settings.link_previews,
        history_max_age_days: settings.history_max_age_days,
        notify_private_messages: settings.notifications != NotificationMode::Off,
        notify_mentions: settings.sounds.mention_notify,
    }
}

/// `saved` with what the app handed in. The phone has one kind of notification, the system's:
/// private messages map to the desktop's message notifications, which are switched on as
/// "both" (toasts and system notifications) when they were off and are kept otherwise.
pub(crate) fn store_app_settings(
    settings: AppSettings,
    mut saved: Settings,
) -> Result<Settings, BridgeError> {
    if let Some(language) = &settings.language
        && !LANGUAGES.contains(&language.as_str())
    {
        return Err(BridgeError::invalid("unknown language"));
    }
    saved.language = settings.language;
    saved.link_previews = settings.link_previews;
    saved.history_max_age_days = settings.history_max_age_days;
    if !settings.notify_private_messages {
        saved.notifications = NotificationMode::Off;
    } else if saved.notifications == NotificationMode::Off {
        saved.notifications = NotificationMode::Both;
    }
    saved.sounds.mention_notify = settings.notify_mentions;
    Ok(saved)
}

pub(crate) fn public_server(server: &EnginePublicServer) -> PublicServer {
    PublicServer {
        name: server.name.clone(),
        host: server.host.clone(),
        port: server.port,
        country: server.country.clone(),
        country_code: server.country_code.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_phone_settings_start_with_the_defaults_of_the_specification() {
        assert_eq!(
            mobile_settings(&StoreMobileSettings::default()),
            MobileSettings {
                talk_mode_chosen: false,
                default_output: DefaultOutput::Ask,
                mobile_data_autoplay_streams: false,
                mobile_data_load_media: true,
                mobile_data_reduced_voice: false,
                idle_disconnect: false,
                notify_sender_only: false,
                update_check: true,
            }
        );
    }

    #[test]
    fn every_phone_setting_maps_both_ways() {
        let saved = StoreMobileSettings::default();
        for output in [
            DefaultOutput::Ask,
            DefaultOutput::Speaker,
            DefaultOutput::Earpiece,
        ] {
            let settings = MobileSettings {
                talk_mode_chosen: true,
                default_output: output,
                mobile_data_autoplay_streams: true,
                mobile_data_load_media: false,
                mobile_data_reduced_voice: true,
                idle_disconnect: true,
                notify_sender_only: true,
                update_check: false,
            };
            assert_eq!(
                mobile_settings(&store_mobile_settings(settings, &saved)),
                settings
            );
        }
    }

    fn app(language: Option<&str>) -> AppSettings {
        AppSettings {
            language: language.map(str::to_owned),
            link_previews: false,
            history_max_age_days: Some(30),
            notify_private_messages: false,
            notify_mentions: false,
        }
    }

    #[test]
    fn the_app_settings_map_both_ways() {
        let saved = Settings::default();
        for settings in [
            app(Some("de")),
            app(None),
            AppSettings {
                link_previews: true,
                history_max_age_days: None,
                notify_private_messages: true,
                notify_mentions: true,
                ..app(Some("ga"))
            },
        ] {
            let stored = store_app_settings(settings.clone(), saved.clone()).expect("store");
            assert_eq!(app_settings(&stored), settings);
        }
    }

    #[test]
    fn switching_private_messages_on_keeps_a_chosen_desktop_mode() {
        let saved = Settings {
            notifications: NotificationMode::InApp,
            ..Settings::default()
        };
        let on = AppSettings {
            notify_private_messages: true,
            ..app(None)
        };
        let stored = store_app_settings(on.clone(), saved).expect("store");
        assert_eq!(stored.notifications, NotificationMode::InApp);
        let off = store_app_settings(app(None), stored).expect("store");
        assert_eq!(off.notifications, NotificationMode::Off);
        let again = store_app_settings(on, off).expect("store");
        assert_eq!(again.notifications, NotificationMode::Both);
    }

    #[test]
    fn unknown_languages_are_refused() {
        for language in ["xx", "DE", "de-AT", ""] {
            let refused = store_app_settings(app(Some(language)), Settings::default());
            assert!(
                matches!(refused, Err(BridgeError::InvalidInput { .. })),
                "{language}"
            );
        }
    }

    #[test]
    fn a_chosen_talk_mode_is_never_taken_back_by_the_settings() {
        let saved = StoreMobileSettings {
            talk_mode_chosen: true,
            ..StoreMobileSettings::default()
        };
        let stale = mobile_settings(&StoreMobileSettings::default());
        assert!(store_mobile_settings(stale, &saved).talk_mode_chosen);
    }
}
