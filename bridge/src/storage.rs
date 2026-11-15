// SPDX-License-Identifier: AGPL-3.0-only

//! The phone's secure storage, implemented by the app and used by the engine for the identity
//! and for server passwords.

use std::sync::Arc;

use mumcord_store::{Error, Result, SecretBackend, SecretBackendError};

/// A failed secure-storage call (storage locked, access denied, …).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum SecureStorageError {
    /// `detail` is logged, so it must never contain the secret.
    #[error("{detail}")]
    Failed { detail: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for SecureStorageError {
    fn from(error: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed {
            detail: error.reason,
        }
    }
}

/// Secure storage of the phone (Android: Keystore-protected storage, iOS: Keychain), implemented
/// in Kotlin and Swift: a flat map from an account name to a secret.
///
/// Rules for an implementation:
/// - Account names are short ASCII paths such as `identity/3/0`. They are chosen by the engine,
///   must be kept exactly and are not secret.
/// - Secrets are opaque bytes of any length (several kilobytes) and must come back unchanged.
/// - A missing item is `null`/`nil` on `get` and no error on `delete`.
/// - Calls may block and come from any thread except audio threads.
/// - Secrets must not leave the device: no backups, no cloud sync.
#[uniffi::export(with_foreign)]
pub trait SecureStorage: Send + Sync {
    /// Stores `secret` under `account`, replacing an existing item.
    fn set(&self, account: String, secret: Vec<u8>) -> std::result::Result<(), SecureStorageError>;
    fn get(&self, account: String) -> std::result::Result<Option<Vec<u8>>, SecureStorageError>;
    fn delete(&self, account: String) -> std::result::Result<(), SecureStorageError>;
}

/// Hands the app's [`SecureStorage`] to the engine's secret store.
pub(crate) struct StorageBackend(pub(crate) Arc<dyn SecureStorage>);

fn store_error(error: SecureStorageError) -> Error {
    Error::SecureStorage(SecretBackendError::new(error))
}

impl SecretBackend for StorageBackend {
    fn set(&self, account: &str, secret: &[u8]) -> Result<()> {
        self.0
            .set(account.to_owned(), secret.to_vec())
            .map_err(store_error)
    }

    fn get(&self, account: &str) -> Result<Option<Vec<u8>>> {
        self.0.get(account.to_owned()).map_err(store_error)
    }

    fn delete(&self, account: &str) -> Result<()> {
        self.0.delete(account.to_owned()).map_err(store_error)
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::Mutex;

    use super::*;

    #[derive(Default)]
    struct Memory {
        items: Mutex<HashMap<String, Vec<u8>>>,
        locked: bool,
    }

    impl Memory {
        fn check(&self) -> std::result::Result<(), SecureStorageError> {
            if self.locked {
                return Err(SecureStorageError::Failed {
                    detail: "storage is locked".into(),
                });
            }
            Ok(())
        }
    }

    impl SecureStorage for Memory {
        fn set(
            &self,
            account: String,
            secret: Vec<u8>,
        ) -> std::result::Result<(), SecureStorageError> {
            self.check()?;
            self.items.lock().expect("items").insert(account, secret);
            Ok(())
        }

        fn get(&self, account: String) -> std::result::Result<Option<Vec<u8>>, SecureStorageError> {
            self.check()?;
            Ok(self.items.lock().expect("items").get(&account).cloned())
        }

        fn delete(&self, account: String) -> std::result::Result<(), SecureStorageError> {
            self.check()?;
            self.items.lock().expect("items").remove(&account);
            Ok(())
        }
    }

    #[test]
    fn items_round_trip_and_missing_items_are_no_error() {
        let backend = StorageBackend(Arc::new(Memory::default()));
        assert_eq!(backend.get("server/1").expect("get"), None);
        backend
            .delete("server/1")
            .expect("delete of a missing item");
        backend.set("server/1", &[0, 159, 255]).expect("set");
        assert_eq!(
            backend.get("server/1").expect("get"),
            Some(vec![0, 159, 255])
        );
        backend.delete("server/1").expect("delete");
        assert_eq!(backend.get("server/1").expect("get"), None);
    }

    #[test]
    fn a_failure_becomes_a_secure_storage_error_without_the_secret() {
        let backend = StorageBackend(Arc::new(Memory {
            locked: true,
            ..Memory::default()
        }));
        let error = backend
            .set("server/1", b"hunter2-secret")
            .expect_err("locked storage");
        assert!(matches!(error, Error::SecureStorage(_)), "{error:?}");
        let text = error.to_string();
        assert!(text.contains("storage is locked"), "{text}");
        assert!(!text.contains("hunter2-secret"), "{text}");
        assert!(matches!(
            backend.get("server/1"),
            Err(Error::SecureStorage(_))
        ));
        assert!(matches!(
            backend.delete("server/1"),
            Err(Error::SecureStorage(_))
        ));
    }
}
