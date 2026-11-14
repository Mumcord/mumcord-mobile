// SPDX-License-Identifier: AGPL-3.0-only

use mumcord_engine::{
    ChannelPasswordState as EngineChannelPasswordState, ChannelView, ConnState, UserView,
};

use crate::types::{
    CertificatePrompt, Channel, ChannelPasswordState, ChannelPermissions, ConnectionState, Person,
};

pub(crate) fn connection_state(state: ConnState) -> ConnectionState {
    match state {
        ConnState::Disconnected { error } => ConnectionState::Disconnected {
            error: error.map(Into::into),
        },
        ConnState::Connecting { .. } => ConnectionState::Connecting,
        ConnState::Connected { .. } => ConnectionState::Connected,
        ConnState::Reconnecting {
            attempt,
            max_attempts,
            next_retry_secs,
            ..
        } => ConnectionState::Reconnecting {
            attempt,
            max_attempts,
            next_retry_secs,
        },
    }
}

pub(crate) fn certificate_prompt(
    fingerprint: String,
    previous_fingerprint: Option<String>,
) -> CertificatePrompt {
    CertificatePrompt {
        fingerprint,
        previous_fingerprint,
    }
}

pub(crate) fn channel(channel: ChannelView) -> Channel {
    let rights = channel.permissions;
    Channel {
        id: channel.id,
        parent_id: channel.parent_id,
        name: channel.name,
        position: channel.position,
        has_description: channel.has_description,
        max_people: channel.max_users,
        temporary: channel.temporary,
        restricted: channel.enter_restricted,
        can_enter: rights.enter,
        permissions: ChannelPermissions {
            speak: rights.speak,
            whisper: rights.whisper,
            text_message: rights.text_message,
            move_people: rights.move_users,
            mute_deafen: rights.mute_deafen,
            kick: rights.kick,
            ban: rights.ban,
        },
    }
}

/// What the bridge itself knows about a person, next to what the engine's view says.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct PersonFlags {
    pub(crate) is_self: bool,
    pub(crate) talking: bool,
    pub(crate) streaming: bool,
    pub(crate) camera: bool,
}

pub(crate) fn person(user: &UserView, flags: PersonFlags) -> Person {
    Person {
        session: user.session,
        channel_id: user.channel_id,
        name: user.name.clone(),
        is_self: flags.is_self,
        talking: flags.talking,
        self_muted: user.self_mute,
        self_deafened: user.self_deaf,
        server_muted: user.server_mute,
        server_deafened: user.server_deaf,
        suppressed: user.suppressed,
        local_volume: user.volume,
        locally_muted: user.local_mute,
        streaming: flags.streaming,
        camera: flags.camera,
    }
}

pub(crate) fn channel_password_state(state: EngineChannelPasswordState) -> ChannelPasswordState {
    match state {
        EngineChannelPasswordState::Required => ChannelPasswordState::Required,
        EngineChannelPasswordState::Wrong => ChannelPasswordState::Wrong,
        EngineChannelPasswordState::Ended => ChannelPasswordState::Ended,
    }
}

#[cfg(test)]
mod tests {
    use mumcord_engine::{EngineError, ErrorKind};

    use super::*;
    use crate::error::BridgeError;

    #[test]
    fn connection_states_map_without_the_server_id() {
        assert_eq!(
            connection_state(ConnState::Disconnected { error: None }),
            ConnectionState::Disconnected { error: None }
        );
        assert_eq!(
            connection_state(ConnState::Disconnected {
                error: Some(EngineError::new(ErrorKind::WrongPassword, "refused")),
            }),
            ConnectionState::Disconnected {
                error: Some(BridgeError::WrongPassword {
                    detail: "refused".into()
                }),
            }
        );
        assert_eq!(
            connection_state(ConnState::Connecting { server_id: 4 }),
            ConnectionState::Connecting
        );
        assert_eq!(
            connection_state(ConnState::Connected { server_id: 4 }),
            ConnectionState::Connected
        );
        assert_eq!(
            connection_state(ConnState::Reconnecting {
                server_id: 4,
                attempt: 2,
                max_attempts: 10,
                next_retry_secs: 8,
            }),
            ConnectionState::Reconnecting {
                attempt: 2,
                max_attempts: 10,
                next_retry_secs: 8,
            }
        );
    }
}
