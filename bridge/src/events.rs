// SPDX-License-Identifier: AGPL-3.0-only

//! Events out: the engine's event stream, reduced to the bridge's events and delivered to the
//! app's listener from one bridge thread.

use std::sync::{Arc, Mutex, MutexGuard};

use mumcord_engine::{EngineEvent, EventReceiver, ServerId, TaggedEvent};
use tokio::runtime::Handle;
use tokio::sync::oneshot;

use crate::convert;
use crate::types::{CertificatePrompt, Channel, ConnectionState, Person, ServerState};

/// A change of the state. Apply it to the last [`BridgeSnapshot`](crate::BridgeSnapshot), or
/// fetch a new snapshot.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum BridgeEvent {
    ConnectionChanged {
        state: ConnectionState,
    },
    /// Connecting waits until the app answers with
    /// [`MumcordEngine::answer_certificate_prompt`](crate::MumcordEngine::answer_certificate_prompt).
    CertificatePromptRequired {
        prompt: CertificatePrompt,
    },
    /// The server has sent all its channels and people.
    ServerSynced {
        server: ServerState,
    },
    /// Channels that were added or changed.
    ChannelsChanged {
        channels: Vec<Channel>,
    },
    ChannelRemoved {
        id: u32,
    },
    /// A person who joined, moved to another channel or changed.
    PersonChanged {
        person: Person,
    },
    PersonRemoved {
        session: u32,
    },
}

/// Receives the events; implemented by the app (Kotlin, Swift). Called on one bridge thread, in
/// order, never on an audio thread. It should return quickly, as the next event waits for it.
#[uniffi::export(with_foreign)]
pub trait EngineListener: Send + Sync {
    fn on_event(&self, event: BridgeEvent);
}

/// What the bridge itself keeps about the one connection it allows.
pub(crate) struct Connection {
    /// The server of the current connection, from `connect` until it has ended.
    pub(crate) server: Option<ServerId>,
    pub(crate) state: ConnectionState,
    pub(crate) certificate_prompt: Option<CertificatePrompt>,
}

impl Default for Connection {
    fn default() -> Self {
        Self {
            server: None,
            state: ConnectionState::Disconnected { error: None },
            certificate_prompt: None,
        }
    }
}

pub(crate) type SharedConnection = Arc<Mutex<Connection>>;

pub(crate) fn lock(connection: &SharedConnection) -> MutexGuard<'_, Connection> {
    // The guarded data stays consistent even if a holder panicked: every update is one step.
    connection
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Applies an engine event to the bridge's connection state and turns it into the event for the
/// app. `None` for events of another server and for events the bridge does not offer yet.
pub(crate) fn translate(
    connection: &SharedConnection,
    (server, event): TaggedEvent,
) -> Option<BridgeEvent> {
    let mut connection = lock(connection);
    if server.is_none() || server != connection.server {
        return None;
    }
    Some(match event {
        EngineEvent::StateChanged(state) => {
            let state = convert::connection_state(state);
            if !matches!(state, ConnectionState::Connecting) {
                connection.certificate_prompt = None;
            }
            if matches!(state, ConnectionState::Disconnected { .. }) {
                connection.server = None;
            }
            connection.state = state.clone();
            BridgeEvent::ConnectionChanged { state }
        }
        EngineEvent::CertTrustRequired {
            fingerprint,
            previous_fingerprint,
            ..
        } => {
            let prompt = convert::certificate_prompt(fingerprint, previous_fingerprint);
            connection.certificate_prompt = Some(prompt.clone());
            BridgeEvent::CertificatePromptRequired { prompt }
        }
        EngineEvent::ServerSynced(snapshot) => BridgeEvent::ServerSynced {
            server: convert::server_state(*snapshot),
        },
        EngineEvent::ChannelUpserted(channel) => BridgeEvent::ChannelsChanged {
            channels: vec![convert::channel(channel)],
        },
        EngineEvent::ChannelsUpserted(channels) => BridgeEvent::ChannelsChanged {
            channels: channels.into_iter().map(convert::channel).collect(),
        },
        EngineEvent::ChannelRemoved(id) => BridgeEvent::ChannelRemoved { id },
        EngineEvent::UserUpserted(user) => BridgeEvent::PersonChanged {
            person: convert::person(user),
        },
        EngineEvent::UserRemoved(session) => BridgeEvent::PersonRemoved { session },
        _ => return None,
    })
}

/// Starts the bridge thread that delivers events to `listener` until `stop` is dropped or the
/// engine is gone.
pub(crate) fn spawn_pump(
    runtime: Handle,
    mut events: EventReceiver,
    connection: SharedConnection,
    listener: Arc<dyn EngineListener>,
    mut stop: oneshot::Receiver<()>,
) -> std::io::Result<()> {
    std::thread::Builder::new()
        .name("mumcord-bridge-events".into())
        .spawn(move || {
            // Only waits on channels, so it keeps working while the runtime shuts down.
            runtime.block_on(async {
                loop {
                    let event = tokio::select! {
                        _ = &mut stop => break,
                        event = events.recv_tagged() => event,
                    };
                    let Some(event) = event else { break };
                    if let Some(event) = translate(&connection, event) {
                        listener.on_event(event);
                    }
                }
            });
        })
        .map(drop)
}

#[cfg(test)]
mod tests {
    use mumcord_engine::{ConnState, EngineError, ErrorKind};

    use super::*;

    fn connected_to(server: ServerId) -> SharedConnection {
        Arc::new(Mutex::new(Connection {
            server: Some(server),
            ..Connection::default()
        }))
    }

    fn prompt_event(server_id: ServerId) -> EngineEvent {
        EngineEvent::CertTrustRequired {
            server_id,
            fingerprint: "AA:BB".into(),
            previous_fingerprint: Some("CC:DD".into()),
        }
    }

    #[test]
    fn events_of_other_servers_and_app_wide_events_are_dropped() {
        let connection = connected_to(1);
        assert_eq!(translate(&connection, (Some(2), prompt_event(2))), None);
        assert_eq!(
            translate(&connection, (None, EngineEvent::MicLevel(0.5))),
            None
        );
        assert_eq!(lock(&connection).certificate_prompt, None);
    }

    #[test]
    fn events_the_bridge_does_not_offer_yet_are_dropped() {
        let connection = connected_to(1);
        assert_eq!(
            translate(&connection, (Some(1), EngineEvent::MicLevel(0.5))),
            None
        );
    }

    #[test]
    fn a_certificate_prompt_is_kept_until_the_connection_moves_on() {
        let connection = connected_to(1);
        let prompt = CertificatePrompt {
            fingerprint: "AA:BB".into(),
            previous_fingerprint: Some("CC:DD".into()),
        };
        assert_eq!(
            translate(&connection, (Some(1), prompt_event(1))),
            Some(BridgeEvent::CertificatePromptRequired {
                prompt: prompt.clone()
            })
        );
        assert_eq!(lock(&connection).certificate_prompt, Some(prompt));

        let connected = EngineEvent::StateChanged(ConnState::Connected { server_id: 1 });
        assert_eq!(
            translate(&connection, (Some(1), connected)),
            Some(BridgeEvent::ConnectionChanged {
                state: ConnectionState::Connected
            })
        );
        let connection = lock(&connection);
        assert_eq!(connection.certificate_prompt, None);
        assert_eq!(connection.state, ConnectionState::Connected);
        assert_eq!(connection.server, Some(1));
    }

    #[test]
    fn a_disconnect_frees_the_connection_and_keeps_its_error() {
        let connection = connected_to(1);
        translate(&connection, (Some(1), prompt_event(1)));
        let ended = EngineEvent::StateChanged(ConnState::Disconnected {
            error: Some(EngineError::new(ErrorKind::CertificateChanged, "declined")),
        });
        let event = translate(&connection, (Some(1), ended)).expect("event");
        let BridgeEvent::ConnectionChanged { state } = event else {
            panic!("unexpected event {event:?}");
        };
        let connection = lock(&connection);
        assert_eq!(connection.server, None);
        assert_eq!(connection.certificate_prompt, None);
        assert_eq!(connection.state, state);
        assert!(matches!(
            state,
            ConnectionState::Disconnected {
                error: Some(crate::BridgeError::CertificateChanged { .. })
            }
        ));
    }

    #[test]
    fn channel_and_person_removals_pass_their_ids() {
        let connection = connected_to(1);
        assert_eq!(
            translate(&connection, (Some(1), EngineEvent::ChannelRemoved(7))),
            Some(BridgeEvent::ChannelRemoved { id: 7 })
        );
        assert_eq!(
            translate(&connection, (Some(1), EngineEvent::UserRemoved(9))),
            Some(BridgeEvent::PersonRemoved { session: 9 })
        );
    }
}
