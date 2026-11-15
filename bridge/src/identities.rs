// SPDX-License-Identifier: AGPL-3.0-only

//! Identities: the certificates the user is known by on servers. The engine keeps the
//! certificate and its key in the app's secure storage; only label, common name, fingerprint,
//! date and default flag cross the bridge.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use mumcord_engine::Engine;

use crate::convert;
use crate::engine::{MumcordEngine, Saved};
use crate::error::BridgeError;
use crate::types::Identity;

/// The folder below the engine's folder that `.p12` data and avatar images pass through. The
/// engine reads and writes these files by path only, so the bytes are a file here for the
/// length of the call.
const FILES_DIR: &str = "identity-io";
/// The file name is the label an imported certificate without a name and without a typed label
/// gets (the engine falls back to it).
const FILE_NAME: &str = "Identity.p12";

static NEXT_FILE: AtomicU64 = AtomicU64::new(0);

/// Every identity, the default one first.
pub(crate) async fn list(engine: &Engine) -> Result<Vec<Identity>, BridgeError> {
    let identities = engine.identity_list().await?;
    Ok(identities.into_iter().map(convert::identity).collect())
}

/// The default identity; created from `name` when there is no identity at all.
pub(crate) async fn ensure_default(
    engine: &Engine,
    saved: &Saved,
    name: &str,
) -> Result<Identity, BridgeError> {
    let _gate = saved.gate.lock().await;
    if let Some(identity) = engine.identity().await? {
        return Ok(convert::identity(identity));
    }
    let created = engine.identity_create(name.to_owned(), None).await?;
    saved.refresh_identities(engine).await?;
    Ok(convert::identity(created))
}

/// Removes what an import, an export or an avatar change left behind when the app was ended in
/// the middle of it.
pub(crate) fn sweep(data_dir: &Path) {
    match std::fs::remove_dir_all(data_dir.join(FILES_DIR)) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        // Without the path: it names the app's private folder.
        Err(e) => tracing::warn!(kind = ?e.kind(), "removing left-over identity files failed"),
    }
}

/// A file for one import, export or avatar, in a folder of its own that only the app's user can
/// open. Both are removed when the value is dropped, on every way out of the command.
pub(crate) struct PrivateFile {
    dir: PathBuf,
    pub(crate) path: PathBuf,
}

impl PrivateFile {
    /// Creates the file `name` with `content`; an export starts with an empty one, so the
    /// engine writes into a file that already has the right permissions.
    pub(crate) fn create(data_dir: &Path, name: &str, content: &[u8]) -> Result<Self, BridgeError> {
        let failed = |e: std::io::Error| BridgeError::Io {
            // Without the path: it names the app's private folder.
            detail: format!("preparing a private file failed: {:?}", e.kind()),
        };
        let number = NEXT_FILE.fetch_add(1, Ordering::Relaxed);
        let dir = data_dir
            .join(FILES_DIR)
            .join(format!("{}-{number}", std::process::id()));
        let mut builder = std::fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        std::os::unix::fs::DirBuilderExt::mode(&mut builder, 0o700);
        builder.create(&dir).map_err(failed)?;
        // From here on dropping removes the folder again.
        let file = Self {
            path: dir.join(name),
            dir,
        };
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
        let mut handle = options.open(&file.path).map_err(failed)?;
        handle.write_all(content).map_err(failed)?;
        handle.sync_all().map_err(failed)?;
        Ok(file)
    }
}

impl Drop for PrivateFile {
    fn drop(&mut self) {
        if let Err(e) = std::fs::remove_dir_all(&self.dir) {
            tracing::warn!(kind = ?e.kind(), "removing a private file failed");
        }
    }
}

#[uniffi::export]
impl MumcordEngine {
    /// Every identity, the default one first; also part of the snapshot. A change arrives as
    /// [`BridgeEvent::IdentitiesChanged`](crate::BridgeEvent::IdentitiesChanged).
    pub async fn identities(&self) -> Result<Vec<Identity>, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { list(&engine).await }).await
    }

    /// The default identity; when there is no identity at all, one is created with `name` as
    /// the name inside the certificate (the app's first start). Connecting still does the same
    /// silently with the server's user name, until both apps call this during their setup.
    pub async fn ensure_default_identity(&self, name: String) -> Result<Identity, BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move { ensure_default(&engine, &saved, &name).await })
            .await
    }

    /// Creates a new identity (a self-signed certificate with the parameters of the desktop
    /// app) with `name` inside the certificate. `label` is the local label; `None` uses the
    /// name. The first identity becomes the default.
    pub async fn create_identity(
        &self,
        name: String,
        label: Option<String>,
    ) -> Result<Identity, BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let created = engine.identity_create(name, label).await?;
            saved.refresh_identities(&engine).await?;
            Ok(convert::identity(created))
        })
        .await
    }

    /// Imports the bytes of a `.p12` / `.pfx` file as an additional identity. `password` is
    /// the file's password, empty for none. `label` is the local label; `None` uses the name
    /// inside the certificate. Fails with `DuplicateIdentity` when the certificate is already
    /// in the list, and with `InvalidInput` for a wrong password or a file that holds no
    /// identity.
    pub async fn import_identity(
        &self,
        data: Vec<u8>,
        password: String,
        label: Option<String>,
    ) -> Result<Identity, BridgeError> {
        let (engine, saved, data_dir) = (
            self.engine.clone(),
            self.saved.clone(),
            self.data_dir.clone(),
        );
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let file = PrivateFile::create(&data_dir, FILE_NAME, &data)?;
            let password = Some(password).filter(|password| !password.is_empty());
            let imported = engine
                .identity_import(file.path.clone(), password, label)
                .await;
            drop(file);
            let imported = imported?;
            saved.refresh_identities(&engine).await?;
            Ok(convert::identity(imported))
        })
        .await
    }

    /// The identity as the bytes of a `.p12` file protected with `password`, for the system's
    /// share sheet. Fails with `InvalidInput` for an empty password.
    pub async fn export_identity(
        &self,
        identity_id: i64,
        password: String,
    ) -> Result<Vec<u8>, BridgeError> {
        let (engine, data_dir) = (self.engine.clone(), self.data_dir.clone());
        self.run(async move {
            if password.is_empty() {
                return Err(BridgeError::invalid("the export password is empty"));
            }
            let file = PrivateFile::create(&data_dir, FILE_NAME, &[])?;
            engine
                .identity_export(identity_id, file.path.clone(), password)
                .await?;
            std::fs::read(&file.path).map_err(|e| BridgeError::Io {
                detail: format!("reading the identity file failed: {:?}", e.kind()),
            })
        })
        .await
    }

    /// Makes an identity the default: servers that name no identity of their own connect with
    /// it from their next connection on.
    pub async fn set_default_identity(&self, identity_id: i64) -> Result<(), BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            engine.identity_set_default(identity_id).await?;
            saved.refresh_identities(&engine).await?;
            Ok(())
        })
        .await
    }

    /// Changes the local label of an identity; the certificate stays as it is.
    pub async fn rename_identity(
        &self,
        identity_id: i64,
        label: String,
    ) -> Result<Identity, BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let renamed = engine.identity_rename(identity_id, label).await?;
            saved.refresh_identities(&engine).await?;
            Ok(convert::identity(renamed))
        })
        .await
    }

    /// Deletes an identity with its certificate and key. Servers that named it use the default
    /// identity again (the list arrives as `ServersChanged`); a connection made with it is
    /// ended first. Fails with `LastIdentity` for the only identity.
    pub async fn delete_identity(&self, identity_id: i64) -> Result<(), BridgeError> {
        let (engine, saved) = (self.engine.clone(), self.saved.clone());
        self.run(async move {
            let _gate = saved.gate.lock().await;
            let known = engine.identity_list().await?;
            if known.len() == 1 && known[0].id == identity_id {
                return Err(BridgeError::LastIdentity {
                    detail: "the last identity cannot be deleted".to_owned(),
                });
            }
            engine.identity_delete(identity_id).await?;
            saved.refresh_identities(&engine).await?;
            saved.refresh(&engine).await
        })
        .await
    }
}

#[cfg(all(test, unix))]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use super::*;

    #[test]
    fn an_identity_file_is_private_to_the_app_and_gone_after_use() {
        let data_dir = std::env::temp_dir().join(format!(
            "mumcord-bridge-identity-file-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&data_dir);
        let file = PrivateFile::create(&data_dir, FILE_NAME, b"content").expect("file");
        let mode = |path: &Path| {
            std::fs::metadata(path)
                .expect("metadata")
                .permissions()
                .mode()
                & 0o777
        };
        assert_eq!(mode(&file.path), 0o600);
        assert_eq!(mode(&file.dir), 0o700);
        assert_eq!(std::fs::read(&file.path).expect("content"), b"content");
        // A second file never shares the folder of the first.
        let second = PrivateFile::create(&data_dir, "avatar", b"").expect("second file");
        assert_ne!(second.dir, file.dir);
        let (first_dir, second_dir) = (file.dir.clone(), second.dir.clone());
        drop(file);
        drop(second);
        assert!(!first_dir.exists() && !second_dir.exists());
        sweep(&data_dir);
        assert!(!data_dir.join(FILES_DIR).exists());
        let _ = std::fs::remove_dir_all(&data_dir);
    }
}
