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
    EngineListener, MumcordEngine, SecureStorage, SecureStorageError, ServerState,
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
fn wait_for<T>(
    events: &Receiver<(BridgeEvent, Option<String>)>,
    what: &str,
    mut pick: impl FnMut(BridgeEvent) -> Option<T>,
) -> T {
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

fn wait_prompt(events: &Receiver<(BridgeEvent, Option<String>)>) -> CertificatePrompt {
    wait_for(events, "the certificate prompt", |e| match e {
        BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt),
        _ => None,
    })
}

fn wait_synced(events: &Receiver<(BridgeEvent, Option<String>)>) -> ServerState {
    wait_for(events, "the synced server", |e| match e {
        BridgeEvent::ServerSynced { server } => Some(server),
        BridgeEvent::CertificatePromptRequired { .. } => panic!("unexpected certificate prompt"),
        _ => None,
    })
}

fn wait_disconnected(events: &Receiver<(BridgeEvent, Option<String>)>) {
    wait_for(events, "the disconnect", |e| match e {
        BridgeEvent::ConnectionChanged {
            state: ConnectionState::Disconnected { error: None },
        } => Some(()),
        _ => None,
    });
}

fn request(username: &str) -> ConnectRequest {
    let base = std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(64738);
    ConnectRequest {
        address: "127.0.0.1".into(),
        port: base,
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

async fn run() {
    let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join("bridge-live");
    let _ = std::fs::remove_dir_all(&dir);
    let username = format!("mc-bridge-{:06x}", std::process::id() & 0xff_ffff);
    let storage = Arc::new(MemoryStorage::default());
    let (sender, events) = channel();
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: dir.to_string_lossy().into_owned(),
        },
        storage.clone(),
        Arc::new(Recorder(Mutex::new(sender))),
    )
    .expect("engine");

    let start = engine.snapshot();
    assert_eq!(
        start.connection,
        ConnectionState::Disconnected { error: None }
    );
    assert_eq!(start.certificate_prompt, None);
    assert_eq!(start.server, None);

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
