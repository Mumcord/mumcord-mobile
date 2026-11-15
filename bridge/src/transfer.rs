// SPDX-License-Identifier: AGPL-3.0-only

//! "Send to your phone", the phone side: the QR codes the desktop app shows are collected, opened
//! with the code the user types, shown as a preview and imported.
//!
//! The format, its checks and the import are the engine's (`mumcord_store::transfer`). The
//! opened transfer holds private keys: it stays inside the [`TransferScan`] object and only
//! labels, fingerprints and server entries cross the bridge. The keys are wiped from memory when
//! the transfer is imported, reset or the object is released. Nothing here touches a network,
//! and nothing of a transfer (parts, code, names) is logged.

use std::sync::{Arc, Mutex, TryLockError};

use mumcord_engine::Engine;
use mumcord_engine::store::cert_hash;
use mumcord_engine::store::transfer::{
    self, DEFAULT_CODE_DIGITS, SkipReason, TransferPayload, TransferReader,
};
use tokio::runtime::Handle;

use crate::engine::{MumcordEngine, Saved};
use crate::error::BridgeError;
use crate::events::lock;
use crate::types::{Identity, SavedServer};

/// Why a scanned QR code or the typed code was not accepted. Where it says that the scan starts
/// over, the collected codes are gone and the progress begins at zero again.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum TransferError {
    /// The scanned text is not a QR code of a Mumcord transfer.
    #[error("this QR code is not part of a Mumcord transfer")]
    NotATransfer,
    /// The QR code was not read correctly; scanning it again helps.
    #[error("this QR code was not read correctly")]
    BadChecksum,
    /// The QR code belongs to another transfer than the ones collected so far (the desktop
    /// created a new one): call `reset` and add it again.
    #[error("this QR code belongs to another transfer")]
    OtherTransfer,
    /// The transfer was made by a newer desktop app than this app understands.
    #[error("this transfer needs a newer version of the app")]
    UnsupportedVersion,
    /// Older than two minutes. The scan starts over, with a new transfer on the desktop.
    #[error("the transfer has expired")]
    Expired,
    /// The clocks of the computer and the phone differ too much. The scan starts over.
    #[error("the clocks of the computer and the phone differ too much")]
    ClockSkew,
    /// The code was typed before all QR codes were scanned.
    #[error("not all QR codes have been scanned yet")]
    Incomplete,
    /// The typed code does not have `digits` digits. Not counted as an attempt.
    #[error("the code has {digits} digits")]
    InvalidCode { digits: u8 },
    /// The code is wrong; `attempts_left` more tries before the scan starts over.
    #[error("wrong code")]
    WrongCode { attempts_left: u8 },
    /// The code was wrong three times. The scan starts over.
    #[error("wrong code; scan the QR codes again")]
    RescanRequired,
    /// All QR codes were read, but their content is not a valid transfer. The scan starts
    /// over, with a new transfer on the desktop.
    #[error("the transfer is damaged")]
    Damaged,
    /// The phone could not get the memory the check of the code needs; trying again can help.
    #[error("not enough memory to check the code")]
    OutOfMemory,
    /// The check of the code did not run to its end.
    #[error("checking the code failed")]
    Failed,
}

impl From<transfer::TransferError> for TransferError {
    fn from(error: transfer::TransferError) -> Self {
        use transfer::TransferError as E;
        match error {
            E::MalformedPart => Self::NotATransfer,
            E::ChecksumMismatch => Self::BadChecksum,
            E::OtherTransfer => Self::OtherTransfer,
            E::UnsupportedVersion => Self::UnsupportedVersion,
            E::Expired => Self::Expired,
            E::ClockSkew => Self::ClockSkew,
            E::Incomplete => Self::Incomplete,
            E::InvalidCode { digits } => Self::InvalidCode { digits },
            E::WrongCode { attempts_left } => Self::WrongCode { attempts_left },
            E::RescanRequired => Self::RescanRequired,
            E::Malformed => Self::Damaged,
            E::OutOfMemory => Self::OutOfMemory,
            // The remaining kinds belong to writing a transfer, which the phone never does.
            E::PayloadTooLarge | E::InvalidPayload(_) | E::InvalidOptions(_) | E::Random => {
                Self::Failed
            }
        }
    }
}

/// How far the scan is: "Code `have` of `total`".
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct TransferProgress {
    /// Different QR codes collected so far.
    pub have: u8,
    /// QR codes the transfer consists of.
    pub total: u8,
}

/// An identity inside an opened transfer. Its key stays in the bridge.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TransferIdentityPreview {
    /// The label it has on the computer.
    pub label: String,
    /// Certificate hash, 40 hex digits, as [`Identity::fingerprint`].
    pub fingerprint: String,
    /// It is the default identity on the computer. On the phone it becomes the default only
    /// when the phone has no identity yet.
    pub is_default: bool,
}

/// A server inside an opened transfer. Passwords and channel passwords are never transferred.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TransferServerPreview {
    pub name: String,
    pub address: String,
    pub port: u16,
    pub username: String,
}

/// What an opened transfer would bring, for the confirmation screen.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TransferPreview {
    pub identities: Vec<TransferIdentityPreview>,
    /// `None` when the server list is not part of the transfer.
    pub servers: Option<Vec<TransferServerPreview>>,
}

/// Why an entry of a transfer was not imported.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TransferSkipReason {
    /// It is already on the phone: an identity with the same certificate, or a server with the
    /// same name, address, port and user name.
    Duplicate,
    /// It cannot be used, e.g. a key that does not belong to its certificate.
    Invalid,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SkippedIdentity {
    pub label: String,
    pub reason: TransferSkipReason,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SkippedServer {
    pub name: String,
    pub address: String,
    pub reason: TransferSkipReason,
}

/// What an import did, for the summary.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ImportReport {
    pub identities_imported: Vec<Identity>,
    pub identities_skipped: Vec<SkippedIdentity>,
    pub servers_imported: Vec<SavedServer>,
    pub servers_skipped: Vec<SkippedServer>,
}

fn skip_reason(reason: SkipReason) -> TransferSkipReason {
    match reason {
        SkipReason::Duplicate => TransferSkipReason::Duplicate,
        SkipReason::Invalid => TransferSkipReason::Invalid,
    }
}

fn preview(payload: &TransferPayload) -> TransferPreview {
    TransferPreview {
        identities: payload
            .identities
            .iter()
            .map(|identity| TransferIdentityPreview {
                label: identity.label.clone(),
                fingerprint: cert_hash(&identity.cert_der),
                is_default: identity.is_default,
            })
            .collect(),
        servers: payload.servers.as_ref().map(|servers| {
            servers
                .iter()
                .map(|server| TransferServerPreview {
                    name: server.name.clone(),
                    address: server.address.clone(),
                    port: server.port,
                    username: server.username.clone(),
                })
                .collect()
        }),
    }
}

/// The time the validity of a transfer is checked against, in seconds since 1970.
pub(crate) type Clock = Arc<dyn Fn() -> u64 + Send + Sync>;

/// What one scan holds. `Debug` of the reader prints the progress only; the opened transfer
/// is never printed.
#[derive(Default)]
struct Scan {
    reader: TransferReader,
    /// The transfer once the code was right, until it is imported or the scan is reset. The
    /// keys in it are wiped when it is dropped.
    opened: Option<TransferPayload>,
}

/// Where the scan stands, as the app can ask at any time.
#[derive(Clone, Copy)]
struct Standing {
    progress: Option<TransferProgress>,
    code_digits: u8,
    attempts_left: u8,
}

impl Standing {
    fn of(reader: &TransferReader) -> Self {
        Self {
            progress: reader.progress().map(|progress| TransferProgress {
                have: progress.have,
                total: progress.total,
            }),
            code_digits: reader.code_digits().unwrap_or(DEFAULT_CODE_DIGITS),
            attempts_left: reader.attempts_left(),
        }
    }
}

/// One "Send to your phone" scan: feed every scanned QR text to `add_part`, in any order, then
/// `open` it with the typed code, show the preview and `import_opened`. Create it with
/// [`MumcordEngine::new_transfer`] when the scanner opens and release it when the screen closes.
#[derive(uniffi::Object)]
pub struct TransferScan {
    engine: Engine,
    saved: Saved,
    runtime: Handle,
    /// Held for the one to two seconds the typed code is checked.
    scan: Arc<Mutex<Scan>>,
    /// The standing after the last step, so the quick questions never wait for that check.
    standing: Arc<Mutex<Standing>>,
    clock: Clock,
}

impl TransferScan {
    pub(crate) fn new(engine: &MumcordEngine, clock: Clock) -> Arc<Self> {
        Arc::new(Self {
            engine: engine.engine.clone(),
            saved: engine.saved.clone(),
            runtime: engine.runtime(),
            scan: Arc::default(),
            standing: Arc::new(Mutex::new(Standing::of(&TransferReader::new()))),
            clock,
        })
    }
}

/// Notes where the scan stands after a step on it.
fn note(standing: &Mutex<Standing>, scan: &Scan) {
    *lock(standing) = Standing::of(&scan.reader);
}

#[uniffi::export]
impl MumcordEngine {
    /// Starts a "Send to your phone" scan.
    pub fn new_transfer(&self) -> Arc<TransferScan> {
        TransferScan::new(self, Arc::new(transfer::unix_now))
    }
}

#[uniffi::export]
impl TransferScan {
    /// Adds the text of one scanned QR code. A code that was scanned before changes nothing, so
    /// the scanner can hand in everything it sees. The code can be asked for once `have` equals
    /// `total`. `Expired` and `ClockSkew` start the scan over; after `OtherTransfer` call
    /// `reset` and add the text again. Quick; safe to call from the scanner's callback.
    pub fn add_part(&self, text: String) -> Result<TransferProgress, TransferError> {
        // While the typed code is being checked all codes are there already: a code the
        // camera still sees changes nothing and must not wait for the check.
        let mut scan = match self.scan.try_lock() {
            Ok(scan) => scan,
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
            Err(TryLockError::WouldBlock) => {
                return lock(&self.standing)
                    .progress
                    .ok_or(TransferError::Incomplete);
            }
        };
        let added = scan.reader.add_part(&text, (self.clock)());
        note(&self.standing, &scan);
        let progress = added?;
        Ok(TransferProgress {
            have: progress.have,
            total: progress.total,
        })
    }

    /// Where the scan stands; `None` before the first QR code.
    pub fn progress(&self) -> Option<TransferProgress> {
        lock(&self.standing).progress
    }

    /// How many digits the code has: 8 (shown as two groups of four) unless the transfer says
    /// otherwise, which is known once its first QR code was scanned (6 to 10).
    pub fn code_digits(&self) -> u8 {
        lock(&self.standing).code_digits
    }

    /// Wrong codes that are still allowed before the scan starts over (3 at the start).
    pub fn attempts_left(&self) -> u8 {
        lock(&self.standing).attempts_left
    }

    /// Forgets everything: the collected QR codes and an opened transfer. Waits for a check of
    /// the typed code that is running.
    pub fn reset(&self) {
        let mut scan = lock(&self.scan);
        *scan = Scan::default();
        note(&self.standing, &scan);
    }

    /// Opens the complete transfer with the typed code (digits only) and says what it holds.
    /// Takes one to two seconds. A wrong code is `WrongCode` with the attempts that are left;
    /// the third one is `RescanRequired`. `Expired`, `ClockSkew`, `RescanRequired` and
    /// `Damaged` start the scan over.
    pub async fn open(&self, code: String) -> Result<TransferPreview, TransferError> {
        let (scan, standing, clock) =
            (self.scan.clone(), self.standing.clone(), self.clock.clone());
        self.runtime
            // The key derivation is slow on purpose and needs 128 MiB: off the async threads.
            .spawn_blocking(move || {
                let mut scan = lock(&scan);
                let opened = scan.reader.open(&code, clock());
                note(&standing, &scan);
                let payload = opened?;
                let preview = preview(&payload);
                scan.opened = Some(payload);
                Ok(preview)
            })
            .await
            .map_err(|_| TransferError::Failed)?
    }

    /// Imports the opened transfer: identities go to the secure storage, servers to the server
    /// list. What is already on the phone is skipped and nothing on the phone is changed or
    /// removed. The new lists arrive as `IdentitiesChanged` and `ServersChanged`. Fails with
    /// `InvalidInput` when no transfer is open; after another failure (the secure storage is
    /// locked) the transfer stays open and the import can be tried again.
    pub async fn import_opened(&self) -> Result<ImportReport, BridgeError> {
        let (engine, saved, scan) = (self.engine.clone(), self.saved.clone(), self.scan.clone());
        self.runtime
            .spawn(async move {
                let _gate = saved.gate.lock().await;
                let payload = lock(&scan)
                    .opened
                    .clone()
                    .ok_or_else(|| BridgeError::invalid("no transfer is open"))?;
                let report = engine.transfer_import(payload).await?;
                lock(&scan).opened = None;
                let identities = saved.refresh_identities(&engine).await?;
                saved.refresh(&engine).await?;
                let servers = saved.servers();
                Ok(ImportReport {
                    identities_imported: report
                        .identities_imported
                        .iter()
                        .filter_map(|added| identities.iter().find(|known| known.id == added.id))
                        .cloned()
                        .collect(),
                    identities_skipped: report
                        .identities_skipped
                        .into_iter()
                        .map(|skipped| SkippedIdentity {
                            label: skipped.label,
                            reason: skip_reason(skipped.reason),
                        })
                        .collect(),
                    servers_imported: report
                        .servers_imported
                        .iter()
                        .filter_map(|added| servers.iter().find(|known| known.id == added.id))
                        .cloned()
                        .collect(),
                    servers_skipped: report
                        .servers_skipped
                        .into_iter()
                        .map(|skipped| SkippedServer {
                            name: skipped.name,
                            address: skipped.address,
                            reason: skip_reason(skipped.reason),
                        })
                        .collect(),
                })
            })
            .await
            .map_err(|e| BridgeError::internal(format!("the command was aborted: {e}")))?
    }
}
