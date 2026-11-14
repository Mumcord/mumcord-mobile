// SPDX-License-Identifier: AGPL-3.0-only

//! Events out: the engine's event stream, reduced to the bridge's events and delivered to the
//! app's listener from one bridge thread.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard};

use mumcord_engine::{EngineEvent, EventReceiver, ServerId, Snapshot, TaggedEvent, UserView};
use tokio::runtime::Handle;
use tokio::sync::{mpsc, oneshot};

use crate::convert::{self, PersonFlags};
use crate::types::{
    CertificatePrompt, Channel, ChannelPasswordState, ConnectionState, Person, SavedServer,
    ServerState,
};

/// A change of the state. Apply it to the last [`BridgeSnapshot`](crate::BridgeSnapshot), or
/// fetch a new snapshot.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum BridgeEvent {
    /// The server list after a server was added, edited, removed or moved.
    ServersChanged {
        servers: Vec<SavedServer>,
    },
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
    /// A person who joined, moved to another channel or changed, also in what only the user
    /// sees of them (talking, volume for me).
    PersonChanged {
        person: Person,
    },
    PersonRemoved {
        session: u32,
    },
    /// Joining a channel that needs a password moved on; `Required` asks the user for it
    /// ([`MumcordEngine::join_channel`](crate::MumcordEngine::join_channel) with the password).
    ChannelPasswordChanged {
        channel_id: u32,
        state: ChannelPasswordState,
    },
    /// The server refused something the user asked for; `detail` is the server's own text.
    ActionDenied {
        channel_id: Option<u32>,
        detail: String,
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
    /// Known once the server is synced.
    self_session: Option<u32>,
    /// The people as the engine last reported them. The engine reports talking, screen shares
    /// and cameras apart from the people, so a change there is sent as the whole person again.
    people: HashMap<u32, UserView>,
    talking: HashSet<u32>,
    streaming: HashSet<u32>,
    camera: HashSet<u32>,
}

impl Default for Connection {
    fn default() -> Self {
        Self {
            server: None,
            state: ConnectionState::Disconnected { error: None },
            certificate_prompt: None,
            self_session: None,
            people: HashMap::new(),
            talking: HashSet::new(),
            streaming: HashSet::new(),
            camera: HashSet::new(),
        }
    }
}

impl Connection {
    /// A connection to `server` that is about to start.
    pub(crate) fn to(server: ServerId) -> Self {
        Self {
            server: Some(server),
            ..Self::default()
        }
    }

    fn person(&self, user: &UserView) -> Person {
        let session = user.session;
        convert::person(
            user,
            PersonFlags {
                is_self: self.self_session == Some(session),
                talking: self.talking.contains(&session),
                streaming: self.streaming.contains(&session),
                camera: self.camera.contains(&session),
            },
        )
    }

    /// Takes over the engine's full state of the server.
    pub(crate) fn server_state(&mut self, snapshot: Snapshot) -> ServerState {
        self.self_session = Some(snapshot.self_session);
        self.people = snapshot.users.into_iter().map(|u| (u.session, u)).collect();
        let mut people: Vec<Person> = self.people.values().map(|u| self.person(u)).collect();
        people.sort_unstable_by_key(|person| person.session);
        ServerState {
            self_session: snapshot.self_session,
            channels: snapshot
                .channels
                .into_iter()
                .map(convert::channel)
                .collect(),
            people,
        }
    }

    /// The people whose flag changed between `before` and `after`, as events.
    fn changed_people(&self, before: &HashSet<u32>, after: &HashSet<u32>) -> Vec<BridgeEvent> {
        let mut sessions: Vec<u32> = before.symmetric_difference(after).copied().collect();
        sessions.sort_unstable();
        sessions
            .into_iter()
            .filter_map(|session| self.people.get(&session))
            .map(|user| BridgeEvent::PersonChanged {
                person: self.person(user),
            })
            .collect()
    }
}

pub(crate) type SharedConnection = Arc<Mutex<Connection>>;

pub(crate) fn lock<T>(shared: &Mutex<T>) -> MutexGuard<'_, T> {
    // The guarded data stays consistent even if a holder panicked: every update is one step.
    shared
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Applies an engine event to the bridge's connection state and turns it into the events for
/// the app. Nothing for events of another server and for events the bridge does not offer yet.
pub(crate) fn translate(
    connection: &SharedConnection,
    (server, event): TaggedEvent,
) -> Vec<BridgeEvent> {
    let mut connection = lock(connection);
    if connection.server.is_none() {
        return Vec::new();
    }
    // Talking belongs to the voice pipeline, which the engine does not always name a server
    // for; with one connection at a time it can only be this one.
    let untagged_talk = server.is_none() && matches!(event, EngineEvent::TalkStateChanged { .. });
    if server != connection.server && !untagged_talk {
        return Vec::new();
    }
    let one = |event| vec![event];
    match event {
        EngineEvent::StateChanged(state) => {
            let state = convert::connection_state(state);
            if !matches!(state, ConnectionState::Connecting) {
                connection.certificate_prompt = None;
            }
            if matches!(state, ConnectionState::Disconnected { .. }) {
                *connection = Connection::default();
            }
            connection.state = state.clone();
            one(BridgeEvent::ConnectionChanged { state })
        }
        EngineEvent::CertTrustRequired {
            fingerprint,
            previous_fingerprint,
            ..
        } => {
            let prompt = convert::certificate_prompt(fingerprint, previous_fingerprint);
            connection.certificate_prompt = Some(prompt.clone());
            one(BridgeEvent::CertificatePromptRequired { prompt })
        }
        EngineEvent::ServerSynced(snapshot) => one(BridgeEvent::ServerSynced {
            server: connection.server_state(*snapshot),
        }),
        EngineEvent::ChannelUpserted(channel) => one(BridgeEvent::ChannelsChanged {
            channels: vec![convert::channel(channel)],
        }),
        EngineEvent::ChannelsUpserted(channels) => one(BridgeEvent::ChannelsChanged {
            channels: channels.into_iter().map(convert::channel).collect(),
        }),
        EngineEvent::ChannelRemoved(id) => one(BridgeEvent::ChannelRemoved { id }),
        EngineEvent::UserUpserted(user) => {
            let person = connection.person(&user);
            connection.people.insert(user.session, user);
            one(BridgeEvent::PersonChanged { person })
        }
        EngineEvent::UserRemoved(session) => {
            connection.people.remove(&session);
            connection.talking.remove(&session);
            connection.streaming.remove(&session);
            connection.camera.remove(&session);
            one(BridgeEvent::PersonRemoved { session })
        }
        EngineEvent::TalkStateChanged { session, talking } => {
            let changed = if talking {
                connection.talking.insert(session)
            } else {
                connection.talking.remove(&session)
            };
            match connection.people.get(&session) {
                Some(user) if changed => one(BridgeEvent::PersonChanged {
                    person: connection.person(user),
                }),
                _ => Vec::new(),
            }
        }
        EngineEvent::ScreenSharesChanged(shares) => {
            let after = shares.iter().map(|share| share.session).collect();
            let before = std::mem::replace(&mut connection.streaming, after);
            connection.changed_people(&before, &connection.streaming)
        }
        EngineEvent::CameraRoomChanged(room) => {
            let after = room.tiles.iter().map(|tile| tile.sharer).collect();
            let before = std::mem::replace(&mut connection.camera, after);
            connection.changed_people(&before, &connection.camera)
        }
        EngineEvent::ChannelPassword { channel_id, state } => {
            one(BridgeEvent::ChannelPasswordChanged {
                channel_id,
                state: convert::channel_password_state(state),
            })
        }
        // A refusal the engine marks as handled is told through its own event (the channel
        // password).
        EngineEvent::ServerDenied {
            error,
            channel_id,
            handled: false,
            ..
        } => one(BridgeEvent::ActionDenied {
            channel_id,
            detail: error.message,
        }),
        _ => Vec::new(),
    }
}

/// Starts the bridge thread that delivers events to `listener` until `stop` is dropped or the
/// engine is gone: the engine's events and the bridge's `own` ones (the server list).
pub(crate) fn spawn_pump(
    runtime: Handle,
    mut events: EventReceiver,
    mut own: mpsc::UnboundedReceiver<BridgeEvent>,
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
                    tokio::select! {
                        _ = &mut stop => break,
                        event = events.recv_tagged() => {
                            let Some(event) = event else { break };
                            for event in translate(&connection, event) {
                                listener.on_event(event);
                            }
                        }
                        // The sender lives as long as the engine object, which also holds `stop`.
                        Some(event) = own.recv() => listener.on_event(event),
                    }
                }
            });
        })
        .map(drop)
}

#[cfg(test)]
mod tests {
    use mumcord_engine::camera::{CameraRoomView, CameraTile, TileQuality, TileState};
    use mumcord_engine::{ConnState, DenialKind, EngineError, ErrorKind};

    use super::*;

    fn connected_to(server: ServerId) -> SharedConnection {
        Arc::new(Mutex::new(Connection::to(server)))
    }

    fn prompt_event(server_id: ServerId) -> EngineEvent {
        EngineEvent::CertTrustRequired {
            server_id,
            fingerprint: "AA:BB".into(),
            previous_fingerprint: Some("CC:DD".into()),
        }
    }

    fn user(session: u32) -> UserView {
        UserView {
            session,
            channel_id: 1,
            name: format!("user{session}"),
            cert_hash: None,
            registered: false,
            self_mute: false,
            self_deaf: false,
            server_mute: false,
            server_deaf: false,
            suppressed: false,
            priority_speaker: false,
            listening_channel_ids: Vec::new(),
            has_avatar: false,
            avatar_hash: None,
            has_comment: false,
            volume: 100,
            local_mute: false,
            share_volume: 100,
            mumcord_features: None,
            mumcord_bot: None,
        }
    }

    /// The single person event `event` leads to.
    fn person_of(connection: &SharedConnection, event: EngineEvent) -> Person {
        let events = translate(connection, (Some(1), event));
        match events.as_slice() {
            [BridgeEvent::PersonChanged { person }] => person.clone(),
            other => panic!("unexpected events {other:?}"),
        }
    }

    fn cameras(sharers: &[u32]) -> EngineEvent {
        EngineEvent::CameraRoomChanged(CameraRoomView {
            server_id: 1,
            open: false,
            videos_hidden: false,
            tiles: sharers
                .iter()
                .map(|&sharer| CameraTile {
                    sharer,
                    state: TileState::Announced,
                    hidden: false,
                    quality: TileQuality::Auto,
                    data_saver: false,
                    fps_badge: None,
                })
                .collect(),
        })
    }

    #[test]
    fn events_of_other_servers_and_app_wide_events_are_dropped() {
        let connection = connected_to(1);
        assert_eq!(translate(&connection, (Some(2), prompt_event(2))), []);
        assert_eq!(
            translate(&connection, (None, EngineEvent::MicLevel(0.5))),
            []
        );
        assert_eq!(lock(&connection).certificate_prompt, None);
    }

    #[test]
    fn events_the_bridge_does_not_offer_yet_are_dropped() {
        let connection = connected_to(1);
        assert_eq!(
            translate(&connection, (Some(1), EngineEvent::MicLevel(0.5))),
            []
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
            [BridgeEvent::CertificatePromptRequired {
                prompt: prompt.clone()
            }]
        );
        assert_eq!(lock(&connection).certificate_prompt, Some(prompt));

        let connected = EngineEvent::StateChanged(ConnState::Connected { server_id: 1 });
        assert_eq!(
            translate(&connection, (Some(1), connected)),
            [BridgeEvent::ConnectionChanged {
                state: ConnectionState::Connected
            }]
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
        translate(&connection, (Some(1), EngineEvent::UserUpserted(user(5))));
        let ended = EngineEvent::StateChanged(ConnState::Disconnected {
            error: Some(EngineError::new(ErrorKind::CertificateChanged, "declined")),
        });
        let events = translate(&connection, (Some(1), ended));
        let [BridgeEvent::ConnectionChanged { state }] = events.as_slice() else {
            panic!("unexpected events {events:?}");
        };
        let connection = lock(&connection);
        assert_eq!(connection.server, None);
        assert_eq!(connection.certificate_prompt, None);
        assert!(connection.people.is_empty());
        assert_eq!(&connection.state, state);
        assert!(matches!(
            state,
            ConnectionState::Disconnected {
                error: Some(crate::BridgeError::CertificateChanged { .. })
            }
        ));
    }

    #[test]
    fn a_ban_ends_the_connection_as_banned() {
        let connection = connected_to(1);
        let ended = EngineEvent::StateChanged(ConnState::Disconnected {
            error: Some(EngineError::new(
                ErrorKind::PermissionDenied,
                "banned: spamming",
            )),
        });
        assert_eq!(
            translate(&connection, (Some(1), ended)),
            [BridgeEvent::ConnectionChanged {
                state: ConnectionState::Disconnected {
                    error: Some(crate::BridgeError::Banned {
                        detail: "spamming".into()
                    })
                }
            }]
        );
    }

    #[test]
    fn channel_and_person_removals_pass_their_ids() {
        let connection = connected_to(1);
        assert_eq!(
            translate(&connection, (Some(1), EngineEvent::ChannelRemoved(7))),
            [BridgeEvent::ChannelRemoved { id: 7 }]
        );
        assert_eq!(
            translate(&connection, (Some(1), EngineEvent::UserRemoved(9))),
            [BridgeEvent::PersonRemoved { session: 9 }]
        );
    }

    #[test]
    fn talking_is_reported_as_the_changed_person() {
        let connection = connected_to(1);
        let joined = person_of(&connection, EngineEvent::UserUpserted(user(5)));
        assert!(!joined.talking && !joined.is_self);
        assert_eq!(joined.local_volume, 100);

        let talk = |talking| EngineEvent::TalkStateChanged {
            session: 5,
            talking,
        };
        assert!(person_of(&connection, talk(true)).talking);
        // Nothing new: no event. The voice pipeline's events may come without a server.
        assert_eq!(translate(&connection, (None, talk(true))), []);
        let stopped = translate(&connection, (None, talk(false)));
        assert!(
            matches!(stopped.as_slice(), [BridgeEvent::PersonChanged { person }] if !person.talking),
            "{stopped:?}"
        );
        // Someone the app was not told about yet.
        let unknown = EngineEvent::TalkStateChanged {
            session: 6,
            talking: true,
        };
        assert_eq!(translate(&connection, (Some(1), unknown)), []);
        // A later change of the person keeps what the bridge knows about them.
        assert!(person_of(&connection, talk(true)).talking);
        let mut muted = user(5);
        muted.self_mute = true;
        let person = person_of(&connection, EngineEvent::UserUpserted(muted));
        assert!(person.talking && person.self_muted);
    }

    #[test]
    fn cameras_are_reported_for_the_people_they_change() {
        let connection = connected_to(1);
        for session in [5, 6] {
            translate(
                &connection,
                (Some(1), EngineEvent::UserUpserted(user(session))),
            );
        }
        assert!(person_of(&connection, cameras(&[5])).camera);
        // 5 turns the camera off, 6 turns it on: both change, in the order of their sessions.
        let events = translate(&connection, (Some(1), cameras(&[6])));
        let flags: Vec<(u32, bool)> = events
            .iter()
            .map(|event| match event {
                BridgeEvent::PersonChanged { person } => (person.session, person.camera),
                other => panic!("unexpected event {other:?}"),
            })
            .collect();
        assert_eq!(flags, [(5, false), (6, true)]);
        assert_eq!(translate(&connection, (Some(1), cameras(&[6]))), []);
    }

    #[test]
    fn refusals_pass_the_servers_text_unless_the_engine_handled_them() {
        let connection = connected_to(1);
        let denied = |handled| EngineEvent::ServerDenied {
            error: EngineError::new(ErrorKind::PermissionDenied, "no entry"),
            channel_id: Some(3),
            kind: DenialKind::Text,
            permission: None,
            handled,
        };
        assert_eq!(
            translate(&connection, (Some(1), denied(false))),
            [BridgeEvent::ActionDenied {
                channel_id: Some(3),
                detail: "no entry".into()
            }]
        );
        assert_eq!(translate(&connection, (Some(1), denied(true))), []);
        let asked = EngineEvent::ChannelPassword {
            channel_id: 3,
            state: mumcord_engine::ChannelPasswordState::Required,
        };
        assert_eq!(
            translate(&connection, (Some(1), asked)),
            [BridgeEvent::ChannelPasswordChanged {
                channel_id: 3,
                state: ChannelPasswordState::Required
            }]
        );
    }
}
