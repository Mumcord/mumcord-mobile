// SPDX-License-Identifier: AGPL-3.0-only

//! Voice: whisper targets, who may be heard, and the audio configuration.

use mumcord_audio::io::ExternalFormat;
use mumcord_engine::whisper::{WhisperDenied, WhisperTo};
use mumcord_engine::{EchoCancellationMode, UserView};

use crate::error::BridgeError;
use crate::types::{AudioConfig, EchoCancellation, WhisperTarget};

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

/// Who the engine sends a whisper or shout to. People are named by their session, so nobody
/// else can come to hear it, whatever their name is; a shout reaches the channel itself, not
/// its sub-channels or linked channels.
pub(crate) fn whisper_to(target: WhisperTarget) -> WhisperTo {
    match target {
        WhisperTarget::Person { session } => WhisperTo::Sessions(vec![session]),
        WhisperTarget::Channel { channel_id } => WhisperTo::Channel {
            channel_id,
            children: false,
            links: false,
            group: None,
        },
    }
}

impl From<WhisperDenied> for BridgeError {
    fn from(denied: WhisperDenied) -> Self {
        let detail = denied.to_string();
        match denied {
            WhisperDenied::NoVoiceServer => Self::NotConnected { detail },
            // A name that fits several people; cannot happen with sessions.
            WhisperDenied::Muted | WhisperDenied::AmbiguousName => Self::InvalidInput { detail },
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

    #[test]
    fn a_person_is_whispered_to_by_their_session() {
        assert_eq!(
            whisper_to(WhisperTarget::Person { session: 7 }),
            WhisperTo::Sessions(vec![7])
        );
    }

    #[test]
    fn a_shout_reaches_the_channel_itself_only() {
        assert_eq!(
            whisper_to(WhisperTarget::Channel { channel_id: 3 }),
            WhisperTo::Channel {
                channel_id: 3,
                children: false,
                links: false,
                group: None,
            }
        );
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
        assert!(matches!(
            kind(WhisperDenied::ChannelNotFound),
            BridgeError::NotFound { .. }
        ));
        assert!(matches!(
            kind(WhisperDenied::ServerMuted),
            BridgeError::PermissionDenied { .. }
        ));
        assert!(matches!(
            kind(WhisperDenied::AmbiguousName),
            BridgeError::InvalidInput { .. }
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
