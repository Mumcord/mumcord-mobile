// SPDX-License-Identifier: AGPL-3.0-only

//! Crash reports through the bridge: a native crash of the "previous run" and a panic of this
//! one are saved, listed, read, previewed for the upload and deleted.
//!
//! One test in a file of its own, because the engine installs its panic hook and signal
//! handlers once for the whole process. The upload itself is not tested: the engine sends to
//! the real crash server only, and tests never do that. The upload is checked up to the point
//! before anything is sent.

use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use mumcord_mobile::{
    BridgeError, BridgeEvent, CrashKind, EngineConfig, EngineListener, MumcordEngine,
    SecureStorage, SecureStorageError, crash_delete, crash_delete_all, crash_install, crash_list,
    crash_read, crash_take_unseen, crash_upload_preview,
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

struct Ignore;

impl EngineListener for Ignore {
    fn on_event(&self, _event: BridgeEvent) {}
}

/// The report of the previous run is written in the background at install.
fn wait_for_reports(count: usize) -> Vec<mumcord_mobile::CrashReport> {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let reports = crash_list().expect("list");
        if reports.len() >= count || Instant::now() > deadline {
            return reports;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[test]
fn crash_reports_are_kept_shown_and_deleted_on_request() {
    let root = Path::new(env!("CARGO_TARGET_TMPDIR")).join("bridge-crash");
    let _ = fs::remove_dir_all(&root);
    let dir = root.join("crash-reports");
    let dir_text = dir.to_string_lossy().into_owned();

    assert!(
        matches!(crash_list(), Err(BridgeError::Io { .. })),
        "nothing works before crash reporting is set up"
    );

    // The previous run was ended by SIGSEGV: the native handler left its marker behind.
    fs::create_dir_all(&dir).expect("crash folder");
    fs::write(dir.join("running"), "SIGSEGV\nthread mumcord-audio\n").expect("marker");

    crash_install(dir_text.clone(), "0.1.0-test".to_owned()).expect("install");
    crash_install(dir_text.clone(), "0.1.0-test".to_owned()).expect("installing again is fine");
    assert!(matches!(
        crash_install(
            root.join("elsewhere").to_string_lossy().into_owned(),
            "0.1.0-test".to_owned()
        ),
        Err(BridgeError::InvalidInput { .. })
    ));

    let reports = wait_for_reports(1);
    assert_eq!(reports.len(), 1, "{reports:?}");
    let native = &reports[0];
    assert_eq!(
        native.kind,
        CrashKind::Signal {
            name: "SIGSEGV".to_owned()
        }
    );
    assert_eq!(native.version, "0.1.0-test");
    assert!(native.created_ms > 0);
    assert!(!native.uploaded);

    let unseen = crash_take_unseen().expect("unseen");
    assert_eq!(unseen, reports, "the crash is announced once");
    assert!(crash_take_unseen().expect("unseen").is_empty());

    // A panic of this run is saved right away; the home folder never appears in it.
    let home = std::env::var("HOME").ok().filter(|h| h.len() > 1);
    let secret_path = format!("{}/secret.db", home.as_deref().unwrap_or("/home/someone"));
    let panicked = std::thread::Builder::new()
        .name("crash-test".to_owned())
        .spawn(move || panic!("cannot open {secret_path}"))
        .expect("thread")
        .join();
    assert!(panicked.is_err());
    let reports = wait_for_reports(2);
    assert_eq!(reports.len(), 2, "{reports:?}");
    let panic = reports
        .iter()
        .find(|r| r.kind == CrashKind::Panic)
        .expect("panic report");
    let text = crash_read(panic.id.clone()).expect("read");
    assert!(text.starts_with("Mumcord crash report\n"), "{text}");
    assert!(text.contains("thread: crash-test"), "{text}");
    if let Some(home) = &home {
        assert!(!text.contains(home.as_str()), "home folder in the report");
        assert!(text.contains("~/secret.db"), "{text}");
    }

    // The upload sends the report unchanged with the comment as its last section.
    let preview =
        crash_upload_preview(panic.id.clone(), "  It crashed when I joined.  ".to_owned())
            .expect("preview");
    assert_eq!(
        preview,
        format!(
            "{text}{}\ncomment:\nIt crashed when I joined.\n",
            if text.ends_with('\n') { "" } else { "\n" }
        )
    );
    assert_eq!(
        crash_upload_preview(panic.id.clone(), " ".to_owned()).expect("preview"),
        text
    );

    // Only report files are reachable by id.
    for id in ["../crash-reports/running", "running", "crash-0.txt"] {
        assert!(
            matches!(crash_read(id.to_owned()), Err(BridgeError::NotFound { .. })),
            "{id}"
        );
    }

    // An upload of a report that does not exist fails before anything is sent.
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: root.join("data").to_string_lossy().into_owned(),
            audio: None,
        },
        Arc::new(MemoryStorage::default()),
        Arc::new(Ignore),
    )
    .expect("engine");
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("runtime");
    let upload = runtime.block_on(engine.crash_upload("crash-0.txt".to_owned(), String::new()));
    assert!(
        matches!(upload, Err(BridgeError::NotFound { .. })),
        "{upload:?}"
    );

    crash_delete(native.id.clone()).expect("delete");
    let left = crash_list().expect("list");
    assert_eq!(left.len(), 1);
    assert_eq!(left[0].kind, CrashKind::Panic);
    assert!(matches!(
        crash_delete(native.id.clone()),
        Err(BridgeError::NotFound { .. })
    ));
    crash_delete_all().expect("delete all");
    assert!(crash_list().expect("list").is_empty());
}
