// SPDX-License-Identifier: AGPL-3.0-only

//! What stays on the phone, through the bridge as the apps use it: identities, the transfer
//! from the desktop, the phone settings and the themes. No server is needed. No test prints or compares in
//! its messages a key, a code, a label or a QR text: failures name the step only.
//!
//! Inside the crate, not under `tests/`: the desktop side of a transfer is made with the
//! engine itself, and the clock of the scan is the test's.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mumcord_engine::store::transfer::{
    KdfParams, Transfer, TransferOptions, VALIDITY_SECS, unix_now,
};

use crate::transfer::TransferScan;
use crate::{
    AppSettings, BanInput, BridgeError, BridgeEvent, DefaultOutput, EngineConfig, EngineListener,
    Identity, MobileSettings, MumcordEngine, PasswordInput, SecureStorage, SecureStorageError,
    ServerInput, TalkMode, ThemeCodeError, ThemeMode, ThemeSettings, ThemeVariant, TransferError,
    TransferProgress, TransferSkipReason,
};

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
        assert_eq!(std::thread::current().name(), Some("mumcord-bridge-events"));
        // The test may have finished already; late events are of no interest then.
        let _ = self.0.lock().expect("sender").send(event);
    }
}

struct Phone {
    engine: Arc<MumcordEngine>,
    events: Receiver<BridgeEvent>,
    storage: Arc<MemoryStorage>,
    dir: PathBuf,
}

fn data_dir(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!(
        "mumcord-bridge-local-{}-{name}",
        std::process::id()
    ))
}

impl Phone {
    /// A phone with an empty data folder `name`.
    fn fresh(name: &str) -> Self {
        let _ = std::fs::remove_dir_all(data_dir(name));
        Self::open(name, Arc::default())
    }

    /// Starts the engine on the data folder `name` as it is.
    fn open(name: &str, storage: Arc<MemoryStorage>) -> Self {
        let dir = data_dir(name);
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
        Self {
            engine,
            events,
            storage,
            dir,
        }
    }

    fn wait<T>(&self, what: &str, mut pick: impl FnMut(BridgeEvent) -> Option<T>) -> T {
        loop {
            let event = self
                .events
                .recv_timeout(Duration::from_secs(20))
                .unwrap_or_else(|_| panic!("timed out waiting for {what}"));
            if let Some(found) = pick(event) {
                return found;
            }
        }
    }

    fn wait_identities(&self) -> Vec<Identity> {
        self.wait("the identities", |event| match event {
            BridgeEvent::IdentitiesChanged { identities } => Some(identities),
            _ => None,
        })
    }

    fn wait_settings(&self) -> MobileSettings {
        self.wait("the phone settings", |event| match event {
            BridgeEvent::MobileSettingsChanged { settings } => Some(settings),
            _ => None,
        })
    }

    /// Files an identity import or export left in the data folder.
    fn identity_files(&self) -> usize {
        std::fs::read_dir(self.dir.join("identity-io")).map_or(0, Iterator::count)
    }

    fn remove(self) {
        let dir = self.dir.clone();
        drop(self);
        let _ = std::fs::remove_dir_all(dir);
    }
}

fn server(name: &str, password: PasswordInput, identity_id: Option<i64>) -> ServerInput {
    ServerInput {
        name: name.to_owned(),
        address: format!("{name}.example"),
        port: 64738,
        username: "ada".to_owned(),
        password,
        identity_id,
    }
}

/// Whether the error is of the kind `expected` names, without printing what it carries.
fn is_kind<T>(result: &Result<T, BridgeError>, expected: &str) -> bool {
    let kind = match result {
        Ok(_) => "Ok",
        Err(BridgeError::InvalidInput { .. }) => "InvalidInput",
        Err(BridgeError::DuplicateIdentity { .. }) => "DuplicateIdentity",
        Err(BridgeError::LastIdentity { .. }) => "LastIdentity",
        Err(BridgeError::NotFound { .. }) => "NotFound",
        Err(_) => "other",
    };
    kind == expected
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn identities_are_created_exported_imported_and_deleted_by_the_rules() {
    let phone = Phone::fresh("identities");
    let engine = &phone.engine;
    assert!(engine.snapshot().identities.is_empty());
    assert!(engine.identities().await.expect("list").is_empty());

    // First start: one identity, the default; asking again creates nothing.
    let first = engine
        .ensure_default_identity("ada".into())
        .await
        .expect("first identity");
    assert!(first.is_default);
    assert!(first.label == "ada" && first.common_name == "ada");
    assert_eq!(first.fingerprint.len(), 40);
    assert!(first.created_ms > 0 && first.server_ids.is_empty());
    assert!(phone.wait_identities() == [first.clone()]);
    let again = engine
        .ensure_default_identity("someone else".into())
        .await
        .expect("same identity");
    assert!(again == first);
    assert!(engine.snapshot().identities == [first.clone()]);

    // The only identity cannot be deleted.
    assert!(is_kind(
        &engine.delete_identity(first.id).await,
        "LastIdentity"
    ));

    // A second one with a label of its own; the default stays.
    let work = engine
        .create_identity("ada at work".into(), Some("Work".into()))
        .await
        .expect("second identity");
    assert!(!work.is_default && work.label == "Work" && work.common_name == "ada at work");
    assert!(work.fingerprint != first.fingerprint);
    assert!(phone.wait_identities() == [first.clone(), work.clone()]);
    assert!(is_kind(
        &engine.create_identity("  ".into(), None).await,
        "InvalidInput"
    ));

    // Export needs a password; the file never stays in the data folder.
    assert!(is_kind(
        &engine.export_identity(work.id, String::new()).await,
        "InvalidInput"
    ));
    assert!(is_kind(
        &engine.export_identity(9999, "pw".into()).await,
        "NotFound"
    ));
    let file = engine
        .export_identity(work.id, "export password".into())
        .await
        .expect("export");
    assert!(file.len() > 500, "a PKCS#12 file");
    assert_eq!(phone.identity_files(), 0);

    // The same certificate again is refused, with its own kind.
    assert!(is_kind(
        &engine
            .import_identity(file.clone(), "export password".into(), None)
            .await,
        "DuplicateIdentity"
    ));
    assert_eq!(phone.identity_files(), 0);

    // Another phone: a wrong password and a file that is no identity are refused, the right
    // password brings the same certificate.
    let other = Phone::fresh("identities-other");
    assert!(is_kind(
        &other
            .engine
            .import_identity(file.clone(), "wrong".into(), None)
            .await,
        "InvalidInput"
    ));
    assert!(is_kind(
        &other
            .engine
            .import_identity(b"not a file".to_vec(), String::new(), None)
            .await,
        "InvalidInput"
    ));
    assert_eq!(other.identity_files(), 0);
    assert!(other.engine.snapshot().identities.is_empty());
    let imported = other
        .engine
        .import_identity(file.clone(), "export password".into(), None)
        .await
        .expect("import");
    assert!(imported.fingerprint == work.fingerprint);
    assert!(imported.label == "ada at work" && imported.is_default);
    assert!(other.wait_identities() == std::slice::from_ref(&imported));
    assert_eq!(other.identity_files(), 0);
    // The key is in the secure storage, and what the export hands out is the same identity.
    assert!(!other.storage.0.lock().expect("storage").is_empty());
    let exported_again = other
        .engine
        .export_identity(imported.id, "second password".into())
        .await
        .expect("export of the import");
    assert!(is_kind(
        &engine
            .import_identity(exported_again, "second password".into(), None)
            .await,
        "DuplicateIdentity"
    ));
    other.remove();

    // A server that names the second identity, a new label, a new default.
    let saved = engine
        .add_server(server("home", PasswordInput::Keep, Some(work.id)))
        .await
        .expect("server");
    assert_eq!(saved.identity_id, Some(work.id));
    let listed = engine.identities().await.expect("list");
    assert!(listed[1].server_ids == [saved.id]);
    let renamed = engine
        .rename_identity(work.id, "Office".into())
        .await
        .expect("rename");
    assert!(renamed.label == "Office" && renamed.fingerprint == work.fingerprint);
    assert!(phone.wait_identities()[1].label == "Office");
    engine
        .set_default_identity(work.id)
        .await
        .expect("new default");
    let listed = phone.wait_identities();
    assert!(listed[0].id == work.id && listed[0].is_default && !listed[1].is_default);
    assert!(is_kind(
        &engine.set_default_identity(9999).await,
        "NotFound"
    ));

    // Deleting an identity takes its key along and frees the servers that named it.
    let accounts_before = phone.storage.0.lock().expect("storage").len();
    engine.delete_identity(work.id).await.expect("delete");
    let left = phone.wait_identities();
    assert!(left.len() == 1 && left[0].id == first.id && left[0].is_default);
    assert!(phone.storage.0.lock().expect("storage").len() < accounts_before);
    let snapshot = engine.snapshot();
    assert!(snapshot.identities == left);
    assert_eq!(snapshot.servers[0].identity_id, None);
    phone.remove();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn identity_files_left_by_an_ended_app_are_removed_at_the_start() {
    let name = "identity-sweep";
    let _ = std::fs::remove_dir_all(data_dir(name));
    let left = data_dir(name).join("identity-io").join("1-0");
    std::fs::create_dir_all(&left).expect("left-over folder");
    std::fs::write(left.join("Identity.p12"), b"left over").expect("left-over file");
    let phone = Phone::open(name, Arc::default());
    assert_eq!(phone.identity_files(), 0);
    phone.remove();
}

/// The cheapest key derivation a reader accepts, so the test does not take minutes.
fn quick() -> TransferOptions {
    TransferOptions {
        kdf: KdfParams {
            memory_kib: 64 * 1024,
            passes: 3,
        },
        ..TransferOptions::default()
    }
}

/// A code of the same length that is not `code`.
fn wrong(code: &str) -> String {
    let first = if code.starts_with('0') { '1' } else { '0' };
    format!("{first}{}", &code[1..])
}

fn progress(have: u8, total: u8) -> Result<TransferProgress, TransferError> {
    Ok(TransferProgress { have, total })
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_transfer_from_the_desktop_is_scanned_opened_and_imported() {
    // The desktop: two identities, two servers, one with a password and an identity of its own.
    let desktop = Phone::fresh("transfer-desktop");
    let main = desktop
        .engine
        .ensure_default_identity("ada".into())
        .await
        .expect("identity");
    let work = desktop
        .engine
        .create_identity("ada at work".into(), Some("Work".into()))
        .await
        .expect("identity");
    let secret = PasswordInput::Set {
        value: "server password".into(),
    };
    desktop
        .engine
        .add_server(server("home", secret, None))
        .await
        .expect("server");
    desktop
        .engine
        .add_server(server("office", PasswordInput::Keep, Some(work.id)))
        .await
        .expect("server");
    let payload = desktop
        .engine
        .engine
        .transfer_export_payload(vec![main.id, work.id], true)
        .await
        .expect("payload");
    let created = unix_now();
    let transfer = Transfer::create_with(&payload, created, &quick()).expect("transfer");
    let other_transfer = Transfer::create_with(&payload, created, &quick()).expect("transfer");
    drop(payload);
    let parts = transfer.parts();
    let total = parts.len() as u8;
    assert!(total >= 2, "several QR codes");

    // The phone has an identity and one of the two servers already.
    let phone = Phone::fresh("transfer-phone");
    let own = phone
        .engine
        .ensure_default_identity("phone".into())
        .await
        .expect("identity");
    phone.wait_identities();
    phone
        .engine
        .add_server(server("home", PasswordInput::Keep, None))
        .await
        .expect("server");
    let now = Arc::new(AtomicU64::new(created + 5));
    let clock = now.clone();
    let scan = TransferScan::new(
        &phone.engine,
        Arc::new(move || clock.load(Ordering::Relaxed)),
    );
    let scan_all = || {
        for part in parts.iter().rev() {
            assert!(scan.add_part(part.clone()).is_ok(), "a part is accepted");
        }
    };

    // Before the first code: nothing known, the default code length, nothing to import.
    assert_eq!(scan.progress(), None);
    assert_eq!((scan.code_digits(), scan.attempts_left()), (8, 3));
    assert!(is_kind(&scan.import_opened().await, "InvalidInput"));
    assert!(scan.open("12345678".into()).await == Err(TransferError::Incomplete));

    // Codes arrive in any order, twice, and next to things that are no transfer.
    assert!(scan.add_part("https://example.com".into()) == Err(TransferError::NotATransfer));
    assert!(scan.add_part(String::new()) == Err(TransferError::NotATransfer));
    let last = parts[parts.len() - 1].clone();
    assert!(scan.add_part(last.clone()) == progress(1, total));
    assert!(scan.add_part(last.clone()) == progress(1, total));
    let mut damaged = last.clone();
    let swapped = if damaged.ends_with('A') { "B" } else { "A" };
    damaged.replace_range(damaged.len() - 1.., swapped);
    assert!(matches!(
        scan.add_part(damaged),
        Err(TransferError::BadChecksum | TransferError::NotATransfer)
    ));
    assert!(scan.add_part(other_transfer.parts()[0].clone()) == Err(TransferError::OtherTransfer));
    assert!(scan.progress() == Some(TransferProgress { have: 1, total }));
    assert!(scan.open(transfer.code().to_owned()).await == Err(TransferError::Incomplete));
    scan_all();
    assert!(scan.progress() == Some(TransferProgress { have: total, total }));
    assert_eq!(scan.code_digits(), 8);

    // A code of the wrong length is not an attempt; three wrong codes end the scan.
    assert!(scan.open("1234".into()).await == Err(TransferError::InvalidCode { digits: 8 }));
    assert!(scan.open("abcdefgh".into()).await == Err(TransferError::InvalidCode { digits: 8 }));
    assert_eq!(scan.attempts_left(), 3);
    let wrong_code = wrong(transfer.code());
    // While the code is checked (a second or two), the camera may still hand in codes.
    let checking = tokio::spawn({
        let (scan, code) = (scan.clone(), wrong_code.clone());
        async move { scan.open(code).await }
    });
    assert!(scan.add_part(last.clone()) == progress(total, total));
    assert!(
        checking.await.expect("the check") == Err(TransferError::WrongCode { attempts_left: 2 })
    );
    assert_eq!(scan.attempts_left(), 2);
    assert!(
        scan.open(wrong_code.clone()).await == Err(TransferError::WrongCode { attempts_left: 1 })
    );
    assert!(scan.open(wrong_code.clone()).await == Err(TransferError::RescanRequired));
    assert_eq!((scan.progress(), scan.attempts_left()), (None, 3));
    assert!(scan.open(transfer.code().to_owned()).await == Err(TransferError::Incomplete));

    // Scanned again, the right code opens it: what arrives, without any key.
    scan_all();
    let preview = scan
        .open(transfer.code().to_owned())
        .await
        .expect("the right code opens the transfer");
    let identities: Vec<(&str, &str, bool)> = preview
        .identities
        .iter()
        .map(|i| (i.label.as_str(), i.fingerprint.as_str(), i.is_default))
        .collect();
    assert!(
        identities
            == [
                ("ada", main.fingerprint.as_str(), true),
                ("Work", work.fingerprint.as_str(), false)
            ]
    );
    let servers = preview.servers.expect("the server list");
    let names: Vec<(&str, &str, u16, &str)> = servers
        .iter()
        .map(|s| {
            (
                s.name.as_str(),
                s.address.as_str(),
                s.port,
                s.username.as_str(),
            )
        })
        .collect();
    assert!(
        names
            == [
                ("home", "home.example", 64738, "ada"),
                ("office", "office.example", 64738, "ada")
            ]
    );
    assert_eq!(scan.progress(), None, "the scanned codes are used up");

    // The import: both identities, the one new server; nothing of the phone is replaced.
    let report = scan.import_opened().await.expect("import");
    assert!(report.identities_imported.len() == 2 && report.identities_skipped.is_empty());
    assert!(report.identities_imported.iter().all(|i| !i.is_default));
    assert!(report.servers_imported.len() == 1 && report.servers_skipped.len() == 1);
    assert!(report.servers_skipped[0].reason == TransferSkipReason::Duplicate);
    let office = &report.servers_imported[0];
    assert!(office.name == "office" && !office.has_password);
    let work_here = report
        .identities_imported
        .iter()
        .find(|i| i.fingerprint == work.fingerprint)
        .expect("the work identity");
    assert_eq!(office.identity_id, Some(work_here.id));
    let listed = phone.wait_identities();
    assert!(listed.len() == 3 && listed[0].id == own.id && listed[0].is_default);
    let snapshot = phone.engine.snapshot();
    assert!(snapshot.identities == listed);
    assert_eq!(snapshot.servers.len(), 2);
    assert!(snapshot.servers.iter().all(|s| !s.has_password));
    // Imported once: the transfer is gone from the object.
    assert!(is_kind(&scan.import_opened().await, "InvalidInput"));

    // The same transfer again brings nothing new.
    scan_all();
    scan.open(transfer.code().to_owned())
        .await
        .expect("opened again");
    let again = scan.import_opened().await.expect("second import");
    assert!(again.identities_imported.is_empty() && again.servers_imported.is_empty());
    assert_eq!(again.identities_skipped.len(), 2);
    assert!(
        again
            .identities_skipped
            .iter()
            .all(|skipped| skipped.reason == TransferSkipReason::Duplicate)
    );
    assert_eq!(again.servers_skipped.len(), 2);
    assert_eq!(phone.engine.snapshot().identities.len(), 3);

    // An opened transfer that is reset cannot be imported.
    scan_all();
    scan.open(transfer.code().to_owned())
        .await
        .expect("opened a third time");
    scan.reset();
    assert!(is_kind(&scan.import_opened().await, "InvalidInput"));

    // After `OtherTransfer` a reset lets the new transfer in.
    scan_all();
    let new_part = other_transfer.parts()[0].clone();
    assert!(scan.add_part(new_part.clone()) == Err(TransferError::OtherTransfer));
    scan.reset();
    assert!(scan.add_part(new_part).is_ok());
    scan.reset();

    // Two minutes (and the allowance for the clocks) later the codes are no longer accepted:
    // neither a scan in progress nor the typed code.
    scan_all();
    now.store(created + VALIDITY_SECS + 31, Ordering::Relaxed);
    assert!(scan.open(transfer.code().to_owned()).await == Err(TransferError::Expired));
    assert_eq!(scan.progress(), None);
    assert!(scan.add_part(parts[0].clone()) == Err(TransferError::Expired));
    assert_eq!(scan.progress(), None);
    // A phone whose clock is far behind the computer's is told so.
    now.store(created - 31, Ordering::Relaxed);
    assert!(scan.add_part(parts[0].clone()) == Err(TransferError::ClockSkew));

    drop(scan);
    desktop.remove();
    phone.remove();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_phone_settings_are_saved_and_survive_a_restart() {
    let name = "settings";
    let phone = Phone::fresh(name);
    let defaults = MobileSettings {
        talk_mode_chosen: false,
        default_output: DefaultOutput::Ask,
        mobile_data_autoplay_streams: false,
        mobile_data_load_media: true,
        mobile_data_reduced_voice: false,
        idle_disconnect: false,
        notify_sender_only: false,
        update_check: true,
    };
    assert_eq!(phone.engine.mobile_settings(), defaults);
    assert_eq!(phone.engine.snapshot().mobile_settings, defaults);
    assert_eq!(
        phone.engine.talk_mode().await.expect("talk mode"),
        TalkMode::VoiceActivity
    );

    // Saving what is saved already tells nothing.
    phone
        .engine
        .set_mobile_settings(defaults)
        .await
        .expect("unchanged");
    let changed = MobileSettings {
        talk_mode_chosen: false,
        default_output: DefaultOutput::Earpiece,
        mobile_data_autoplay_streams: true,
        mobile_data_load_media: false,
        mobile_data_reduced_voice: true,
        idle_disconnect: true,
        notify_sender_only: true,
        update_check: false,
    };
    phone
        .engine
        .set_mobile_settings(changed)
        .await
        .expect("changed");
    assert_eq!(phone.wait_settings(), changed);
    assert_eq!(phone.engine.snapshot().mobile_settings, changed);

    // Choosing the talk mode marks it as chosen, and an older copy of the settings does not
    // take that back.
    phone
        .engine
        .set_talk_mode(TalkMode::PushToTalk)
        .await
        .expect("talk mode");
    let chosen = MobileSettings {
        talk_mode_chosen: true,
        ..changed
    };
    assert_eq!(phone.wait_settings(), chosen);
    let stale = MobileSettings {
        default_output: DefaultOutput::Speaker,
        ..changed
    };
    phone
        .engine
        .set_mobile_settings(stale)
        .await
        .expect("older copy");
    let saved = MobileSettings {
        talk_mode_chosen: true,
        ..stale
    };
    assert_eq!(phone.wait_settings(), saved);

    // A new engine on the same data folder.
    let storage = phone.storage.clone();
    drop(phone);
    let restarted = Phone::open(name, storage);
    assert_eq!(restarted.engine.mobile_settings(), saved);
    assert_eq!(restarted.engine.snapshot().mobile_settings, saved);
    assert_eq!(
        restarted.engine.talk_mode().await.expect("talk mode"),
        TalkMode::PushToTalk
    );
    restarted.remove();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_shared_settings_are_saved_checked_and_survive_a_restart() {
    let name = "app-settings";
    let phone = Phone::fresh(name);
    let defaults = AppSettings {
        language: None,
        link_previews: true,
        history_max_age_days: None,
        notify_private_messages: true,
        notify_mentions: true,
    };
    assert_eq!(phone.engine.app_settings(), defaults);

    let changed = AppSettings {
        language: Some("fr".into()),
        link_previews: false,
        history_max_age_days: Some(30),
        notify_private_messages: false,
        notify_mentions: false,
    };
    phone
        .engine
        .set_app_settings(changed.clone())
        .await
        .expect("changed");
    let reported = phone.wait("the app settings", |event| match event {
        BridgeEvent::AppSettingsChanged { settings } => Some(settings),
        _ => None,
    });
    assert_eq!(reported, changed);

    let unknown = AppSettings {
        language: Some("tlh".into()),
        ..changed.clone()
    };
    let refused = phone.engine.set_app_settings(unknown).await;
    assert!(is_kind(&refused, "InvalidInput"));
    assert_eq!(phone.engine.app_settings(), changed);

    let storage = phone.storage.clone();
    drop(phone);
    let restarted = Phone::open(name, storage);
    assert_eq!(restarted.engine.app_settings(), changed);
    restarted.remove();
}

impl Phone {
    fn wait_themes(&self) -> ThemeSettings {
        self.wait("the themes", |event| match event {
            BridgeEvent::ThemesChanged { settings } => Some(settings),
            _ => None,
        })
    }
}

/// A custom theme shared from the desktop, with the shades the desktop draws it in
/// (`themes/vectors.json`, written from the desktop code).
fn desktop_theme() -> serde_json::Value {
    let vectors: serde_json::Value =
        serde_json::from_str(include_str!("themes/vectors.json")).expect("vectors");
    vectors["desktopTheme"].clone()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_theme_from_the_desktop_is_imported_shown_shared_and_kept() {
    let name = "themes";
    let phone = Phone::fresh(name);
    let defaults = phone.engine.theme_settings();
    assert_eq!(defaults.mode, ThemeMode::System);
    assert_eq!(defaults.app_theme_id, "preset:mumcord");
    assert!(defaults.custom_themes.is_empty() && defaults.server_themes.is_empty());
    let built_in = phone.engine.built_in_themes();
    assert_eq!(built_in.len(), 4);
    assert!(built_in.iter().all(|theme| theme.built_in));

    phone
        .engine
        .set_theme_mode(ThemeMode::Light)
        .await
        .expect("mode");
    assert_eq!(phone.wait_themes().mode, ThemeMode::Light);

    // Pasted with the text around it, as copied from a chat.
    let desktop = desktop_theme();
    // Written for the pinned engine; after moving the pin, run `scripts/theme-vectors.mjs`.
    assert_eq!(desktop["version"], env!("MUMCORD_ENGINE_VERSION"));
    let message = desktop["message"].as_str().expect("message");
    let pasted = format!("look at this: {message} :)");
    let preview = phone
        .engine
        .theme_code_preview(pasted.clone())
        .expect("preview");
    assert_eq!(preview.name, "From the desktop");
    assert_eq!(preview.imported_id, None);
    assert!(!preview.made_with_newer);
    let imported = phone
        .engine
        .import_theme_code(pasted.clone())
        .await
        .expect("imported");
    assert!(imported.id.starts_with("custom:") && !imported.built_in);
    assert_eq!(imported.dark.background, desktop["dark"]["background"]);
    assert_eq!(
        phone.wait_themes().custom_themes,
        std::slice::from_ref(&imported)
    );
    // A second import adds nothing.
    let again = phone
        .engine
        .import_theme_code(pasted.clone())
        .await
        .expect("again");
    assert_eq!(again, imported);
    assert_eq!(phone.engine.theme_settings().custom_themes.len(), 1);
    assert_eq!(
        phone
            .engine
            .theme_code_preview(pasted)
            .expect("preview")
            .imported_id,
        Some(imported.id.clone())
    );

    phone
        .engine
        .set_app_theme(imported.id.clone())
        .await
        .expect("app theme");
    assert_eq!(phone.wait_themes().app_theme_id, imported.id);
    // The same shades as on the desktop, in both versions.
    for (variant, tokens) in [
        (ThemeVariant::Dark, &desktop["tokensDark"]),
        (ThemeVariant::Light, &desktop["tokensLight"]),
    ] {
        let shown = phone.engine.theme_tokens(None, variant);
        assert_eq!(shown.theme_id, imported.id);
        assert_eq!(shown.base, variant);
        assert_eq!(shown.bg_servers, tokens["bg-servers"]);
        assert_eq!(shown.text_muted, tokens["text-muted"]);
        assert_eq!(shown.accent_stop_2, tokens["accent-stop-2"]);
        assert_eq!(shown.focus, tokens["focus"]);
    }
    // Shared again, it is the desktop's code byte for byte.
    let share = phone
        .engine
        .theme_share(imported.id.clone())
        .expect("share");
    assert_eq!(share.code, desktop["code"].as_str().expect("code"));
    assert_eq!(share.message, message);

    let server = phone
        .engine
        .add_server(server("home", PasswordInput::Keep, None))
        .await
        .expect("server");
    phone
        .engine
        .set_server_theme(server.id, Some("preset:forest".into()))
        .await
        .expect("server theme");
    let themes = phone.wait("the server theme", |event| match event {
        BridgeEvent::ThemesChanged { settings } if !settings.server_themes.is_empty() => {
            Some(settings)
        }
        _ => None,
    });
    assert_eq!(
        themes.server_themes.get(&server.id).map(String::as_str),
        Some("preset:forest")
    );
    let forest = phone
        .engine
        .theme_tokens(Some("preset:forest".into()), ThemeVariant::Dark);
    assert_eq!(forest.theme_id, "preset:forest");

    let storage = phone.storage.clone();
    drop(phone);
    let restarted = Phone::open(name, storage);
    let kept = restarted.engine.theme_settings();
    assert_eq!(kept.mode, ThemeMode::Light);
    assert_eq!(kept.app_theme_id, imported.id);
    assert_eq!(kept.custom_themes, std::slice::from_ref(&imported));
    assert_eq!(kept.server_themes, themes.server_themes);

    // Deleting the app theme goes back to Mumcord.
    restarted
        .engine
        .delete_custom_theme(imported.id.clone())
        .await
        .expect("deleted");
    let after = restarted.wait_themes();
    assert_eq!(after.app_theme_id, "preset:mumcord");
    assert!(after.custom_themes.is_empty());
    restarted.remove();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn wrong_theme_codes_and_unknown_themes_are_refused() {
    let phone = Phone::fresh("theme-errors");
    assert_eq!(
        phone.engine.theme_code_preview("no code here".into()),
        Err(ThemeCodeError::Invalid)
    );
    assert_eq!(
        phone
            .engine
            .theme_code_preview(format!("mct1.{}", "A".repeat(3000))),
        Err(ThemeCodeError::Invalid)
    );
    // Readable nowhere: the text is the background colour.
    let murky = "mct1.eyJ2IjoiMC40LjAiLCJuIjoiTXVya3kiLCJjIjp7ImJhY2tncm91bmQiOiIjMTExMTExIiwidGV4dCI6IiMxMTExMTEifX0";
    assert_eq!(
        phone.engine.theme_code_preview(murky.into()),
        Err(ThemeCodeError::Unreadable)
    );
    assert!(is_kind(
        &phone.engine.import_theme_code(murky.into()).await,
        "InvalidInput"
    ));
    assert!(phone.engine.theme_settings().custom_themes.is_empty());
    assert!(is_kind(
        &phone.engine.set_app_theme("custom:nothing".into()).await,
        "NotFound"
    ));
    assert!(is_kind(
        &phone.engine.theme_share("custom:nothing".into()),
        "NotFound"
    ));
    assert!(is_kind(
        &phone
            .engine
            .delete_custom_theme("preset:mumcord".into())
            .await,
        "InvalidInput"
    ));
    assert!(is_kind(
        &phone
            .engine
            .delete_custom_theme("custom:nothing".into())
            .await,
        "NotFound"
    ));
    assert!(is_kind(
        &phone
            .engine
            .set_server_theme(1, Some("preset:forest".into()))
            .await,
        "NotFound"
    ));
    assert!(is_kind(
        &phone
            .engine
            .set_server_theme(1, Some("custom:nothing".into()))
            .await,
        "NotFound"
    ));
    phone.remove();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn profile_and_moderation_need_a_connection_and_the_log_is_empty_without_one() {
    let phone = Phone::fresh("offline-moderation");
    assert_eq!(phone.engine.activity_log(), []);
    let not_connected =
        |result: Result<(), BridgeError>| matches!(result, Err(BridgeError::NotConnected { .. }));
    assert!(not_connected(
        phone.engine.person_profile(1).await.map(drop)
    ));
    assert!(not_connected(
        phone.engine.set_own_comment("hi".into()).await
    ));
    assert!(not_connected(phone.engine.set_own_avatar(None).await));
    assert!(not_connected(
        phone.engine.move_person(1, 0).await.map(drop)
    ));
    assert!(not_connected(
        phone.engine.set_server_mute(1, true).await.map(drop)
    ));
    assert!(not_connected(
        phone.engine.set_server_deafen(1, true).await.map(drop)
    ));
    assert!(not_connected(
        phone.engine.kick_person(1, None).await.map(drop)
    ));
    assert!(not_connected(phone.engine.ban_target(1).await.map(drop)));
    let ban = BanInput {
        reason: None,
        duration_secs: 0,
        by_certificate: true,
        by_address: false,
        remove_now: true,
        force: false,
        quick: false,
    };
    assert!(not_connected(
        phone.engine.ban_person(1, ban).await.map(drop)
    ));
    // No avatar file is left behind by a refused change.
    assert_eq!(phone.identity_files(), 0);
    phone.remove();
}
