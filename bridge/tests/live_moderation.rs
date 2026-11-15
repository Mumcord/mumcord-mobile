// SPDX-License-Identifier: AGPL-3.0-only

//! Profile, activity log and moderation through the bridge against the local 1.5 test server of
//! the Mumcord repository, with the server's SuperUser as the moderator: `scripts/testenv.sh up`
//! (or `testenv/up.sh v15` there), then
//! `MUMCORD_LIVE=1 cargo test -p mumcord-mobile-bridge --test live_moderation -- --ignored`.
//! The SuperUser password comes from `SUPERUSER_PASSWORD` or the `.env` the script wrote;
//! `MUMCORD_PORT_BASE` selects the port (default 64738), `SERVER_PASSWORD` the connect password.
//!
//! A test binary of its own: a ban on a standard server always covers the address too, which is
//! the address of every test client on this machine. Test binaries run one after the other, so
//! the short ban cannot meet the tests of `live.rs`, and the test waits until it has ended.

use std::collections::HashMap;
use std::path::Path;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use mumcord_mobile::{
    ActivityEntry, ActivityKind, BanInput, BanResult, BridgeError, BridgeEvent, CertificatePrompt,
    ChannelPermissions, ConnectRequest, ConnectionState, EngineConfig, EngineListener,
    MumcordEngine, Person, SecureStorage, SecureStorageError, ServerState,
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

/// Waits until `name` is on the server, as the watcher sees it, and returns their session.
/// `earlier` is the session they had on their last connection: late events of it are skipped.
fn wait_joined(events: &Events, name: &str, earlier: Option<u32>) -> u32 {
    wait_for(events, "the person joining", |e| match e {
        BridgeEvent::PersonChanged { person }
            if person.name == name && Some(person.session) != earlier =>
        {
            Some(person.session)
        }
        _ => None,
    })
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "needs testenv v15 (MUMCORD_LIVE=1)"]
async fn profile_activity_and_moderation_by_the_superuser() {
    if std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1") {
        run_moderation().await;
    } else {
        eprintln!("skipped: set MUMCORD_LIVE=1 and start testenv/up.sh v15");
    }
}

/// The SuperUser password of the local test server: `SUPERUSER_PASSWORD`, or the `.env` that
/// `scripts/testenv.sh up` wrote into `.engine/testenv/`. Never printed.
fn superuser_password() -> String {
    if let Ok(password) = std::env::var("SUPERUSER_PASSWORD")
        && !password.is_empty()
    {
        return password;
    }
    let env = Path::new(env!("CARGO_MANIFEST_DIR")).join("../.engine/testenv/.env");
    std::fs::read_to_string(env)
        .ok()
        .and_then(|text| {
            text.lines()
                .find_map(|line| line.strip_prefix("SUPERUSER_PASSWORD="))
                .map(str::to_owned)
        })
        .filter(|password| !password.is_empty())
        .expect("set SUPERUSER_PASSWORD or run scripts/testenv.sh up")
}

/// Waits for the first event matching `pick`, also across the end of a connection.
fn wait_any<T>(events: &Events, what: &str, mut pick: impl FnMut(BridgeEvent) -> Option<T>) -> T {
    let deadline = Instant::now() + WAIT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let (event, _) = events
            .recv_timeout(left)
            .unwrap_or_else(|_| panic!("timed out waiting for {what}"));
        if let Some(found) = pick(event) {
            return found;
        }
    }
}

/// The rights of the root channel once `answered` says the server's answer is in (or after
/// the wait).
async fn root_rights(
    engine: &MumcordEngine,
    answered: fn(&ChannelPermissions) -> bool,
) -> ChannelPermissions {
    let deadline = Instant::now() + WAIT;
    loop {
        let server = engine.snapshot().server.expect("connected");
        let rights = server
            .channels
            .iter()
            .find(|channel| channel.parent_id.is_none())
            .expect("root channel")
            .permissions;
        if answered(&rights) || Instant::now() >= deadline {
            return rights;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

fn wait_activity(events: &Events, kind: ActivityKind, session: u32) -> ActivityEntry {
    wait_for(events, "the activity entry", |e| match e {
        BridgeEvent::ActivityAdded { entry } if entry.kind == kind && entry.session == session => {
            Some(entry)
        }
        _ => None,
    })
}

fn wait_person(events: &Events, what: &str, pick: impl Fn(&Person) -> bool) {
    wait_for(events, what, |e| match e {
        BridgeEvent::PersonChanged { person } if pick(&person) => Some(()),
        _ => None,
    });
}

fn png() -> Vec<u8> {
    let image = image::RgbaImage::from_pixel(48, 48, image::Rgba([40, 120, 200, 255]));
    let mut bytes = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgba8(image)
        .write_to(&mut bytes, image::ImageFormat::Png)
        .expect("png");
    bytes.into_inner()
}

async fn run_moderation() {
    let tag = std::process::id() & 0xff_ffff;
    let (admin, _admin_storage, admin_events) = fresh_engine("bridge-live-admin");
    admin
        .connect(ConnectRequest {
            password: Some(superuser_password()),
            ..request("SuperUser")
        })
        .await
        .expect("connect");
    let prompt = wait_prompt(&admin_events);
    admin
        .answer_certificate_prompt(prompt.fingerprint, true)
        .await
        .expect("trust");
    let admin_state = wait_synced(&admin_events);
    let connected = wait_activity(
        &admin_events,
        ActivityKind::Connected,
        admin_state.self_session,
    );
    assert!(connected.is_self);

    let name = format!("mc-mod-{tag:06x}");
    let (person, _storage, events) = fresh_engine("bridge-live-moderated");
    person.connect(request(&name)).await.expect("connect");
    let prompt = wait_prompt(&events);
    person
        .answer_certificate_prompt(prompt.fingerprint, true)
        .await
        .expect("trust");
    let own = wait_synced(&events).self_session;
    let session = wait_joined(&admin_events, &name, None);
    assert_eq!(session, own);
    let joined = wait_activity(&admin_events, ActivityKind::Joined, session);
    assert_eq!(joined.name, name);
    assert!(joined.channel_name.is_some());
    assert_eq!(admin.activity_log()[0], joined);

    // Rights as the server answers them: none for a guest, all for the SuperUser.
    // A guest may write in the root channel of the test server, so its answer shows there.
    let guest = root_rights(&person, |rights| rights.text_message).await;
    assert!(guest.text_message);
    assert!(!guest.kick && !guest.ban && !guest.move_people && !guest.mute_deafen);
    let kick = person.kick_person(admin_state.self_session, None).await;
    assert!(
        matches!(kick, Err(BridgeError::PermissionDenied { .. })),
        "{:?}",
        kick.map_err(|e| std::mem::discriminant(&e))
    );
    let rights = root_rights(&admin, |rights| rights.kick).await;
    assert!(rights.kick && rights.ban && rights.move_people && rights.mute_deafen);

    // Profile: the own comment and avatar, as the user and as others see them.
    person
        .set_own_comment("Playing late\nAsk me <anything>".into())
        .await
        .expect("comment");
    wait_for(&events, "the own profile change", |e| match e {
        BridgeEvent::ProfileChanged { session } if session == own => Some(()),
        _ => None,
    });
    let profile = person.person_profile(own).await.expect("own profile");
    assert_eq!(profile.comment_text, "Playing late\nAsk me <anything>");
    assert!(profile.comment.is_some());
    wait_for(&admin_events, "the profile change", |e| match e {
        BridgeEvent::ProfileChanged { session: changed } if changed == session => Some(()),
        _ => None,
    });
    let seen = admin.person_profile(session).await.expect("profile");
    assert_eq!(seen.comment_text, profile.comment_text);

    person.set_own_avatar(Some(png())).await.expect("avatar");
    let avatar = person
        .person_profile(own)
        .await
        .expect("own profile")
        .avatar;
    assert!(avatar.is_some_and(|image| image.width > 0 && image.mime.starts_with("image/")));
    let refused = person.set_own_avatar(Some(b"no image".to_vec())).await;
    assert!(refused.is_err());
    person.set_own_avatar(None).await.expect("remove avatar");
    assert_eq!(
        person.person_profile(own).await.expect("profile").avatar,
        None
    );
    person
        .set_own_comment(String::new())
        .await
        .expect("no comment");
    assert_eq!(
        person.person_profile(own).await.expect("profile").comment,
        None
    );

    // Move, server mute and deafen.
    let raid = channel_named(&admin_state, "Raid");
    assert!(admin.move_person(session, raid).await.expect("move"));
    let moved = wait_activity(&admin_events, ActivityKind::Moved, session);
    assert_eq!(moved.channel_name.as_deref(), Some("Raid"));
    wait_self_in(&events, own, raid);
    assert!(admin.set_server_mute(session, true).await.expect("mute"));
    wait_person(&events, "the server mute", |p| p.is_self && p.server_muted);
    assert!(admin.set_server_mute(session, false).await.expect("unmute"));
    wait_person(&events, "the end of the mute", |p| {
        p.is_self && !p.server_muted
    });
    assert!(
        admin
            .set_server_deafen(session, true)
            .await
            .expect("deafen")
    );
    wait_person(&events, "the server deafen", |p| {
        p.is_self && p.server_deafened && p.server_muted
    });
    assert!(
        admin
            .set_server_deafen(session, false)
            .await
            .expect("undeafen")
    );
    wait_person(&events, "the end of the deafen", |p| {
        p.is_self && !p.server_deafened
    });
    // The user is never offered to kick themselves.
    let own_kick = admin.kick_person(admin_state.self_session, None).await;
    assert!(matches!(own_kick, Err(BridgeError::InvalidInput { .. })));

    // Kick: the person is off the server and can come back.
    assert!(
        admin
            .kick_person(session, Some("cool down".into()))
            .await
            .expect("kick")
    );
    wait_any(&events, "the end of the connection", |e| match e {
        BridgeEvent::ConnectionChanged {
            state: ConnectionState::Disconnected { .. },
        } => Some(()),
        _ => None,
    });
    let left = wait_activity(&admin_events, ActivityKind::Left, session);
    assert_eq!(left.name, name);
    assert_eq!(person.activity_log(), []);

    // Ban: for five seconds, removed now. The server then refuses the person until it ends.
    person.connect(request(&name)).await.expect("reconnect");
    wait_synced(&events);
    let session = wait_joined(&admin_events, &name, Some(session));
    let target = admin.ban_target(session).await.expect("ban target");
    assert!(target.address.is_some() && target.has_certificate);
    assert!(target.can_kick && target.can_ban && target.ban_list_readable);
    let banned = admin
        .ban_person(
            session,
            BanInput {
                reason: Some("spam".into()),
                duration_secs: 5,
                by_certificate: true,
                by_address: false,
                remove_now: true,
                force: false,
                quick: false,
            },
        )
        .await
        .expect("ban");
    assert_eq!(banned, BanResult::Saved { removed: true });
    wait_any(&events, "the end of the connection", |e| match e {
        BridgeEvent::ConnectionChanged {
            state: ConnectionState::Disconnected { .. },
        } => Some(()),
        _ => None,
    });
    wait_activity(&admin_events, ActivityKind::Left, session);
    let mut refused = false;
    let deadline = Instant::now() + WAIT;
    loop {
        assert!(Instant::now() < deadline, "the ban did not end");
        person.connect(request(&name)).await.expect("connect");
        let synced = wait_any(&events, "the answer to the connect", |e| match e {
            BridgeEvent::ServerSynced { .. } => Some(true),
            BridgeEvent::ConnectionChanged {
                state: ConnectionState::Disconnected { error: Some(error) },
            } => {
                // The server drops a banned address during the TLS handshake, before it could
                // say why, so the refusal reads as unreachable.
                refused |= matches!(
                    error,
                    BridgeError::Banned { .. } | BridgeError::ServerUnreachable { .. }
                );
                Some(false)
            }
            _ => None,
        });
        if synced {
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    assert!(refused, "the server refused the banned person");
    person.disconnect().await.expect("disconnect");
    wait_disconnected(&events);

    admin.disconnect().await.expect("disconnect");
    wait_disconnected(&admin_events);
    assert_eq!(admin.activity_log(), []);
}
