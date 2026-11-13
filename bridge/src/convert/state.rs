// SPDX-License-Identifier: AGPL-3.0-only

use mumcord_engine::{ChannelView, ConnState, Snapshot, UserView};

use crate::types::{CertificatePrompt, Channel, ConnectionState, Person, ServerState};

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
    Channel {
        id: channel.id,
        parent_id: channel.parent_id,
        name: channel.name,
        position: channel.position,
    }
}

pub(crate) fn person(user: UserView) -> Person {
    Person {
        session: user.session,
        channel_id: user.channel_id,
        name: user.name,
    }
}

pub(crate) fn server_state(snapshot: Snapshot) -> ServerState {
    ServerState {
        self_session: snapshot.self_session,
        channels: snapshot.channels.into_iter().map(channel).collect(),
        people: snapshot.users.into_iter().map(person).collect(),
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
                    message: "refused".into()
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
