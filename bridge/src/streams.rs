// SPDX-License-Identifier: AGPL-3.0-only

//! Streams (Spec 5 FR-5.70 … FR-5.73): the screen shares and cameras of the people in the
//! user's channel, watching them, their sound volume and whether they can be reached.
//!
//! The list is part of the snapshot ([`BridgeSnapshot::streams`](crate::BridgeSnapshot::streams))
//! and arrives whole as [`BridgeEvent::StreamsChanged`] whenever a stream starts or stops, a
//! watch changes state or its path changes. Nothing is downloaded before
//! [`MumcordEngine::watch_stream`]: whether a stream starts by itself (Wi-Fi, or "Start streams
//! automatically" on mobile data, FR-5.41) is the app's decision.
//!
//! Screen shares come over a direct connection between the clients or through the server's
//! optional relay, as on the desktop. A direct connection reveals the user's IP address to the
//! other person, so the first attempt asks [`BridgeEvent::DirectConnectionsQuestion`]; the
//! answer ([`MumcordEngine::answer_direct_connections`]) is kept for later streams.
//!
//! Cameras are fetched only for the tiles the user watches: the camera room stays closed until
//! the first camera is watched and closes again with the last one; while it is open, every
//! camera not watched is hidden.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

use mumcord_engine::camera::{CameraRoomView, TileState};
use mumcord_engine::p2p::ConnectionPath;
use mumcord_engine::screen_share::wire::Codec;
use mumcord_engine::screen_share::{ScreenShareView, WatchBlock};
use mumcord_engine::{Engine, EngineError, EngineEvent, ErrorKind, ServerId};
use tokio::sync::Notify;

use crate::engine::{MumcordEngine, connected_server};
use crate::error::BridgeError;
use crate::events::{BridgeEvent, SharedConnection, lock};
use crate::video::{VideoFrames, VideoSink, video_id};

/// Every codec the engine can decode is decoded by it; the app gets pictures only.
const DECODED: [Codec; 2] = [Codec::Vp9, Codec::Av1];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum StreamKind {
    /// A screen share.
    Screen,
    /// A camera.
    Camera,
}

/// How a watched stream travels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StreamPath {
    /// Directly between the two clients.
    Direct,
    /// Through the server's relay.
    Relay,
    /// Not known (cameras: the engine does not tell yet).
    Unknown,
}

/// Why a stream cannot be watched from here ("Stream not reachable from this network",
/// FR-5.73, or the more specific text the app has for a reason).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StreamUnreachable {
    /// Peer-to-peer is switched off for this server.
    PeerToPeerOff,
    /// The user declined direct connections and the relay cannot be used either.
    DirectConnectionsDeclined,
    /// The relay was needed but cannot be used.
    RelayUnavailable,
    /// The sharer's client does not take direct connections.
    SharerUnsupported,
    /// Neither a direct path nor the relay works from this network.
    NoPath,
    /// The sharer did not answer the watch request.
    NoAnswer,
    /// Too many people are watching already.
    Full,
    /// The sharer needs a newer Mumcord to serve this client.
    NeedsUpdate,
    /// Joining the camera brought no answer; the engine tries again by itself.
    JoinFailed,
}

/// The user's watch of a stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StreamWatch {
    /// Not watched; nothing is downloaded.
    Idle,
    /// Watch asked for, the stream is not playing yet.
    Connecting,
    /// Playing: pictures arrive at the [`VideoSink`].
    Watching { path: StreamPath },
    /// Cannot be watched. For a screen share this can show before any attempt.
    Unreachable { reason: StreamUnreachable },
}

/// A screen share or camera of a person in the user's channel. The user's own share and camera
/// are not in the list.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Stream {
    pub session: u32,
    pub kind: StreamKind,
    /// The id of this stream's pictures for the [`VideoSink`] and `mumcord_video_frame_acquire`.
    pub video_id: u64,
    pub watch: StreamWatch,
    /// The sharer paused the share.
    pub paused: bool,
    /// The share sends sound; it plays mixed with voice at `volume`.
    pub has_sound: bool,
    /// The sound volume for this person's shares, in percent (0–200).
    pub volume: u16,
    /// How many people watch; `None` where the bridge cannot know (cameras).
    pub viewers: Option<u32>,
}

/// The state of a screen share watch the user asked for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ScreenWatch {
    Connecting,
    Open,
    Unreachable(StreamUnreachable),
}

/// The watches of the current connection and the list as last reported.
#[derive(Default)]
pub(crate) struct Streams {
    /// The server the watches belong to.
    server: Option<ServerId>,
    screens: HashMap<u32, ScreenWatch>,
    /// The cameras the user watches.
    cameras: HashSet<u32>,
    pub(crate) reported: Vec<Stream>,
}

pub(crate) type SharedStreams = Arc<Mutex<Streams>>;

impl Streams {
    /// Forgets the watches of an earlier connection.
    fn on_server(&mut self, server: Option<ServerId>) {
        if self.server != server {
            self.server = server;
            self.screens.clear();
            self.cameras.clear();
        }
    }

    /// Whether the pictures of `id` are wanted.
    fn plays(&self, id: u64) -> bool {
        self.screens.iter().any(|(&session, watch)| {
            *watch == ScreenWatch::Open && video_id(StreamKind::Screen, session) == id
        }) || self
            .cameras
            .iter()
            .any(|&session| video_id(StreamKind::Camera, session) == id)
    }

    fn report(&mut self, streams: Vec<Stream>) -> Option<BridgeEvent> {
        (streams != self.reported).then(|| {
            self.reported.clone_from(&streams);
            BridgeEvent::StreamsChanged { streams }
        })
    }
}

/// Engine events after which the list may look different.
pub(crate) fn affected_by(event: &EngineEvent) -> bool {
    matches!(
        event,
        EngineEvent::ScreenSharesChanged(_)
            | EngineEvent::CameraRoomChanged(_)
            | EngineEvent::P2pLinkChanged { .. }
            | EngineEvent::UserUpserted(_)
            | EngineEvent::UserRemoved(_)
            | EngineEvent::ServerSynced(_)
            | EngineEvent::StateChanged(_)
    )
}

/// What the bridge needs to keep the list.
#[derive(Clone)]
pub(crate) struct StreamParts {
    pub(crate) engine: Engine,
    pub(crate) connection: SharedConnection,
    pub(crate) streams: SharedStreams,
    pub(crate) video: &'static VideoFrames,
    /// Asks the event thread to look at the list again.
    pub(crate) changed: Arc<Notify>,
}

impl StreamParts {
    /// Brings the watches in line with the engine (ended shares and cameras, the camera room)
    /// and returns the list as an event when it changed since it was last reported.
    pub(crate) fn refresh(&self) -> Option<BridgeEvent> {
        let mut streams = lock(&self.streams);
        let (server, own, volumes) = {
            let connection = lock(&self.connection);
            let volumes: HashMap<u32, u16> = connection
                .people()
                .map(|user| (user.session, user.share_volume))
                .collect();
            (
                connection.server.filter(|_| connection.synced()),
                connection.self_session(),
                volumes,
            )
        };
        streams.on_server(server);
        let Some(server) = server else {
            self.video.retain(|_| false);
            return streams.report(Vec::new());
        };
        let shares: Vec<ScreenShareView> = self
            .engine
            .screen_shares(server)
            .into_iter()
            .filter(|share| Some(share.session) != own)
            .collect();
        let room = self.engine.camera_room(server);
        let engine = &self.engine;
        streams.screens.retain(|&sharer, watch| {
            shares.iter().any(|share| share.session == sharer)
                // A watch the engine no longer has ended by itself (the link dropped).
                && (*watch != ScreenWatch::Open
                    || engine.screen_share_watch_via_relay(server, sharer).is_some())
        });
        streams
            .cameras
            .retain(|&sharer| room.tiles.iter().any(|tile| tile.sharer == sharer));
        self.keep_room(server, &streams.cameras, &room);
        self.video.retain(|id| streams.plays(id));

        let mut list: Vec<Stream> = shares
            .iter()
            .map(|share| {
                let watch = screen_watch(
                    streams.screens.get(&share.session).copied(),
                    share.blocked,
                    || self.screen_path(server, share.session),
                );
                Stream {
                    session: share.session,
                    kind: StreamKind::Screen,
                    video_id: video_id(StreamKind::Screen, share.session),
                    watch,
                    paused: share.paused,
                    has_sound: share.sound,
                    volume: volumes.get(&share.session).copied().unwrap_or(100),
                    viewers: Some(u32::try_from(share.viewers.len()).unwrap_or(u32::MAX)),
                }
            })
            .chain(
                room.tiles
                    .iter()
                    .filter(|tile| Some(tile.sharer) != own)
                    .map(|tile| Stream {
                        session: tile.sharer,
                        kind: StreamKind::Camera,
                        video_id: video_id(StreamKind::Camera, tile.sharer),
                        watch: camera_watch(streams.cameras.contains(&tile.sharer), tile.state),
                        paused: false,
                        has_sound: false,
                        volume: volumes.get(&tile.sharer).copied().unwrap_or(100),
                        viewers: None,
                    }),
            )
            .collect();
        list.sort_unstable_by_key(|stream| (stream.session, stream.kind == StreamKind::Camera));
        streams.report(list)
    }

    /// Keeps the camera room closed without a watched camera and hides every camera that is not
    /// watched while it is open, so nothing is fetched that the user did not ask for.
    fn keep_room(&self, server: ServerId, watched: &HashSet<u32>, room: &CameraRoomView) {
        if watched.is_empty() {
            if room.open {
                self.engine.camera_room_set_open(server, false);
            }
            return;
        }
        for tile in &room.tiles {
            if !watched.contains(&tile.sharer) && !tile.hidden {
                self.engine
                    .camera_tile_set_hidden(server, tile.sharer, true);
            }
        }
    }

    fn screen_path(&self, server: ServerId, sharer: u32) -> StreamPath {
        if self.engine.screen_share_watch_via_relay(server, sharer) == Some(true) {
            return StreamPath::Relay;
        }
        match self.engine.p2p_link(server, sharer).map(|link| link.path) {
            Some(ConnectionPath::Direct) => StreamPath::Direct,
            Some(ConnectionPath::Relay(_)) => StreamPath::Relay,
            None => StreamPath::Unknown,
        }
    }

    async fn watch_screen(&self, server: ServerId, sharer: u32) -> Result<(), BridgeError> {
        {
            let mut streams = lock(&self.streams);
            streams.on_server(Some(server));
            if matches!(
                streams.screens.get(&sharer),
                Some(ScreenWatch::Connecting | ScreenWatch::Open)
            ) {
                return Ok(());
            }
            streams.screens.insert(sharer, ScreenWatch::Connecting);
        }
        self.changed.notify_one();
        let watched = self.engine.screen_share_watch(server, sharer).await;
        let outcome = self.screen_watched(server, sharer, watched);
        self.changed.notify_one();
        outcome
    }

    /// Takes the outcome of the engine's watch over, unless the user stopped meanwhile.
    fn screen_watched(
        &self,
        server: ServerId,
        sharer: u32,
        watched: Result<(), EngineError>,
    ) -> Result<(), BridgeError> {
        let mut streams = lock(&self.streams);
        let wanted = streams.server == Some(server)
            && streams.screens.get(&sharer) == Some(&ScreenWatch::Connecting);
        match watched {
            Ok(()) if wanted => {
                streams.screens.insert(sharer, ScreenWatch::Open);
                // The slot first, so the first decoded picture is kept.
                self.video.open(video_id(StreamKind::Screen, sharer));
                self.engine
                    .screen_share_set_decode_codecs(server, sharer, &DECODED)?;
                // Frames that came before were encoded ones the app cannot use: start the
                // pictures with a keyframe.
                if let Err(e) = self.engine.screen_share_request_keyframe(server, sharer) {
                    tracing::debug!(error = %e, "no keyframe asked for a new watch");
                }
                Ok(())
            }
            Ok(()) => {
                if let Err(e) = self.engine.screen_share_unwatch(server, sharer) {
                    tracing::debug!(error = %e, "ending a watch stopped while it opened");
                }
                Ok(())
            }
            Err(e) => match unreachable_reason(&e) {
                Some(reason) => {
                    if wanted {
                        streams
                            .screens
                            .insert(sharer, ScreenWatch::Unreachable(reason));
                    }
                    Ok(())
                }
                None => {
                    if wanted {
                        streams.screens.remove(&sharer);
                    }
                    Err(e.into())
                }
            },
        }
    }

    fn watch_camera(&self, server: ServerId, sharer: u32) -> Result<(), BridgeError> {
        let room = self.engine.camera_room(server);
        if !room.tiles.iter().any(|tile| tile.sharer == sharer) {
            return Err(BridgeError::NotFound {
                detail: "that person has no camera on".to_owned(),
            });
        }
        let mut streams = lock(&self.streams);
        streams.on_server(Some(server));
        streams.cameras.insert(sharer);
        self.video.open(video_id(StreamKind::Camera, sharer));
        // The others are hidden before the room opens, so opening joins only this one.
        self.keep_room(server, &streams.cameras, &room);
        self.engine.camera_tile_set_hidden(server, sharer, false);
        if !room.open {
            self.engine.camera_room_set_open(server, true);
        }
        drop(streams);
        self.changed.notify_one();
        Ok(())
    }

    fn stop(&self, server: ServerId, session: u32, kind: StreamKind) -> Result<(), BridgeError> {
        let mut streams = lock(&self.streams);
        self.video.close(video_id(kind, session));
        match kind {
            StreamKind::Screen => {
                // A watch still opening is ended when its answer comes.
                if streams.screens.remove(&session) == Some(ScreenWatch::Open) {
                    self.engine.screen_share_unwatch(server, session)?;
                }
            }
            StreamKind::Camera => {
                if streams.cameras.remove(&session) {
                    if streams.cameras.is_empty() {
                        self.engine.camera_room_set_open(server, false);
                    } else {
                        self.engine.camera_tile_set_hidden(server, session, true);
                    }
                }
            }
        }
        drop(streams);
        self.changed.notify_one();
        Ok(())
    }
}

/// A screen share's watch as the app sees it: the user's watch, else why it cannot be watched.
fn screen_watch(
    watch: Option<ScreenWatch>,
    blocked: Option<WatchBlock>,
    path: impl FnOnce() -> StreamPath,
) -> StreamWatch {
    match watch {
        Some(ScreenWatch::Connecting) => StreamWatch::Connecting,
        Some(ScreenWatch::Open) => StreamWatch::Watching { path: path() },
        Some(ScreenWatch::Unreachable(reason)) => StreamWatch::Unreachable { reason },
        None => blocked.map_or(StreamWatch::Idle, |block| StreamWatch::Unreachable {
            reason: block_reason(block),
        }),
    }
}

fn camera_watch(watched: bool, state: TileState) -> StreamWatch {
    if !watched {
        return StreamWatch::Idle;
    }
    let unreachable = |reason| StreamWatch::Unreachable { reason };
    match state {
        TileState::Playing => StreamWatch::Watching {
            path: StreamPath::Unknown,
        },
        TileState::Full => unreachable(StreamUnreachable::Full),
        TileState::Failed => unreachable(StreamUnreachable::JoinFailed),
        TileState::NeedsUpdate => unreachable(StreamUnreachable::NeedsUpdate),
        TileState::Announced | TileState::Joining | TileState::Hidden => StreamWatch::Connecting,
    }
}

fn block_reason(block: WatchBlock) -> StreamUnreachable {
    match block {
        WatchBlock::P2pOff => StreamUnreachable::PeerToPeerOff,
        WatchBlock::ConsentOff => StreamUnreachable::DirectConnectionsDeclined,
        WatchBlock::Relay(_) => StreamUnreachable::RelayUnavailable,
        WatchBlock::PeerUnsupported => StreamUnreachable::SharerUnsupported,
        WatchBlock::NoDirectPath => StreamUnreachable::NoPath,
    }
}

/// The reason a failed watch shows, for the failures that mean "cannot be reached"; `None` for
/// the others, which the command returns.
fn unreachable_reason(error: &EngineError) -> Option<StreamUnreachable> {
    Some(match error.kind {
        ErrorKind::ShareFull => StreamUnreachable::Full,
        ErrorKind::NeedsUpdate => StreamUnreachable::NeedsUpdate,
        // The engine names the reason by the code of `WatchBlock::code`.
        ErrorKind::DirectUnavailable => match error.message.as_str() {
            "p2pOff" => StreamUnreachable::PeerToPeerOff,
            "consentOff" => StreamUnreachable::DirectConnectionsDeclined,
            "relayUnavailable" => StreamUnreachable::RelayUnavailable,
            "peerUnsupported" => StreamUnreachable::SharerUnsupported,
            _ => StreamUnreachable::NoPath,
        },
        // The sharer did not answer in time; a share that ended meanwhile leaves the list.
        ErrorKind::NotFound => StreamUnreachable::NoAnswer,
        _ => return None,
    })
}

#[uniffi::export]
impl MumcordEngine {
    /// Starts watching a stream of a person in the user's channel. The way there shows in the
    /// stream's `watch` ([`BridgeEvent::StreamsChanged`]): `Connecting`, then `Watching` with
    /// pictures at the [`VideoSink`], or `Unreachable` with the reason (the command still
    /// succeeds then). A screen share's sound plays with voice at the stream's volume.
    /// Errors when not connected or when the person has no such stream.
    pub async fn watch_stream(&self, session: u32, kind: StreamKind) -> Result<(), BridgeError> {
        let parts = self.stream_parts();
        self.run(async move {
            let server = connected_server(&parts.connection)?;
            match kind {
                StreamKind::Screen => parts.watch_screen(server, session).await,
                StreamKind::Camera => parts.watch_camera(server, session),
            }
        })
        .await
    }

    /// Stops watching a stream (also one still connecting or unreachable): nothing more is
    /// downloaded and its pictures stop. Pictures the app still holds stay valid until it
    /// releases them.
    pub async fn stop_watching_stream(
        &self,
        session: u32,
        kind: StreamKind,
    ) -> Result<(), BridgeError> {
        let parts = self.stream_parts();
        self.run(async move {
            let server = connected_server(&parts.connection)?;
            parts.stop(server, session, kind)
        })
        .await
    }

    /// Sets how loud a person's screen share sound plays, in percent (0–200). Kept like the
    /// person's voice volume and independent of it; it never leaves the phone.
    pub async fn set_stream_volume(&self, session: u32, percent: u16) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(engine.person_set_share_volume(server, session, percent)?)
        })
        .await
    }

    /// Where the news of new pictures goes; `None` stops it. Pictures keep arriving in the
    /// streams' slots meanwhile, the app just is not told.
    pub fn set_video_sink(&self, sink: Option<Arc<dyn VideoSink>>) {
        self.video.set_sink(sink);
    }

    /// The handle for `mumcord_video_frame_acquire` and `mumcord_video_frame_release`
    /// (`bridge/include/mumcord_video.h`). Valid until the process ends.
    pub fn video_handle(&self) -> u64 {
        self.video.handle()
    }

    /// Answers [`BridgeEvent::DirectConnectionsQuestion`]: `true` allows direct connections
    /// between clients (the other person can see the user's IP address), `false` allows only
    /// the relay. The answer is saved and applies to the waiting stream and later ones.
    pub async fn answer_direct_connections(&self, allow: bool) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            engine.p2p_consent_decide(allow).await?;
            lock(&connection).direct_question = false;
            Ok(())
        })
        .await
    }
}

impl MumcordEngine {
    fn stream_parts(&self) -> StreamParts {
        StreamParts {
            engine: self.engine.clone(),
            connection: self.connection.clone(),
            streams: self.streams.clone(),
            video: self.video,
            changed: self.streams_changed.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use mumcord_engine::peer::RelayUnavailable;

    use super::*;

    #[test]
    fn a_share_shows_the_users_watch_before_any_block() {
        let path = || StreamPath::Direct;
        assert_eq!(screen_watch(None, None, path), StreamWatch::Idle);
        assert_eq!(
            screen_watch(None, Some(WatchBlock::NoDirectPath), path),
            StreamWatch::Unreachable {
                reason: StreamUnreachable::NoPath
            }
        );
        assert_eq!(
            screen_watch(Some(ScreenWatch::Connecting), None, path),
            StreamWatch::Connecting
        );
        assert_eq!(
            screen_watch(Some(ScreenWatch::Open), Some(WatchBlock::P2pOff), path),
            StreamWatch::Watching {
                path: StreamPath::Direct
            }
        );
        assert_eq!(
            screen_watch(
                Some(ScreenWatch::Unreachable(StreamUnreachable::Full)),
                None,
                path
            ),
            StreamWatch::Unreachable {
                reason: StreamUnreachable::Full
            }
        );
    }

    #[test]
    fn every_block_has_its_reason() {
        let reasons = [
            (WatchBlock::P2pOff, StreamUnreachable::PeerToPeerOff),
            (
                WatchBlock::ConsentOff,
                StreamUnreachable::DirectConnectionsDeclined,
            ),
            (
                WatchBlock::Relay(RelayUnavailable::NoRelay),
                StreamUnreachable::RelayUnavailable,
            ),
            (
                WatchBlock::PeerUnsupported,
                StreamUnreachable::SharerUnsupported,
            ),
            (WatchBlock::NoDirectPath, StreamUnreachable::NoPath),
        ];
        for (block, reason) in reasons {
            assert_eq!(block_reason(block), reason);
            // A failed watch names the same reason by the block's code.
            let error = EngineError::new(ErrorKind::DirectUnavailable, block.code());
            assert_eq!(unreachable_reason(&error), Some(reason));
        }
    }

    #[test]
    fn failed_watches_are_unreachable_or_errors() {
        let failed = |kind| unreachable_reason(&EngineError::new(kind, "x"));
        assert_eq!(failed(ErrorKind::ShareFull), Some(StreamUnreachable::Full));
        assert_eq!(
            failed(ErrorKind::NeedsUpdate),
            Some(StreamUnreachable::NeedsUpdate)
        );
        assert_eq!(
            failed(ErrorKind::NotFound),
            Some(StreamUnreachable::NoAnswer)
        );
        assert_eq!(failed(ErrorKind::NotConnected), None);
        assert_eq!(failed(ErrorKind::InvalidInput), None);
    }

    #[test]
    fn cameras_follow_their_tile_only_while_watched() {
        assert_eq!(camera_watch(false, TileState::Playing), StreamWatch::Idle);
        assert_eq!(
            camera_watch(true, TileState::Joining),
            StreamWatch::Connecting
        );
        assert_eq!(
            camera_watch(true, TileState::Playing),
            StreamWatch::Watching {
                path: StreamPath::Unknown
            }
        );
        assert_eq!(
            camera_watch(true, TileState::Failed),
            StreamWatch::Unreachable {
                reason: StreamUnreachable::JoinFailed
            }
        );
        assert_eq!(
            camera_watch(true, TileState::Full),
            StreamWatch::Unreachable {
                reason: StreamUnreachable::Full
            }
        );
    }

    #[test]
    fn only_open_watches_want_pictures() {
        let mut streams = Streams::default();
        streams.screens.insert(1, ScreenWatch::Open);
        streams.screens.insert(2, ScreenWatch::Connecting);
        streams.cameras.insert(3);
        assert!(streams.plays(video_id(StreamKind::Screen, 1)));
        assert!(!streams.plays(video_id(StreamKind::Screen, 2)));
        assert!(streams.plays(video_id(StreamKind::Camera, 3)));
        assert!(!streams.plays(video_id(StreamKind::Screen, 3)));
    }

    #[test]
    fn a_new_server_forgets_the_watches_and_an_unchanged_list_is_not_reported() {
        let mut streams = Streams::default();
        streams.on_server(Some(1));
        streams.screens.insert(1, ScreenWatch::Open);
        streams.on_server(Some(1));
        assert_eq!(streams.screens.len(), 1);
        streams.on_server(Some(2));
        assert!(streams.screens.is_empty());
        assert!(streams.report(Vec::new()).is_none());
        let stream = Stream {
            session: 4,
            kind: StreamKind::Screen,
            video_id: video_id(StreamKind::Screen, 4),
            watch: StreamWatch::Idle,
            paused: false,
            has_sound: true,
            volume: 100,
            viewers: Some(0),
        };
        assert!(streams.report(vec![stream.clone()]).is_some());
        assert!(streams.report(vec![stream]).is_none());
    }
}
