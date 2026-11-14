// SPDX-License-Identifier: AGPL-3.0-only

//! The bridge against the local 1.5 test server of the Mumcord repository, as the apps use it:
//! `testenv/up.sh v15` there, then
//! `MUMCORD_LIVE=1 cargo test -p mumcord-mobile-bridge --test live -- --ignored`.
//! `MUMCORD_PORT_BASE` selects the port of a parallel instance (default 64738), `SERVER_PASSWORD`
//! the connect password if the instance has one.

use std::collections::HashMap;
use std::path::Path;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use mumcord_mobile::{
    BridgeError, BridgeEvent, CertificatePrompt, ConnectRequest, ConnectionState, EngineConfig,
    EngineListener, MumcordEngine, PasswordInput, SavedServer, SecureStorage, SecureStorageError,
    ServerInput, ServerState, TalkMode,
};

const WAIT: Duration = Duration::from_secs(20);

#[derive(Default)]
struct MemoryStorage(Mutex<HashMap<String, Vec<u8>>>);

impl SecureStorage for MemoryStorage {
    fn set(&self, account: String, secret: Vec<u8>) -> Result<(), SecureStorageError> {
        self.0.lock().expect("storage").insert(account, secret);
        Ok(())
    }

    fn get(&self, account: String) -> Result<Option<Vec<u8>>, SecureStorageError> {
        Ok(self.0.lock().expect("storage").get(&account).cloned())
    }

    fn delete(&self, account: String) -> Result<(), SecureStorageError> {
        self.0.lock().expect("storage").remove(&account);
        Ok(())
    }
}

/// Forwards every event to the test, with the name of the thread it arrived on.
struct Recorder(Mutex<Sender<(BridgeEvent, Option<String>)>>);

impl EngineListener for Recorder {
    fn on_event(&self, event: BridgeEvent) {
        let thread = std::thread::current().name().map(str::to_owned);
        // The test may have finished already; late events are of no interest then.
        let _ = self.0.lock().expect("sender").send((event, thread));
    }
}

/// Waits for the first event matching `pick`, skipping others.
fn wait_for<T>(events: &Events, what: &str, mut pick: impl FnMut(BridgeEvent) -> Option<T>) -> T {
    let deadline = Instant::now() + WAIT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let (event, thread) = events
            .recv_timeout(left)
            .unwrap_or_else(|_| panic!("timed out waiting for {what}"));
        assert_eq!(thread.as_deref(), Some("mumcord-bridge-events"));
        if let BridgeEvent::ConnectionChanged {
            state: ConnectionState::Disconnected { error: Some(error) },
        } = &event
        {
            panic!("connection failed while waiting for {what}: {error}");
        }
        if let Some(found) = pick(event) {
            return found;
        }
    }
}

fn wait_prompt(events: &Events) -> CertificatePrompt {
    wait_for(events, "the certificate prompt", |e| match e {
        BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt),
        _ => None,
    })
}

fn wait_synced(events: &Events) -> ServerState {
    wait_for(events, "the synced server", |e| match e {
        BridgeEvent::ServerSynced { server } => Some(server),
        BridgeEvent::CertificatePromptRequired { .. } => panic!("unexpected certificate prompt"),
        _ => None,
    })
}

fn wait_disconnected(events: &Events) {
    wait_for(events, "the disconnect", |e| match e {
        BridgeEvent::ConnectionChanged {
            state: ConnectionState::Disconnected { error: None },
        } => Some(()),
        _ => None,
    });
}

fn port() -> u16 {
    std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(64738)
}

fn request(username: &str) -> ConnectRequest {
    ConnectRequest {
        address: "127.0.0.1".into(),
        port: port(),
        username: username.into(),
        password: std::env::var("SERVER_PASSWORD")
            .ok()
            .filter(|password| !password.is_empty()),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "needs testenv v15 (MUMCORD_LIVE=1)"]
async fn connect_answer_certificate_prompt_snapshot_disconnect() {
    if std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1") {
        run().await;
    } else {
        eprintln!("skipped: set MUMCORD_LIVE=1 and start testenv/up.sh v15");
    }
}

type Events = Receiver<(BridgeEvent, Option<String>)>;

/// A fresh engine with its own data folder `name`.
fn fresh_engine(name: &str) -> (Arc<MumcordEngine>, Arc<MemoryStorage>, Events) {
    let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join(name);
    let _ = std::fs::remove_dir_all(&dir);
    let storage = Arc::new(MemoryStorage::default());
    let (sender, events) = channel();
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: dir.to_string_lossy().into_owned(),
            audio: None,
        },
        storage.clone(),
        Arc::new(Recorder(Mutex::new(sender))),
    )
    .expect("engine");
    (engine, storage, events)
}

async fn run() {
    let username = format!("mc-bridge-{:06x}", std::process::id() & 0xff_ffff);
    let (engine, storage, events) = fresh_engine("bridge-live");

    let start = engine.snapshot();
    assert_eq!(
        start.connection,
        ConnectionState::Disconnected { error: None }
    );
    assert_eq!(start.certificate_prompt, None);
    assert_eq!(start.server, None);
    assert_eq!(start.servers, []);
    assert_eq!(start.connected_server_id, None);

    // An unknown certificate: connecting stops and waits for the answer.
    engine.connect(request(&username)).await.expect("connect");
    let prompt = wait_prompt(&events);
    assert_eq!(prompt.previous_fingerprint, None);
    let waiting = engine.snapshot();
    assert_eq!(waiting.connection, ConnectionState::Connecting);
    assert_eq!(waiting.certificate_prompt, Some(prompt.clone()));
    assert_eq!(waiting.server, None);
    assert!(
        !storage.0.lock().expect("storage").is_empty(),
        "the identity is kept in the secure storage"
    );

    // Only one connection at a time.
    let second = engine.connect(request(&username)).await;
    assert!(
        matches!(second, Err(BridgeError::InvalidInput { .. })),
        "{second:?}"
    );

    engine
        .answer_certificate_prompt(prompt.fingerprint.clone(), true)
        .await
        .expect("trust");
    let synced = wait_synced(&events);

    let snapshot = engine.snapshot();
    assert_eq!(snapshot.connection, ConnectionState::Connected);
    assert_eq!(snapshot.certificate_prompt, None);
    let server = snapshot.server.expect("server state");
    // The root channel carries the server's name, so it is found by its place in the tree.
    let root = server
        .channels
        .iter()
        .find(|c| c.parent_id.is_none())
        .expect("root channel");
    for name in ["Lobby", "Raid", "AFK"] {
        let channel = server
            .channels
            .iter()
            .find(|c| c.name == name)
            .unwrap_or_else(|| panic!("channel {name} missing"));
        assert_eq!(channel.parent_id, Some(root.id), "parent of {name}");
    }
    assert_eq!(synced.self_session, server.self_session);
    let me = server
        .people
        .iter()
        .find(|p| p.session == server.self_session)
        .expect("own person");
    assert_eq!(me.name, username);
    assert!(
        server.channels.iter().any(|c| c.id == me.channel_id),
        "own channel is in the tree"
    );

    engine.disconnect().await.expect("disconnect");
    wait_disconnected(&events);
    let ended = engine.snapshot();
    assert_eq!(
        ended.connection,
        ConnectionState::Disconnected { error: None }
    );
    assert_eq!(ended.server, None);

    // The certificate is trusted now: no second prompt.
    engine.connect(request(&username)).await.expect("reconnect");
    wait_synced(&events);
    engine.disconnect().await.expect("disconnect");
    wait_disconnected(&events);

    // Declining a certificate is covered by the engine; here the answer for a prompt that does
    // not exist must fail cleanly.
    let stray = engine
        .answer_certificate_prompt(prompt.fingerprint, true)
        .await;
    assert!(
        matches!(stray, Err(BridgeError::InvalidInput { .. })),
        "{stray:?}"
    );
}

fn input(name: &str, username: &str, password: PasswordInput) -> ServerInput {
    ServerInput {
        name: name.into(),
        address: "127.0.0.1".into(),
        port: port(),
        username: username.into(),
        password,
        identity_id: None,
    }
}

fn wait_servers(events: &Events) -> Vec<SavedServer> {
    wait_for(events, "the changed server list", |e| match e {
        BridgeEvent::ServersChanged { servers } => Some(servers),
        _ => None,
    })
}

/// Waits until the user themselves are reported in `channel_id`.
fn wait_self_in(events: &Events, self_session: u32, channel_id: u32) {
    wait_for(events, "the own move", |e| match e {
        BridgeEvent::PersonChanged { person }
            if person.session == self_session && person.channel_id == channel_id =>
        {
            assert!(person.is_self);
            Some(())
        }
        _ => None,
    });
}

fn channel_named(server: &ServerState, name: &str) -> u32 {
    server
        .channels
        .iter()
        .find(|c| c.name == name)
        .unwrap_or_else(|| panic!("channel {name} missing"))
        .id
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "needs testenv v15 (MUMCORD_LIVE=1)"]
async fn server_list_connect_by_id_join_ping_and_settings() {
    if std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1") {
        run_server_list().await;
    } else {
        eprintln!("skipped: set MUMCORD_LIVE=1 and start testenv/up.sh v15");
    }
}

async fn run_server_list() {
    let username = format!("mc-list-{:06x}", std::process::id() & 0xff_ffff);
    let (engine, storage, events) = fresh_engine("bridge-live-servers");

    // Add: the password goes to the secure storage and never comes back.
    let secret = "not-the-server-password";
    let added = engine
        .add_server(input(
            "Test server",
            &username,
            PasswordInput::Set {
                value: secret.into(),
            },
        ))
        .await
        .expect("add");
    assert_eq!(added.name, "Test server");
    assert!(added.has_password);
    assert_eq!(added.identity_id, None);
    assert_eq!(wait_servers(&events), std::slice::from_ref(&added));
    assert_eq!(engine.snapshot().servers, std::slice::from_ref(&added));
    assert!(!format!("{:?}", engine.snapshot()).contains(secret));
    assert!(
        storage
            .0
            .lock()
            .expect("storage")
            .values()
            .any(|value| value
                .windows(secret.len())
                .any(|part| part == secret.as_bytes())),
        "the password is kept in the secure storage"
    );

    // Reorder, edit, remove.
    let second = engine
        .add_server(input("", "SuperUser", PasswordInput::Keep))
        .await
        .expect("add second");
    assert_eq!(second.name, "127.0.0.1", "an empty name uses the address");
    assert!(!second.has_password);
    wait_servers(&events);
    engine
        .reorder_servers(vec![second.id, added.id])
        .await
        .expect("reorder");
    let order: Vec<i64> = wait_servers(&events).iter().map(|s| s.id).collect();
    assert_eq!(order, [second.id, added.id]);
    let partial = engine.reorder_servers(vec![added.id]).await;
    assert!(
        matches!(partial, Err(BridgeError::InvalidInput { .. })),
        "{partial:?}"
    );
    let edited = engine
        .update_server(added.id, input("Renamed", &username, PasswordInput::Clear))
        .await
        .expect("update");
    assert_eq!(edited.name, "Renamed");
    assert!(!edited.has_password);
    assert!(wait_servers(&events).contains(&edited));
    let invalid = engine.add_server(input("x", "", PasswordInput::Keep)).await;
    assert!(
        matches!(invalid, Err(BridgeError::InvalidInput { .. })),
        "{invalid:?}"
    );

    // Ping without connecting.
    let ping = engine
        .ping_server(edited.id, true)
        .await
        .expect("ping")
        .expect("the test server answers the ping");
    assert!(ping.max_people > 0, "{ping:?}");
    let unknown = engine.ping_server(i64::MAX, true).await;
    assert!(
        matches!(unknown, Err(BridgeError::NotFound { .. })),
        "{unknown:?}"
    );

    // Settings.
    assert_eq!(
        engine.talk_mode().await.expect("talk mode"),
        TalkMode::VoiceActivity
    );
    engine
        .set_talk_mode(TalkMode::PushToTalk)
        .await
        .expect("set talk mode");
    assert_eq!(
        engine.talk_mode().await.expect("talk mode"),
        TalkMode::PushToTalk
    );

    // Connect by id, then join a channel.
    let missing = engine.connect_server(i64::MAX, None, Vec::new()).await;
    assert!(
        matches!(missing, Err(BridgeError::NotFound { .. })),
        "{missing:?}"
    );
    let not_connected = engine.join_channel(0, None).await;
    assert!(
        matches!(not_connected, Err(BridgeError::NotConnected { .. })),
        "{not_connected:?}"
    );
    engine
        .connect_server(edited.id, None, Vec::new())
        .await
        .expect("connect");
    let prompt = wait_prompt(&events);
    assert_eq!(engine.snapshot().connected_server_id, Some(edited.id));
    engine
        .answer_certificate_prompt(prompt.fingerprint, true)
        .await
        .expect("trust");
    let synced = wait_synced(&events);
    let me = synced
        .people
        .iter()
        .find(|p| p.is_self)
        .expect("own person");
    assert_eq!(me.session, synced.self_session);
    assert_eq!(me.name, username);
    assert_eq!(me.local_volume, 100);
    let lobby = channel_named(&synced, "Lobby");
    assert_ne!(me.channel_id, lobby);
    assert!(
        synced.channels.iter().all(|c| c.can_enter && !c.restricted),
        "the test server has no locked channels"
    );
    engine.join_channel(lobby, None).await.expect("join");
    wait_self_in(&events, synced.self_session, lobby);
    let snapshot = engine.snapshot().server.expect("server state");
    let me = snapshot.people.iter().find(|p| p.is_self).expect("me");
    assert_eq!(me.channel_id, lobby);
    let nowhere = engine.join_channel(u32::MAX, None).await;
    assert!(
        matches!(nowhere, Err(BridgeError::NotFound { .. })),
        "{nowhere:?}"
    );
    engine.disconnect().await.expect("disconnect");
    wait_disconnected(&events);

    // A channel path, as a link gives it, is joined after connecting.
    engine
        .connect_server(edited.id, None, vec!["Raid".into()])
        .await
        .expect("connect with a channel path");
    let synced = wait_synced(&events);
    wait_self_in(&events, synced.self_session, channel_named(&synced, "Raid"));
    engine.disconnect().await.expect("disconnect");
    wait_disconnected(&events);

    // A wrong password ends the attempt as WrongPassword. The stock server asks one of its
    // SuperUser account.
    engine
        .connect_server(second.id, Some("certainly-wrong".into()), Vec::new())
        .await
        .expect("connect as SuperUser");
    let deadline = Instant::now() + WAIT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let (event, _) = events
            .recv_timeout(left)
            .expect("timed out waiting for the refusal");
        match event {
            BridgeEvent::CertificatePromptRequired { prompt } => engine
                .answer_certificate_prompt(prompt.fingerprint, true)
                .await
                .expect("trust"),
            BridgeEvent::ConnectionChanged {
                state: ConnectionState::Disconnected { error },
            } => {
                assert!(
                    matches!(error, Some(BridgeError::WrongPassword { .. })),
                    "{error:?}"
                );
                break;
            }
            BridgeEvent::ServerSynced { .. } => panic!("connected with a wrong password"),
            _ => {}
        }
    }
    assert_eq!(engine.snapshot().connected_server_id, None);

    engine.remove_server(second.id).await.expect("remove");
    assert_eq!(wait_servers(&events), std::slice::from_ref(&edited));
    assert_eq!(engine.snapshot().servers, [edited]);
}
