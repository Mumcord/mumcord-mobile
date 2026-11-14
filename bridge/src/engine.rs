// SPDX-License-Identifier: AGPL-3.0-only

//! Commands in: the one object the apps hold.

use std::collections::HashMap;
use std::future::Future;
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use mumcord_audio::io::{ExternalCapture, ExternalPlayback};
use mumcord_engine::{
    Engine, PasswordChange, QueryOutcome, SecretStore, ServerFields, ServerId, ServerQueries,
};
use tokio::runtime::{Handle, Runtime};
use tokio::sync::{mpsc, oneshot};

use crate::audio_abi::Endpoint;
use crate::convert;
use crate::error::BridgeError;
use crate::events::{self, BridgeEvent, Connection, EngineListener, SharedConnection, lock};
use crate::storage::{SecureStorage, StorageBackend};
use crate::types::{
    AudioEndpoints, BridgeSnapshot, ConnectRequest, EngineConfig, SavedServer, ServerInput,
    ServerPing, TalkMode, VoiceSettings, WhisperTarget,
};

/// The server list as the app last got it, and the way to tell the app about a change.
#[derive(Clone)]
struct ServerList {
    servers: Arc<Mutex<Vec<SavedServer>>>,
    /// Serialises the commands that change the list, so every change is reported in order.
    gate: Arc<tokio::sync::Mutex<()>>,
    events: mpsc::UnboundedSender<BridgeEvent>,
}

impl ServerList {
    /// Reads the list again after a change and reports it.
    async fn refresh(&self, engine: &Engine) -> Result<(), BridgeError> {
        let servers = saved_servers(engine).await?;
        lock(&self.servers).clone_from(&servers);
        // The event thread is gone only while the engine object is being released.
        let _ = self.events.send(BridgeEvent::ServersChanged { servers });
        Ok(())
    }
}

async fn saved_servers(engine: &Engine) -> Result<Vec<SavedServer>, BridgeError> {
    let servers = engine.servers().await?;
    Ok(servers.into_iter().map(convert::saved_server).collect())
}

/// The Mumcord engine of the app. Create one per process and keep it for the app's lifetime.
#[derive(uniffi::Object)]
pub struct MumcordEngine {
    engine: Engine,
    connection: SharedConnection,
    servers: ServerList,
    /// Ping answers of the listed servers, kept for a while so a redrawn list does not ask again.
    pings: Arc<ServerQueries>,
    /// Serialises `connect`, so two calls cannot both find the connection free.
    connect_gate: Arc<tokio::sync::Mutex<()>>,
    /// The app's ends of the audio, behind the handles of `audio_endpoints`. Emptied on drop.
    capture: &'static Endpoint<ExternalCapture>,
    playback: &'static Endpoint<ExternalPlayback>,
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
        let listed = std::thread::scope(|scope| {
            scope
                .spawn(|| handle.block_on(saved_servers(&engine)))
                .join()
        })
        .map_err(|_| BridgeError::internal("reading the server list failed"))??;
        let connection = SharedConnection::default();
        let (stop_events, stop) = oneshot::channel();
        let (own_events, own) = mpsc::unbounded_channel();
        events::spawn_pump(
            handle.clone(),
            receiver,
            own,
            connection.clone(),
            listener,
            stop,
        )
        .map_err(|e| BridgeError::internal(format!("starting the event thread failed: {e}")))?;
        Ok(Arc::new(Self {
            engine,
            connection,
            servers: ServerList {
                servers: Arc::new(Mutex::new(listed)),
                gate: Arc::default(),
                events: own_events,
            },
            pings: Arc::default(),
            connect_gate: Arc::default(),
            capture: Endpoint::leak(capture),
            playback: Endpoint::leak(playback),
            bitrate_limit_kbps: AtomicU32::new(0),
            handle,
            runtime: Some(runtime),
            _stop_events: stop_events,
        }))
    }

    /// Adds a server to the list. The password goes to the secure storage. The changed list
    /// arrives as [`BridgeEvent::ServersChanged`].
    pub async fn add_server(&self, input: ServerInput) -> Result<SavedServer, BridgeError> {
        let (engine, list) = (self.engine.clone(), self.servers.clone());
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
        let (engine, list) = (self.engine.clone(), self.servers.clone());
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
        let (engine, list) = (self.engine.clone(), self.servers.clone());
        self.run(async move {
            let _gate = list.gate.lock().await;
            engine.server_remove(server_id).await?;
            list.refresh(&engine).await
        })
        .await
    }

    /// Sets the order of the list; `server_ids` names every saved server once.
    pub async fn reorder_servers(&self, server_ids: Vec<i64>) -> Result<(), BridgeError> {
        let (engine, list) = (self.engine.clone(), self.servers.clone());
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
        let target = lock(&self.servers.servers)
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
        let (engine, connection, gate) = (
            self.engine.clone(),
            self.connection.clone(),
            self.connect_gate.clone(),
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
            start(
                &engine,
                &connection,
                server_id,
                &username,
                channel_path,
                password,
            )
            .await
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
            self.servers.clone(),
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

    /// The saved talk mode. Voice activity until the user has chosen one.
    pub async fn talk_mode(&self) -> Result<TalkMode, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { Ok(convert::talk_mode(engine.settings()?.talk_mode)) })
            .await
    }

    /// Saves the talk mode and applies it at once.
    pub async fn set_talk_mode(&self, mode: TalkMode) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let mut settings = engine.settings()?;
            settings.talk_mode = convert::store_talk_mode(mode);
            engine.update_settings(settings).await?;
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
    /// channel, in either talk mode (the hold-to-whisper button is the push-to-talk). Fails
    /// when the user is muted, the target is gone or the server does not allow it.
    pub async fn start_whisper(&self, target: WhisperTarget) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            let receivers = match target {
                WhisperTarget::Person { session } => {
                    convert::whisper_person(lock(&connection).person_name(session))?
                }
                WhisperTarget::Channel { channel_id } => {
                    let channels: HashMap<_, _> = engine
                        .snapshot_of(server)
                        .ok_or_else(BridgeError::not_connected)?
                        .channels
                        .into_iter()
                        .map(|channel| (channel.id, (channel.parent_id, channel.name)))
                        .collect();
                    convert::whisper_channel(channel_id, &channels)?
                }
            };
            Ok(engine.whisper_start(&receivers)?)
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
        let servers = lock(&self.servers.servers).clone();
        let connected = lock(&self.connection).server;
        // Asked before the connection is locked: the engine must not be called under that lock.
        let synced = connected.and_then(|server| self.engine.snapshot_of(server));
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
            whispering: connection.server.is_some()
                && self.engine.dsp_controls().voice_target() != 0,
        }
    }
}

impl MumcordEngine {
    /// Runs a command on the bridge runtime, so the caller's thread is never blocked and the
    /// command finishes even if the caller stops waiting.
    async fn run<T: Send + 'static>(
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

fn connected_server(connection: &SharedConnection) -> Result<ServerId, BridgeError> {
    lock(connection)
        .server
        .ok_or_else(BridgeError::not_connected)
}

async fn connect(
    engine: &Engine,
    connection: &SharedConnection,
    list: &ServerList,
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
    start(engine, connection, server, username, None, request.password).await
}

/// Starts the one connection the bridge allows.
async fn start(
    engine: &Engine,
    connection: &SharedConnection,
    server: ServerId,
    username: &str,
    channel_path: Option<Vec<String>>,
    password: Option<String>,
) -> Result<(), BridgeError> {
    if lock(connection).server.is_some() {
        return Err(BridgeError::invalid("already connected to a server"));
    }
    // First slice only: when there is no identity at all, one is created silently from the
    // user name. The identity flow of phase 6 (create or bring an identity during setup)
    // replaces this. A server that names an identity of its own connects with that one.
    if engine.identity().await?.is_none() {
        engine.identity_create(username.to_owned(), None).await?;
    }
    // A held button of the last connection must not talk or whisper on this one.
    engine.whisper_stop();
    engine.dsp_controls().set_ptt_pressed(false);
    *lock(connection) = Connection::to(server);
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
