// SPDX-License-Identifier: AGPL-3.0-only

//! Commands in: the one object the apps hold.

use std::collections::HashMap;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use mumcord_audio::io::{ExternalCapture, ExternalPlayback};
use mumcord_engine::screen_share::wire::Codec;
use mumcord_engine::store::CameraShow;
use mumcord_engine::{
    Engine, PasswordChange, QueryOutcome, SecretStore, ServerFields, ServerId, ServerQueries,
};
use tokio::runtime::{Handle, Runtime};
use tokio::sync::{mpsc, oneshot};

use crate::audio_abi::Endpoint;
use crate::chat::SharedChat;
use crate::convert;
use crate::error::BridgeError;
use crate::events::{self, BridgeEvent, Connection, EngineListener, SharedConnection, lock};
use crate::identities;
use crate::public_servers::PublicList;
use crate::storage::{SecureStorage, StorageBackend};
use crate::streams::{SharedStreams, StreamKind, StreamParts};
use crate::themes::{self, SharedThemes, ThemeState};
use crate::types::{
    AppSettings, AudioEndpoints, BridgeSnapshot, ConnectRequest, EngineConfig, Identity,
    MobileSettings, SavedServer, ServerInput, ServerPing, TalkMode, VoiceSettings, WhisperTarget,
};
use crate::video::{Router, VideoFrames};

/// What is saved on the phone as the app last got it (the server list, the identities, the
/// phone settings), and the way to tell the app about a change.
#[derive(Clone)]
pub(crate) struct Saved {
    servers: Arc<Mutex<Vec<SavedServer>>>,
    identities: Arc<Mutex<Vec<Identity>>>,
    mobile: Arc<Mutex<MobileSettings>>,
    /// Also read by the event thread, which holds back the notifications switched off here.
    pub(crate) app: Arc<Mutex<AppSettings>>,
    pub(crate) themes: SharedThemes,
    /// Serialises the commands that change the lists, so every change is reported in order.
    pub(crate) gate: Arc<tokio::sync::Mutex<()>>,
    pub(crate) events: mpsc::UnboundedSender<BridgeEvent>,
}

impl Saved {
    /// Reads the server list again after a change and reports it.
    pub(crate) async fn refresh(&self, engine: &Engine) -> Result<(), BridgeError> {
        let (servers, server_themes) = saved_servers(engine).await?;
        lock(&self.servers).clone_from(&servers);
        // The event thread is gone only while the engine object is being released.
        let _ = self.events.send(BridgeEvent::ServersChanged { servers });
        self.set_server_themes(server_themes);
        Ok(())
    }

    /// Reads the identities again after a change and reports them.
    pub(crate) async fn refresh_identities(
        &self,
        engine: &Engine,
    ) -> Result<Vec<Identity>, BridgeError> {
        let identities = identities::list(engine).await?;
        lock(&self.identities).clone_from(&identities);
        let _ = self.events.send(BridgeEvent::IdentitiesChanged {
            identities: identities.clone(),
        });
        Ok(identities)
    }

    pub(crate) fn servers(&self) -> Vec<SavedServer> {
        lock(&self.servers).clone()
    }

    /// Takes over the phone settings as they were just saved and reports them when they
    /// changed.
    fn set_mobile(&self, settings: MobileSettings) {
        let changed = std::mem::replace(&mut *lock(&self.mobile), settings) != settings;
        if changed {
            let _ = self
                .events
                .send(BridgeEvent::MobileSettingsChanged { settings });
        }
    }

    /// Takes over the shared settings as they were just saved and reports them when they
    /// changed.
    fn set_app(&self, settings: AppSettings) {
        let changed = std::mem::replace(&mut *lock(&self.app), settings.clone()) != settings;
        if changed {
            let _ = self
                .events
                .send(BridgeEvent::AppSettingsChanged { settings });
        }
    }
}

/// The server list, and the theme each server names (the list leaves themes out).
async fn saved_servers(
    engine: &Engine,
) -> Result<(Vec<SavedServer>, HashMap<i64, String>), BridgeError> {
    let servers = engine.servers().await?;
    let server_themes = themes::server_theme_ids(&servers);
    Ok((
        servers.into_iter().map(convert::saved_server).collect(),
        server_themes,
    ))
}

/// The Mumcord engine of the app. Create one per process and keep it for the app's lifetime.
#[derive(uniffi::Object)]
pub struct MumcordEngine {
    pub(crate) engine: Engine,
    pub(crate) connection: SharedConnection,
    pub(crate) chat: SharedChat,
    pub(crate) saved: Saved,
    /// The engine's folder; identity files pass through a private folder below it.
    pub(crate) data_dir: PathBuf,
    /// The public server list as last downloaded.
    pub(crate) public: PublicList,
    /// Ping answers of the listed servers, kept for a while so a redrawn list does not ask again.
    pings: Arc<ServerQueries>,
    /// Serialises `connect`, so two calls cannot both find the connection free.
    connect_gate: Arc<tokio::sync::Mutex<()>>,
    /// The app's ends of the audio, behind the handles of `audio_endpoints`. Emptied on drop.
    capture: &'static Endpoint<ExternalCapture>,
    playback: &'static Endpoint<ExternalPlayback>,
    /// The watches of the streams and the list as last reported.
    pub(crate) streams: SharedStreams,
    /// Wakes the event thread to look at the streams again after a command.
    pub(crate) streams_changed: Arc<tokio::sync::Notify>,
    /// The pictures of the watched streams, behind the handle of `video_handle`. Emptied on drop.
    pub(crate) video: &'static VideoFrames,
    /// The engine does not tell its runtime bitrate limit; 0 is none.
    bitrate_limit_kbps: AtomicU32,
    handle: Handle,
    /// Taken on drop.
    runtime: Option<Runtime>,
    /// Dropping it ends the event thread.
    _stop_events: oneshot::Sender<()>,
}

#[uniffi::export]
impl MumcordEngine {
    /// Opens the engine's database in `config.data_dir` and starts delivering events to
    /// `listener`. Reads the database and the secure storage, so call it off the main thread.
    #[uniffi::constructor]
    pub fn new(
        config: EngineConfig,
        storage: Arc<dyn SecureStorage>,
        listener: Arc<dyn EngineListener>,
    ) -> Result<Arc<Self>, BridgeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("mumcord-bridge")
            .build()
            .map_err(|e| BridgeError::internal(format!("starting the runtime failed: {e}")))?;
        let handle = runtime.handle().clone();
        let secrets = SecretStore::with_backend(Arc::new(StorageBackend(storage)));
        let (capture_format, playback_format) = convert::audio_formats(config.audio);
        let (audio, capture, playback) =
            mumcord_audio::io::external(capture_format, playback_format)
                .map_err(|e| BridgeError::invalid(&format!("audio configuration: {e}")))?;
        let (engine, receiver) = {
            // The engine starts its background tasks while it is created.
            let _runtime = runtime.enter();
            // Phones have no audio devices the engine could open itself.
            Engine::with_external_audio(
                Path::new(&config.data_dir),
                secrets,
                handle.clone(),
                audio,
            )?
        };
        // On a thread of its own: the caller may itself be inside an async runtime, where
        // waiting for the runtime is not allowed.
        let data_dir = PathBuf::from(&config.data_dir);
        // What an identity import or export left behind when the app was ended in the middle.
        identities::sweep(&data_dir);
        let ((listed, server_themes), known) = std::thread::scope(|scope| {
            scope
                .spawn(|| {
                    handle.block_on(async {
                        fetch_cameras_on_request(&engine).await?;
                        Ok::<_, BridgeError>((
                            saved_servers(&engine).await?,
                            identities::list(&engine).await?,
                        ))
                    })
                })
                .join()
        })
        .map_err(|_| BridgeError::internal("reading the server list failed"))??;
        let settings = engine.settings()?;
        let mobile = convert::mobile_settings(&settings.mobile);
        let app = Arc::new(Mutex::new(convert::app_settings(&settings)));
        let themes = Arc::new(Mutex::new(ThemeState::new(&settings, server_themes)));
        let connection = SharedConnection::default();
        let chat = SharedChat::default();
        let video = VideoFrames::start()
            .map_err(|e| BridgeError::internal(format!("starting the video thread failed: {e}")))?;
        engine.set_screen_frame_sink(Arc::new(Router {
            frames: video,
            kind: StreamKind::Screen,
        }));
        engine.camera_set_frame_sink(Arc::new(Router {
            frames: video,
            kind: StreamKind::Camera,
        }));
        engine.camera_set_decode_codecs(&[Codec::Vp9, Codec::Av1]);
        let streams = SharedStreams::default();
        let streams_changed = Arc::new(tokio::sync::Notify::new());
        let (stop_events, stop) = oneshot::channel();
        let (own_events, own) = mpsc::unbounded_channel();
        events::spawn_pump(
            handle.clone(),
            receiver,
            own,
            events::PumpState {
                engine: engine.clone(),
                connection: connection.clone(),
                chat: chat.clone(),
                app: app.clone(),
                streams: StreamParts {
                    engine: engine.clone(),
                    connection: connection.clone(),
                    streams: streams.clone(),
                    video,
                    changed: streams_changed.clone(),
                },
            },
            listener,
            stop,
        )
        .map_err(|e| BridgeError::internal(format!("starting the event thread failed: {e}")))?;
        Ok(Arc::new(Self {
            engine,
            connection,
            chat,
            saved: Saved {
                servers: Arc::new(Mutex::new(listed)),
                identities: Arc::new(Mutex::new(known)),
                mobile: Arc::new(Mutex::new(mobile)),
                app,
                themes,
                gate: Arc::default(),
                events: own_events,
            },
            data_dir,
            public: PublicList::default(),
            pings: Arc::default(),
            connect_gate: Arc::default(),
            capture: Endpoint::leak(capture),
            playback: Endpoint::leak(playback),
            streams,
            streams_changed,
            video,
            bitrate_limit_kbps: AtomicU32::new(0),
            handle,
            runtime: Some(runtime),
            _stop_events: stop_events,
        }))
    }

    /// Adds a server to the list. The password goes to the secure storage. The changed list
    /// arrives as [`BridgeEvent::ServersChanged`].
    pub async fn add_server(&self, input: ServerInput) -> Result<SavedServer, BridgeError> {
        let (engine, list) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = list.gate.lock().await;
            let identity = input.identity_id;
            let mut added = engine.server_add(convert::server_fields(input)).await?;
            if identity.is_some() {
                added = engine.server_set_identity(added.id, identity).await?;
            }
            list.refresh(&engine).await?;
            Ok(convert::saved_server(added))
        })
        .await
    }

    /// Changes a server of the list. A running connection to it is left as it is.
    pub async fn update_server(
        &self,
        server_id: i64,
        input: ServerInput,
    ) -> Result<SavedServer, BridgeError> {
        let (engine, list) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = list.gate.lock().await;
            let identity = input.identity_id;
            let mut updated = engine
                .server_update(server_id, convert::server_fields(input))
                .await?;
            if updated.identity_id != identity {
                updated = engine.server_set_identity(server_id, identity).await?;
            }
            list.refresh(&engine).await?;
            Ok(convert::saved_server(updated))
        })
        .await
    }

    /// Removes a server from the list, with its saved password.
    pub async fn remove_server(&self, server_id: i64) -> Result<(), BridgeError> {
        let (engine, list) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = list.gate.lock().await;
            engine.server_remove(server_id).await?;
            list.refresh(&engine).await
        })
        .await
    }

    /// Sets the order of the list; `server_ids` names every saved server once.
    pub async fn reorder_servers(&self, server_ids: Vec<i64>) -> Result<(), BridgeError> {
        let (engine, list) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = list.gate.lock().await;
            engine.servers_reorder(&server_ids)?;
            list.refresh(&engine).await
        })
        .await
    }

    /// Asks a saved server for its people online and the latency, without connecting. `None`
    /// when it does not answer. Answers are reused for a minute and a server that stayed silent
    /// is not asked again for ten; `refresh` asks in any case.
    pub async fn ping_server(
        &self,
        server_id: i64,
        refresh: bool,
    ) -> Result<Option<ServerPing>, BridgeError> {
        let target = self
            .saved
            .servers()
            .iter()
            .find(|server| server.id == server_id)
            .map(|server| (server.address.clone(), server.port))
            .ok_or_else(unknown_server)?;
        let pings = self.pings.clone();
        self.run(async move {
            Ok(match pings.query(&target.0, target.1, refresh).await {
                QueryOutcome::Answered(answer) => Some(convert::server_ping(&answer)),
                QueryOutcome::Unreachable | QueryOutcome::NotAllowed => None,
            })
        })
        .await
    }

    /// Starts connecting to a saved server; progress and the result arrive as events
    /// ([`BridgeEvent::ConnectionChanged`], a certificate prompt, then
    /// [`BridgeEvent::ServerSynced`]). `password` replaces the saved one for this connection
    /// only (retry after a wrong password); a non-empty `channel_path` (from a link) is joined
    /// after connecting. Only one connection at a time: fails with `InvalidInput` while another
    /// one is connected, connecting or reconnecting.
    pub async fn connect_server(
        &self,
        server_id: i64,
        password: Option<String>,
        channel_path: Vec<String>,
    ) -> Result<(), BridgeError> {
        let (engine, connection, gate, saved) = (
            self.engine.clone(),
            self.connection.clone(),
            self.connect_gate.clone(),
            self.saved.clone(),
        );
        self.run(async move {
            let _gate = gate.lock().await;
            let username = engine
                .servers()
                .await?
                .into_iter()
                .find(|server| server.id == server_id)
                .map(|server| server.username)
                .ok_or_else(unknown_server)?;
            let channel_path = Some(channel_path).filter(|path| !path.is_empty());
            let server = (server_id, username.as_str());
            start(&engine, &connection, &saved, server, channel_path, password).await
        })
        .await
    }

    /// Connects to an address, saving it as a server when it is not in the list yet. The call of
    /// the first slice; task 2.4 (server list) removes it in favour of
    /// [`MumcordEngine::connect_server`].
    pub async fn connect(&self, request: ConnectRequest) -> Result<(), BridgeError> {
        let (engine, connection, gate, list) = (
            self.engine.clone(),
            self.connection.clone(),
            self.connect_gate.clone(),
            self.saved.clone(),
        );
        self.run(async move {
            let _gate = gate.lock().await;
            connect(&engine, &connection, &list, request).await
        })
        .await
    }

    /// Joins a channel. With `password` the join is retried with that channel password (access
    /// token), which is then kept in the secure storage for this server. A refusal arrives as
    /// [`BridgeEvent::ChannelPasswordChanged`] (`Required` or `Wrong`) or
    /// [`BridgeEvent::ActionDenied`].
    pub async fn join_channel(
        &self,
        channel_id: u32,
        password: Option<String>,
    ) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            match password {
                Some(password) => {
                    engine
                        .channel_join_with_password(server, channel_id, password)
                        .await?;
                }
                None => engine.join_channel(server, channel_id)?,
            }
            Ok(())
        })
        .await
    }

    /// Sets how loud a person is for the user, in percent (0–200). It never leaves the phone.
    pub async fn set_person_volume(&self, session: u32, percent: u16) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(engine.person_set_volume(server, session, percent)?)
        })
        .await
    }

    /// Mutes a person for the user only. It never leaves the phone.
    pub async fn set_person_local_mute(
        &self,
        session: u32,
        muted: bool,
    ) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(engine.person_set_local_mute(server, session, muted)?)
        })
        .await
    }

    /// The saved talk mode. Voice activity until the user has chosen one
    /// ([`MobileSettings::talk_mode_chosen`]).
    pub async fn talk_mode(&self) -> Result<TalkMode, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { Ok(convert::talk_mode(engine.settings()?.talk_mode)) })
            .await
    }

    /// Saves the talk mode and applies it at once. The talk mode then counts as chosen
    /// ([`MobileSettings::talk_mode_chosen`], reported as
    /// [`BridgeEvent::MobileSettingsChanged`]).
    pub async fn set_talk_mode(&self, mode: TalkMode) -> Result<(), BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let mut settings = engine.settings()?;
            settings.talk_mode = convert::store_talk_mode(mode);
            settings.mobile.talk_mode_chosen = true;
            let settings = engine.update_settings(settings).await?;
            saved.set_mobile(convert::mobile_settings(&settings.mobile));
            Ok(())
        })
        .await
    }

    /// The phone settings; also part of the snapshot.
    pub fn mobile_settings(&self) -> MobileSettings {
        *lock(&self.saved.mobile)
    }

    /// Saves the phone settings; they arrive as [`BridgeEvent::MobileSettingsChanged`].
    /// `talk_mode_chosen` is never cleared by it: `set_talk_mode` sets it once.
    pub async fn set_mobile_settings(&self, settings: MobileSettings) -> Result<(), BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let mut all = engine.settings()?;
            all.mobile = convert::store_mobile_settings(settings, &all.mobile);
            let all = engine.update_settings(all).await?;
            saved.set_mobile(convert::mobile_settings(&all.mobile));
            Ok(())
        })
        .await
    }

    /// The settings shared with the desktop's settings model: language, chat and
    /// notifications.
    pub fn app_settings(&self) -> AppSettings {
        lock(&self.saved.app).clone()
    }

    /// Saves them; they arrive as [`BridgeEvent::AppSettingsChanged`]. Fails with
    /// `InvalidInput` for a language that is not one of the 24. A changed history age deletes
    /// older chat history soon after.
    pub async fn set_app_settings(&self, settings: AppSettings) -> Result<(), BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let all = convert::store_app_settings(settings, engine.settings()?)?;
            let all = engine.update_settings(all).await?;
            saved.set_app(convert::app_settings(&all));
            Ok(())
        })
        .await
    }

    /// The handles the app's audio threads pass to the C functions of
    /// `bridge/include/mumcord_audio.h`. The same values for the engine object's whole life.
    pub fn audio_endpoints(&self) -> AudioEndpoints {
        AudioEndpoints {
            capture: self.capture.handle(),
            playback: self.playback.handle(),
        }
    }

    /// The hold-to-talk button went down (`true`) or up. Only matters in push-to-talk mode.
    /// Takes effect at once; safe to call from the main thread.
    pub fn set_push_to_talk_pressed(&self, pressed: bool) {
        self.engine.dsp_controls().set_ptt_pressed(pressed);
    }

    /// Mutes or unmutes the user. Unmuting also ends a deafen. The new state arrives as
    /// [`BridgeEvent::PersonChanged`] of the user themselves.
    pub async fn set_self_mute(&self, muted: bool) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { Ok(engine.set_self_mute(muted)?) })
            .await
    }

    /// Deafens the user, which also mutes them, or ends it; the mute then goes back to what it
    /// was before. The new state arrives as [`BridgeEvent::PersonChanged`] of the user
    /// themselves.
    pub async fn set_self_deaf(&self, deafened: bool) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { Ok(engine.set_self_deaf(deafened)?) })
            .await
    }

    /// The voice settings as they apply now.
    pub async fn voice_settings(&self) -> Result<VoiceSettings, BridgeError> {
        let engine = self.engine.clone();
        let limit = self.bitrate_limit_kbps.load(Ordering::Relaxed);
        self.run(async move {
            let settings = engine.settings()?;
            Ok(VoiceSettings {
                talk_mode: convert::talk_mode(settings.talk_mode),
                noise_suppression: settings.noise_suppression,
                auto_gain: settings.auto_gain,
                echo_cancellation: convert::echo_cancellation(engine.echo_cancellation_mode()),
                bitrate_limit_kbps: Some(limit).filter(|&kbps| kbps > 0),
            })
        })
        .await
    }

    /// Saves whether noise suppression runs and applies it at once.
    pub async fn set_noise_suppression(&self, on: bool) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let mut settings = engine.settings()?;
            settings.noise_suppression = on;
            engine.update_settings(settings).await?;
            Ok(())
        })
        .await
    }

    /// Saves whether the automatic gain runs and applies it at once.
    pub async fn set_auto_gain(&self, on: bool) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let mut settings = engine.settings()?;
            settings.auto_gain = on;
            engine.update_settings(settings).await?;
            Ok(())
        })
        .await
    }

    /// Limits the voice bitrate in kbit/s, `None` lifts the limit ("Reduced voice quality": 24
    /// while on mobile data). Not saved: the app sets it again after a start and whenever the
    /// network changes. Takes effect within a second; 0 is not a limit.
    pub fn set_bitrate_limit_kbps(&self, kbps: Option<u32>) {
        let kbps = kbps.filter(|&kbps| kbps > 0);
        self.bitrate_limit_kbps
            .store(kbps.unwrap_or(0), Ordering::Relaxed);
        self.engine.set_bitrate_limit_kbps(kbps);
    }

    /// Starts a whisper to a person or a shout to a channel: until
    /// [`MumcordEngine::stop_whisper`] the user's voice goes there instead of their own
    /// channel, in either talk mode (the hold-to-whisper button is the push-to-talk). A whisper
    /// stays with the person it was started for: when they leave the server, or the connection
    /// ends, it is over and [`BridgeEvent::WhisperEnded`] arrives. Fails with `InvalidInput`
    /// when the user is muted, `NotFound` when the target is gone, `PermissionDenied` when the
    /// server does not allow it and `NotConnected` without voice on the server.
    pub async fn start_whisper(&self, target: WhisperTarget) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(engine.whisper_start_to(server, &convert::whisper_to(target))?)
        })
        .await
    }

    /// Ends the whisper or shout; talking goes back to the user's own channel. Does nothing
    /// when none is held.
    pub fn stop_whisper(&self) {
        self.engine.whisper_stop();
    }

    /// Answers the pending certificate prompt with that `fingerprint`: `trust = true` saves the
    /// certificate as trusted and goes on connecting, `false` ends the attempt.
    pub async fn answer_certificate_prompt(
        &self,
        fingerprint: String,
        trust: bool,
    ) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = lock(&connection)
                .server
                .ok_or_else(|| BridgeError::invalid("no certificate question is pending"))?;
            engine.cert_trust_decide(server, &fingerprint, trust)?;
            let mut connection = lock(&connection);
            if connection
                .certificate_prompt
                .as_ref()
                .is_some_and(|prompt| prompt.fingerprint == fingerprint)
            {
                connection.certificate_prompt = None;
            }
            Ok(())
        })
        .await
    }

    /// Ends the connection, also while it is connecting, reconnecting or waiting for a
    /// certificate answer. The end is reported as `ConnectionChanged` with `Disconnected`; a new
    /// `connect` is possible after that. Does nothing when not connected.
    pub async fn disconnect(&self) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = lock(&connection).server;
            if let Some(server) = server {
                engine.disconnect(server);
            }
            Ok(())
        })
        .await
    }

    /// The full current state.
    pub fn snapshot(&self) -> BridgeSnapshot {
        let servers = self.saved.servers();
        let connected = lock(&self.connection).server;
        // Asked before the connection is locked: the engine must not be called under that lock.
        let synced = connected.and_then(|server| self.engine.snapshot_of(server));
        let (chat_unread, open_chat) = {
            let chat = lock(&self.chat);
            (chat.unread_list(), chat.open.clone())
        };
        // Before the connection is locked: the streams are always locked first.
        let streams = lock(&self.streams).reported.clone();
        let mut connection = lock(&self.connection);
        // The connection may have ended or changed meanwhile; its state then has no server.
        let server = synced
            .filter(|_| connection.server == connected)
            .map(|synced| connection.server_state(synced));
        BridgeSnapshot {
            servers,
            connection: connection.state.clone(),
            connected_server_id: connection.server,
            certificate_prompt: connection.certificate_prompt.clone(),
            server,
            audio: connection.audio,
            chat_unread,
            open_chat,
            whispering: connection.server.is_some()
                && self.engine.dsp_controls().voice_target() != 0,
            identities: lock(&self.saved.identities).clone(),
            mobile_settings: self.mobile_settings(),
            streams,
            direct_connections_question: connection.direct_question,
        }
    }
}

impl MumcordEngine {
    /// Runs a command on the bridge runtime, so the caller's thread is never blocked and the
    /// command finishes even if the caller stops waiting.
    pub(crate) fn runtime(&self) -> Handle {
        self.handle.clone()
    }

    pub(crate) async fn run<T: Send + 'static>(
        &self,
        command: impl Future<Output = Result<T, BridgeError>> + Send + 'static,
    ) -> Result<T, BridgeError> {
        self.handle
            .spawn(command)
            .await
            .map_err(|e| BridgeError::internal(format!("the command was aborted: {e}")))?
    }
}

impl Drop for MumcordEngine {
    fn drop(&mut self) {
        if let Some(server) = lock(&self.connection).server {
            self.engine.disconnect(server);
        }
        // From here on the app's audio callbacks find nothing behind their handles.
        self.capture.close();
        self.playback.close();
        self.video.shut_down();
        if let Some(runtime) = self.runtime.take() {
            // The object can be released from any thread, also from inside the runtime, where a
            // blocking shutdown would panic.
            runtime.shutdown_background();
        }
    }
}

fn unknown_server() -> BridgeError {
    BridgeError::NotFound {
        detail: "unknown server".to_owned(),
    }
}

/// The phone fetches a camera only when the user watches it (AC-5.23): the engine must not open
/// the camera room by itself when the first camera turns on, as the desktop's default does.
async fn fetch_cameras_on_request(engine: &Engine) -> Result<(), BridgeError> {
    let mut settings = engine.settings()?;
    if settings.camera_show != CameraShow::OnOpen {
        settings.camera_show = CameraShow::OnOpen;
        engine.update_settings(settings).await?;
    }
    Ok(())
}

pub(crate) fn connected_server(connection: &SharedConnection) -> Result<ServerId, BridgeError> {
    lock(connection)
        .server
        .ok_or_else(BridgeError::not_connected)
}

async fn connect(
    engine: &Engine,
    connection: &SharedConnection,
    list: &Saved,
    request: ConnectRequest,
) -> Result<(), BridgeError> {
    let address = request.address.trim();
    let username = request.username.trim();
    if address.is_empty() {
        return Err(BridgeError::invalid("address is empty"));
    }
    if username.is_empty() {
        return Err(BridgeError::invalid("user name is empty"));
    }
    if lock(connection).server.is_some() {
        return Err(BridgeError::invalid("already connected to a server"));
    }
    let server = {
        let _gate = list.gate.lock().await;
        let known = engine.servers().await?.len();
        let server = saved_server(engine, address, request.port, username).await?;
        if engine.servers().await?.len() != known {
            list.refresh(engine).await?;
        }
        server
    };
    // The password is used for this connection only; nothing is saved.
    let server = (server, username);
    start(engine, connection, list, server, None, request.password).await
}

/// Starts the one connection the bridge allows, to `server` (its id and user name).
async fn start(
    engine: &Engine,
    connection: &SharedConnection,
    saved: &Saved,
    (server, username): (ServerId, &str),
    channel_path: Option<Vec<String>>,
    password: Option<String>,
) -> Result<(), BridgeError> {
    if lock(connection).server.is_some() {
        return Err(BridgeError::invalid("already connected to a server"));
    }
    // Temporary, until both apps create or bring an identity during setup
    // (`ensure_default_identity`): when there is no identity at all, one is created silently
    // from the user name. Then this becomes `BridgeError::NoIdentity`. A server that names an
    // identity of its own connects with that one.
    identities::ensure_default(engine, saved, username).await?;
    // Logged without detail: an error text could name a person.
    let blocked = engine.blocked_users().unwrap_or_else(|_| {
        tracing::warn!("reading the blocked people failed");
        Vec::new()
    });
    *lock(connection) = Connection::to(server, blocked);
    let started = engine.connect_with_password(server, channel_path, password);
    if started.is_err() {
        lock(connection).server = None;
    }
    Ok(started?)
}

/// The saved server for this address and user name; added when there is none. The engine
/// connects saved servers only (it keeps the trusted certificate with them).
async fn saved_server(
    engine: &Engine,
    address: &str,
    port: u16,
    username: &str,
) -> Result<ServerId, BridgeError> {
    let known = engine
        .servers()
        .await?
        .into_iter()
        .find(|s| s.address == address && s.port == port && s.username == username);
    if let Some(server) = known {
        return Ok(server.id);
    }
    let added = engine
        .server_add(ServerFields {
            label: address.to_owned(),
            address: address.to_owned(),
            port,
            username: username.to_owned(),
            password: PasswordChange::Keep,
        })
        .await?;
    Ok(added.id)
}
