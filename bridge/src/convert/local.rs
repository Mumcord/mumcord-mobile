// SPDX-License-Identifier: AGPL-3.0-only

//! What is kept on the phone itself: identities, the phone settings, the public server list.

use mumcord_engine::IdentityView;
use mumcord_engine::public_servers::PublicServer as EnginePublicServer;
use mumcord_engine::store::{
    DefaultOutput as StoreDefaultOutput, MobileSettings as StoreMobileSettings,
};

use crate::types::{DefaultOutput, Identity, MobileSettings, PublicServer};

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
