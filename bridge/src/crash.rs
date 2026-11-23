// SPDX-License-Identifier: AGPL-3.0-only

//! Crash reports, the same as on the desktop (FR-5.103): the engine saves a report for a Rust
//! panic or a fatal signal on the phone; nothing leaves it until the user sends one report
//! themselves.
//!
//! The functions are free functions because reporting has to start before the
//! [`MumcordEngine`] exists, so that a crash while it starts is caught too. Only the upload
//! needs the engine's runtime and is a method.

use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use mumcord_engine::crash;

use crate::engine::MumcordEngine;
use crate::error::BridgeError;
use crate::events::lock;

/// The folder [`crash_install`] set up; `None` until then.
static DIR: Mutex<Option<PathBuf>> = Mutex::new(None);

/// What ended the run a report is about.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum CrashKind {
    /// A Rust panic in the engine or the bridge.
    Panic,
    /// A fatal signal such as `SIGSEGV`, noticed at the next start.
    Signal { name: String },
    /// The previous run ended without a clean exit and without a signal the engine saw: the
    /// system ended the app, or the phone lost power. Listed, but never part of
    /// [`crash_take_unseen`].
    Unexpected,
}

/// One saved report, as the crash list shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CrashReport {
    /// The id the other crash functions take.
    pub id: String,
    /// When the report was written, milliseconds since 1970.
    pub created_ms: i64,
    /// The app version that crashed.
    pub version: String,
    pub kind: CrashKind,
    /// First line of the cause, e.g. the panic message.
    pub cause: String,
    /// The user already sent it.
    pub uploaded: bool,
}

impl From<crash::CrashSummary> for CrashReport {
    fn from(summary: crash::CrashSummary) -> Self {
        // Mapped as the desktop's crash list does: an overrun is the signal it is, a freeze has
        // no kind of its own yet. Neither is reported on a phone so far.
        let kind = match summary.kind {
            crash::CrashKind::Panic => CrashKind::Panic,
            crash::CrashKind::Signal(name) => CrashKind::Signal { name },
            crash::CrashKind::Overrun => CrashKind::Signal {
                name: "SIGXCPU".to_owned(),
            },
            crash::CrashKind::Unexpected | crash::CrashKind::Freeze => CrashKind::Unexpected,
        };
        Self {
            id: summary.id,
            created_ms: i64::try_from(summary.created_unix)
                .unwrap_or(i64::MAX)
                .saturating_mul(1000),
            version: summary.version,
            kind,
            cause: summary.cause,
            uploaded: summary.uploaded,
        }
    }
}

/// Starts crash reporting for this run: saves a report about an unclean previous run, catches
/// panics and fatal signals from now on. `dir` is an app-private folder of its own (e.g.
/// `crash-reports` inside the app's files); `app_version` is written into every report.
///
/// Call it once at app start, before [`MumcordEngine::new`]. Calling it again with the same
/// folder does nothing; another folder is refused, because the engine's handlers are set up
/// once per process.
#[uniffi::export]
pub fn crash_install(dir: String, app_version: String) -> Result<(), BridgeError> {
    let mut installed = lock(&DIR);
    if let Some(current) = installed.as_deref() {
        return if current == Path::new(&dir) {
            Ok(())
        } else {
            Err(BridgeError::invalid(
                "crash reports are already set up in another folder",
            ))
        };
    }
    let dir = PathBuf::from(dir);
    crash::install(&dir, &app_version).map_err(|e| io_error("setting up crash reports", &e))?;
    *installed = Some(dir);
    Ok(())
}

/// The saved reports, newest first.
#[uniffi::export]
pub fn crash_list() -> Result<Vec<CrashReport>, BridgeError> {
    Ok(crash::list(&dir()?).into_iter().map(Into::into).collect())
}

/// Reports from crashes the user has not been told about yet, for the notice after a crash.
/// Each report is returned once; afterwards all count as shown. Unexpected exits never bring a
/// notice: most are the system ending the app, not a crash.
#[uniffi::export]
pub fn crash_take_unseen() -> Result<Vec<CrashReport>, BridgeError> {
    Ok(crash::take_unseen(&dir()?)
        .into_iter()
        .map(Into::into)
        .collect())
}

/// The full text of one report, for the details view.
#[uniffi::export]
pub fn crash_read(id: String) -> Result<String, BridgeError> {
    crash::read(&dir()?, &id).map_err(|e| io_error("reading the crash report", &e))
}

/// Exactly what [`MumcordEngine::crash_upload`] would send for this report and comment: the
/// report unchanged, the comment as a last section when it is not empty. The upload dialog
/// shows it before the user confirms.
#[uniffi::export]
pub fn crash_upload_preview(id: String, comment: String) -> Result<String, BridgeError> {
    crash::upload_body(&dir()?, &id, &comment).map_err(|e| io_error("reading the crash report", &e))
}

#[uniffi::export]
pub fn crash_delete(id: String) -> Result<(), BridgeError> {
    crash::delete(&dir()?, &id).map_err(|e| io_error("deleting the crash report", &e))
}

#[uniffi::export]
pub fn crash_delete_all() -> Result<(), BridgeError> {
    crash::delete_all(&dir()?).map_err(|e| io_error("deleting the crash reports", &e))
}

#[uniffi::export]
impl MumcordEngine {
    /// Sends one report to the Mumcord crash server (`crash.mumcord.app`, kept there for 90
    /// days) with the user's comment, exactly as [`crash_upload_preview`] shows it. Only for the
    /// user's own confirmation in the upload dialog: nothing else sends a report. The server's
    /// certificate is checked against the system's trusted certificates; on Android that needs
    /// the start-up call described under "Android start-up".
    pub async fn crash_upload(&self, id: String, comment: String) -> Result<(), BridgeError> {
        let dir = dir()?;
        // A missing report is the app's mistake, not the network's; say so before connecting.
        crash::read(&dir, &id).map_err(|e| io_error("reading the crash report", &e))?;
        self.run(async move {
            crash::upload(&dir, &id, &comment)
                .await
                .map_err(|e| BridgeError::ServerUnreachable {
                    detail: format!("uploading the crash report failed: {e}"),
                })
        })
        .await
    }
}

fn dir() -> Result<PathBuf, BridgeError> {
    lock(&DIR).clone().ok_or_else(|| BridgeError::Io {
        detail: "crash reports are not set up".to_owned(),
    })
}

fn io_error(what: &str, e: &io::Error) -> BridgeError {
    let detail = format!("{what} failed: {e}");
    match e.kind() {
        io::ErrorKind::NotFound => BridgeError::NotFound { detail },
        _ => BridgeError::Io { detail },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn summary(kind: crash::CrashKind) -> crash::CrashSummary {
        crash::CrashSummary {
            id: "crash-1700000000.txt".to_owned(),
            created_unix: 1_700_000_000,
            version: "0.1.0".to_owned(),
            kind,
            cause: "boom".to_owned(),
            uploaded: true,
        }
    }

    #[test]
    fn a_summary_becomes_a_report_like_on_the_desktop() {
        let report = CrashReport::from(summary(crash::CrashKind::Signal("SIGSEGV".to_owned())));
        assert_eq!(
            report,
            CrashReport {
                id: "crash-1700000000.txt".to_owned(),
                created_ms: 1_700_000_000_000,
                version: "0.1.0".to_owned(),
                kind: CrashKind::Signal {
                    name: "SIGSEGV".to_owned()
                },
                cause: "boom".to_owned(),
                uploaded: true,
            }
        );
        let kind = |k| CrashReport::from(summary(k)).kind;
        assert_eq!(kind(crash::CrashKind::Panic), CrashKind::Panic);
        assert_eq!(kind(crash::CrashKind::Unexpected), CrashKind::Unexpected);
        assert_eq!(kind(crash::CrashKind::Freeze), CrashKind::Unexpected);
        assert_eq!(
            kind(crash::CrashKind::Overrun),
            CrashKind::Signal {
                name: "SIGXCPU".to_owned()
            }
        );
    }

    #[test]
    fn a_missing_report_is_not_found_and_other_failures_are_storage_errors() {
        let missing = io::Error::new(io::ErrorKind::NotFound, "no such crash report");
        assert!(matches!(
            io_error("reading", &missing),
            BridgeError::NotFound { .. }
        ));
        let denied = io::Error::new(io::ErrorKind::PermissionDenied, "denied");
        assert!(matches!(
            io_error("reading", &denied),
            BridgeError::Io { .. }
        ));
    }
}
