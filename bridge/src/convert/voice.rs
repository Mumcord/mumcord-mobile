// SPDX-License-Identifier: AGPL-3.0-only

//! Voice: whisper targets, who may be heard, and the audio configuration.

use std::collections::HashMap;

use mumcord_audio::io::ExternalFormat;
use mumcord_engine::whisper::WhisperDenied;
use mumcord_engine::{EchoCancellationMode, UserView};
use mumcord_store::WhisperReceivers;

use crate::error::BridgeError;
use crate::types::{AudioConfig, EchoCancellation};

/// The layout the engine gets when the app names none: what the engine works in itself.
const DEFAULT_FORMAT: ExternalFormat = ExternalFormat {
    sample_rate: 48_000,
    channels: 1,
};

/// Capture and playback layout for the engine.
pub(crate) fn audio_formats(config: Option<AudioConfig>) -> (ExternalFormat, ExternalFormat) {
    config.map_or((DEFAULT_FORMAT, DEFAULT_FORMAT), |config| {
        (
            ExternalFormat {
                sample_rate: config.capture_rate,
                channels: config.capture_channels,
            },
            ExternalFormat {
                sample_rate: config.playback_rate,
                channels: config.playback_channels,
            },
        )
    })
}

pub(crate) fn echo_cancellation(mode: EchoCancellationMode) -> EchoCancellation {
    match mode {
        EchoCancellationMode::System => EchoCancellation::System,
        EchoCancellationMode::Engine => EchoCancellation::Engine,
    }
}

/// Whether the server passes this person's voice on, so the microphone is worth opening.
pub(crate) fn may_send(user: &UserView) -> bool {
    !(user.self_mute || user.self_deaf || user.server_mute || user.server_deaf || user.suppressed)
}

/// The engine's whisper receivers for one person. The engine finds people by name.
pub(crate) fn whisper_person(name: Option<String>) -> Result<WhisperReceivers, BridgeError> {
    let name = name.ok_or_else(|| not_found("unknown person"))?;
    Ok(WhisperReceivers::People { names: vec![name] })
}

/// The engine's whisper receivers for the people in one channel. The engine finds a channel by
/// its names from below the root down; `channels` maps an id to its parent and name.
pub(crate) fn whisper_channel(
    channel_id: u32,
    channels: &HashMap<u32, (Option<u32>, String)>,
) -> Result<WhisperReceivers, BridgeError> {
    let mut path = Vec::new();
    let mut next = Some(channel_id);
    while let Some(id) = next {
        let (parent, name) = channels
            .get(&id)
            .ok_or_else(|| not_found("unknown channel"))?;
        // The server's data is untrusted: a loop of parents must not hang the command.
        if path.len() > channels.len() {
            return Err(not_found("unknown channel"));
        }
        if parent.is_some() {
            path.push(name.clone());
        }
        next = *parent;
    }
    path.reverse();
    Ok(WhisperReceivers::Channel {
        path,
        children: false,
        links: false,
        group: None,
    })
}

fn not_found(detail: &str) -> BridgeError {
    BridgeError::NotFound {
        detail: detail.to_owned(),
    }
}

impl From<WhisperDenied> for BridgeError {
    fn from(denied: WhisperDenied) -> Self {
        let detail = denied.to_string();
        match denied {
            WhisperDenied::NoVoiceServer => Self::NotConnected { detail },
            WhisperDenied::Muted => Self::InvalidInput { detail },
            WhisperDenied::ServerMuted | WhisperDenied::NoPermission { .. } => {
                Self::PermissionDenied { detail }
            }
            WhisperDenied::NobodyHere | WhisperDenied::ChannelNotFound => Self::NotFound { detail },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn channels() -> HashMap<u32, (Option<u32>, String)> {
        HashMap::from([
            (0, (None, "Root".to_owned())),
            (1, (Some(0), "Lobby".to_owned())),
            (2, (Some(1), "Corner".to_owned())),
        ])
    }

    #[test]
    fn a_person_is_whispered_to_by_name() {
        assert_eq!(
            whisper_person(Some("ada".to_owned())),
            Ok(WhisperReceivers::People {
                names: vec!["ada".to_owned()]
            })
        );
        assert!(matches!(
            whisper_person(None),
            Err(BridgeError::NotFound { .. })
        ));
    }

    #[test]
    fn a_channel_is_shouted_to_by_its_path_below_the_root() {
        let path_of = |id| match whisper_channel(id, &channels()) {
            Ok(WhisperReceivers::Channel {
                path,
                children: false,
                links: false,
                group: None,
            }) => path,
            other => panic!("{other:?}"),
        };
        assert_eq!(path_of(2), ["Lobby", "Corner"]);
        assert_eq!(path_of(1), ["Lobby"]);
        assert_eq!(path_of(0), [""; 0]);
        assert!(matches!(
            whisper_channel(9, &channels()),
            Err(BridgeError::NotFound { .. })
        ));
    }

    #[test]
    fn a_loop_of_parents_is_refused() {
        let looped = HashMap::from([
            (1, (Some(2), "a".to_owned())),
            (2, (Some(1), "b".to_owned())),
        ]);
        assert!(matches!(
            whisper_channel(1, &looped),
            Err(BridgeError::NotFound { .. })
        ));
    }

    #[test]
    fn refusals_keep_their_kind() {
        let kind = |denied: WhisperDenied| BridgeError::from(denied);
        assert!(matches!(
            kind(WhisperDenied::NoVoiceServer),
            BridgeError::NotConnected { .. }
        ));
        assert!(matches!(
            kind(WhisperDenied::Muted),
            BridgeError::InvalidInput { .. }
        ));
        assert!(matches!(
            kind(WhisperDenied::NoPermission {
                channel: "Lobby".to_owned()
            }),
            BridgeError::PermissionDenied { .. }
        ));
        assert!(matches!(
            kind(WhisperDenied::NobodyHere),
            BridgeError::NotFound { .. }
        ));
    }

    #[test]
    fn the_audio_layout_defaults_to_48_khz_mono() {
        assert_eq!(audio_formats(None), (DEFAULT_FORMAT, DEFAULT_FORMAT));
        let (capture, playback) = audio_formats(Some(AudioConfig {
            capture_rate: 44_100,
            capture_channels: 1,
            playback_rate: 48_000,
            playback_channels: 2,
        }));
        assert_eq!((capture.sample_rate, capture.channels), (44_100, 1));
        assert_eq!((playback.sample_rate, playback.channels), (48_000, 2));
    }
}
