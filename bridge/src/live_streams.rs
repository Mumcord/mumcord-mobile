// SPDX-License-Identifier: AGPL-3.0-only

//! A stream through the bridge against the local 1.5 test server, as the apps watch it: a
//! desktop engine shares its test pattern (VP9), the phone sees the stream, answers the direct
//! connection question, watches it over a loopback direct connection and takes decoded pictures
//! through the C functions while a held picture survives stopping. Relay paths need the relay
//! companion's test environment and are covered by the unit tests of `streams`.
//!
//! Needs libvpx on this machine (pkg-config `vpx`). `scripts/testenv.sh up`, then
//! `MUMCORD_LIVE=1 cargo test -p mumcord-mobile-bridge --features live-video --lib live_streams -- --ignored --nocapture`.
//! `MUMCORD_PORT_BASE` selects the server's port (default 64738), `SERVER_PASSWORD` the connect
//! password if the instance has one.
//!
//! Inside the crate, not under `tests/`: both engines must be allowed to connect over loopback,
//! which only the engine itself can switch on.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use mumcord_engine::p2p::P2pConfig;
use mumcord_engine::store::{Consent, ScreenCodec};
use mumcord_engine::{
    Engine, EngineEvent, EventReceiver, PasswordChange, SecretStore, ServerFields, ServerId,
};

use crate::storage::StorageBackend;
use crate::{
    BridgeEvent, ConnectRequest, EngineConfig, EngineListener, MumcordEngine, MumcordVideoFrame,
    SecureStorage, SecureStorageError, Stream, StreamKind, StreamPath, StreamWatch, VideoSink,
    mumcord_video_frame_acquire, mumcord_video_frame_release,
};

const WAIT: Duration = Duration::from_secs(30);

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

struct Recorder(Mutex<Sender<BridgeEvent>>);

impl EngineListener for Recorder {
    fn on_event(&self, event: BridgeEvent) {
        let _ = self.0.lock().expect("sender").send(event);
    }
}

struct Ready(Mutex<Sender<(u64, u32, u32)>>);

impl VideoSink for Ready {
    fn on_frame_ready(&self, video_id: u64, width: u32, height: u32) {
        assert_eq!(std::thread::current().name(), Some("mumcord-bridge-video"));
        let _ = self
            .0
            .lock()
            .expect("sender")
            .send((video_id, width, height));
    }
}

fn port() -> u16 {
    std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(64738)
}

fn password() -> Option<String> {
    std::env::var("SERVER_PASSWORD")
        .ok()
        .filter(|password| !password.is_empty())
}

fn unique(prefix: &str) -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    format!("{prefix}-{:06x}", (nanos ^ std::process::id()) & 0xff_ffff)
}

fn work_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("work dir");
    dir
}

/// Both engines run on one machine and reach each other over loopback.
fn loopback() -> P2pConfig {
    P2pConfig {
        connect_timeout: Duration::from_secs(10),
        port_mapping: false,
        allow_loopback: true,
    }
}

/// The desktop side: an engine of its own that shares the test pattern.
struct Desktop {
    engine: Engine,
    server: ServerId,
    session: u32,
}

async fn desktop(dir: &Path) -> Desktop {
    let (engine, mut rx) = Engine::new(
        dir,
        SecretStore::with_backend(Arc::new(StorageBackend(Arc::new(MemoryStorage::default())))),
        tokio::runtime::Handle::current(),
    )
    .expect("engine");
    engine
        .identity_create("Desktop Tester".into(), None)
        .await
        .expect("identity");
    let mut settings = engine.settings().expect("settings");
    settings.allow_direct_connections = Consent::Yes;
    engine.update_settings(settings).await.expect("consent");
    engine.p2p_set_config(loopback());
    let server = engine
        .server_add(ServerFields {
            label: "stream test".into(),
            address: "127.0.0.1".into(),
            port: port(),
            username: unique("desktop"),
            password: password().map_or(PasswordChange::Keep, PasswordChange::Set),
        })
        .await
        .expect("server");
    engine.connect(server.id, None).expect("connect");
    let session = synced(&engine, &mut rx).await;
    Desktop {
        engine,
        server: server.id,
        session,
    }
}

/// Trusts the test server's certificate and returns the own session once synced.
async fn synced(engine: &Engine, rx: &mut EventReceiver) -> u32 {
    let deadline = tokio::time::Instant::now() + WAIT;
    loop {
        let event = tokio::time::timeout_at(deadline, rx.recv())
            .await
            .expect("desktop sync in time")
            .expect("event stream open");
        match event {
            EngineEvent::CertTrustRequired {
                server_id,
                fingerprint,
                ..
            } => engine
                .cert_trust_decide(server_id, &fingerprint, true)
                .expect("trust"),
            EngineEvent::ServerSynced(snapshot) => return snapshot.self_session,
            _ => {}
        }
    }
}

/// Waits for an event `pick` accepts.
fn wait_for<T>(
    events: &Receiver<BridgeEvent>,
    what: &str,
    mut pick: impl FnMut(&BridgeEvent) -> Option<T>,
) -> T {
    let deadline = Instant::now() + WAIT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let event = events
            .recv_timeout(left)
            .unwrap_or_else(|_| panic!("timed out waiting for {what}"));
        if let Some(found) = pick(&event) {
            return found;
        }
    }
}

fn stream_of(streams: &[Stream], session: u32) -> Option<Stream> {
    streams
        .iter()
        .find(|s| s.session == session && s.kind == StreamKind::Screen)
        .cloned()
}

fn acquire(handle: u64, video_id: u64) -> Option<MumcordVideoFrame> {
    let mut frame = std::mem::MaybeUninit::<MumcordVideoFrame>::uninit();
    // SAFETY: the engine's handle and a writable frame.
    let got = unsafe { mumcord_video_frame_acquire(handle, video_id, frame.as_mut_ptr()) };
    // SAFETY: written when `got`.
    got.then(|| unsafe { frame.assume_init() })
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "needs testenv v15 and libvpx (MUMCORD_LIVE=1, --features live-video)"]
async fn a_phone_watches_a_desktop_share_and_gets_pictures() {
    if !std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1") {
        eprintln!("skipped: set MUMCORD_LIVE=1 and start the test server");
        return;
    }
    let dir = work_dir("live-streams");
    let desktop = desktop(&dir.join("desktop")).await;

    let (sender, events) = channel();
    let phone = MumcordEngine::new(
        EngineConfig {
            data_dir: dir.join("phone").to_string_lossy().into_owned(),
            audio: None,
        },
        Arc::new(MemoryStorage::default()),
        Arc::new(Recorder(Mutex::new(sender))),
    )
    .expect("phone engine");
    phone.engine.p2p_set_config(loopback());
    let (ready_tx, ready) = channel();
    phone.set_video_sink(Some(Arc::new(Ready(Mutex::new(ready_tx)))));

    phone
        .connect(ConnectRequest {
            address: "127.0.0.1".into(),
            port: port(),
            username: unique("phone"),
            password: password(),
        })
        .await
        .expect("connect");
    let prompt = wait_for(&events, "certificate prompt", |event| match event {
        BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt.fingerprint.clone()),
        _ => None,
    });
    phone
        .answer_certificate_prompt(prompt, true)
        .await
        .expect("trust");
    wait_for(&events, "sync", |event| {
        matches!(event, BridgeEvent::ServerSynced { .. }).then_some(())
    });

    // The share shows up as a stream nobody fetched yet.
    desktop
        .engine
        .screen_share_start(desktop.server, false, None, Some(ScreenCodec::Vp9))
        .await
        .expect("share");
    let shown = wait_for(&events, "the share in the list", |event| match event {
        BridgeEvent::StreamsChanged { streams } => stream_of(streams, desktop.session),
        _ => None,
    });
    assert_eq!(shown.watch, StreamWatch::Idle);
    assert!(
        ready.try_recv().is_err(),
        "nothing is fetched before watching"
    );

    // Watching asks the direct connection question once, then plays directly.
    let watching = {
        let phone = phone.clone();
        let sharer = desktop.session;
        tokio::spawn(async move { phone.watch_stream(sharer, StreamKind::Screen).await })
    };
    let path = wait_for(&events, "the watch", |event| match event {
        BridgeEvent::DirectConnectionsQuestion => {
            assert!(phone.snapshot().direct_connections_question);
            let phone = phone.clone();
            tokio::spawn(async move { phone.answer_direct_connections(true).await });
            None
        }
        BridgeEvent::StreamsChanged { streams } => match stream_of(streams, desktop.session)?.watch
        {
            StreamWatch::Watching { path } => Some(path),
            _ => None,
        },
        _ => None,
    });
    watching.await.expect("task").expect("watch");
    assert_eq!(path, StreamPath::Direct);
    assert!(!phone.snapshot().direct_connections_question);

    // Pictures arrive decoded.
    let video_id = shown.video_id;
    let handle = phone.video_handle();
    let (told, width, height) = ready.recv_timeout(WAIT).expect("a picture is ready");
    assert_eq!(told, video_id);
    assert!(width > 0 && height > 0);
    let held = loop {
        if let Some(frame) = acquire(handle, video_id) {
            break frame;
        }
        ready.recv_timeout(WAIT).expect("another picture");
    };
    assert!(held.width > 0 && held.height > 0);
    assert_eq!(held.stride_y, held.width);
    let luma_len = held.stride_y as usize * held.height as usize;
    // SAFETY: the picture is held.
    let luma = unsafe { std::slice::from_raw_parts(held.y, luma_len) }.to_vec();

    phone
        .set_stream_volume(desktop.session, 50)
        .await
        .expect("volume");
    wait_for(&events, "the new volume", |event| match event {
        BridgeEvent::StreamsChanged { streams } => {
            (stream_of(streams, desktop.session)?.volume == 50).then_some(())
        }
        _ => None,
    });

    // Stopping while a picture is held: the picture stays readable until it is released.
    phone
        .stop_watching_stream(desktop.session, StreamKind::Screen)
        .await
        .expect("stop");
    wait_for(&events, "the end of the watch", |event| match event {
        BridgeEvent::StreamsChanged { streams } => {
            (stream_of(streams, desktop.session)?.watch == StreamWatch::Idle).then_some(())
        }
        _ => None,
    });
    // SAFETY: still held.
    let after = unsafe { std::slice::from_raw_parts(held.y, luma_len) };
    assert_eq!(after, luma.as_slice(), "unchanged until release");
    // SAFETY: the engine's handle; the second release is a no-op.
    unsafe {
        mumcord_video_frame_release(handle, held.token);
        mumcord_video_frame_release(handle, held.token);
    }
    assert!(
        acquire(handle, video_id).is_none(),
        "no pictures after stop"
    );

    desktop
        .engine
        .screen_share_stop(desktop.server)
        .expect("stop sharing");
    wait_for(
        &events,
        "the share to leave the list",
        |event| match event {
            BridgeEvent::StreamsChanged { streams } => {
                stream_of(streams, desktop.session).is_none().then_some(())
            }
            _ => None,
        },
    );
    assert!(phone.snapshot().streams.is_empty());
    phone.disconnect().await.expect("disconnect");
    desktop.engine.disconnect(desktop.server);
}
