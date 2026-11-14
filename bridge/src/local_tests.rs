// SPDX-License-Identifier: AGPL-3.0-only

//! What stays on the phone, through the bridge as the apps use it: identities, the transfer
//! from the desktop and the phone settings. No server is needed. No test prints or compares in
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
    BridgeError, BridgeEvent, DefaultOutput, EngineConfig, EngineListener, Identity,
    MobileSettings, MumcordEngine, PasswordInput, SecureStorage, SecureStorageError, ServerInput,
    TalkMode, TransferError, TransferProgress, TransferSkipReason,
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
    assert!(other.wait_identities() == [imported.clone()]);
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
