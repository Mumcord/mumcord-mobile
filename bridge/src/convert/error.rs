// SPDX-License-Identifier: AGPL-3.0-only

use mumcord_engine::{EngineError, ErrorKind};

use crate::error::BridgeError;

/// How the engine starts the text of a ban; it has no error kind of its own for it.
const BANNED_PREFIX: &str = "banned: ";

impl From<EngineError> for BridgeError {
    fn from(error: EngineError) -> Self {
        let detail = error.message;
        match error.kind {
            ErrorKind::PermissionDenied => match detail.strip_prefix(BANNED_PREFIX) {
                Some(reason) => Self::Banned {
                    detail: reason.to_owned(),
                },
                None => Self::PermissionDenied { detail },
            },
            ErrorKind::NotFound => Self::NotFound { detail },
            ErrorKind::NotConnected => Self::NotConnected { detail },
            ErrorKind::ServerUnreachable => Self::ServerUnreachable { detail },
            ErrorKind::WrongPassword => Self::WrongPassword { detail },
            ErrorKind::NameInUse => Self::NameInUse { detail },
            ErrorKind::CertificateRejected => Self::CertificateRejected { detail },
            ErrorKind::CertificateChanged => Self::CertificateChanged { detail },
            ErrorKind::ServerTooOld => Self::ServerTooOld { detail },
            ErrorKind::InvalidInput => Self::InvalidInput { detail },
            ErrorKind::SecureStorageUnavailable => Self::SecureStorageUnavailable { detail },
            ErrorKind::Io => Self::Io { detail },
            // Kinds of features the bridge does not offer yet keep their message.
            _ => Self::Internal { detail },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connection_kinds_keep_their_kind_and_message() {
        let cases = [
            (ErrorKind::ServerUnreachable, "ServerUnreachable"),
            (ErrorKind::WrongPassword, "WrongPassword"),
            (ErrorKind::NameInUse, "NameInUse"),
            (ErrorKind::CertificateRejected, "CertificateRejected"),
            (ErrorKind::CertificateChanged, "CertificateChanged"),
            (ErrorKind::ServerTooOld, "ServerTooOld"),
            (ErrorKind::PermissionDenied, "PermissionDenied"),
            (ErrorKind::NotFound, "NotFound"),
            (
                ErrorKind::SecureStorageUnavailable,
                "SecureStorageUnavailable",
            ),
        ];
        for (kind, name) in cases {
            let error = BridgeError::from(EngineError::new(kind, "server said no"));
            let debug = format!("{error:?}");
            assert!(debug.starts_with(name), "{debug}");
            assert!(debug.contains("server said no"), "{debug}");
        }
    }

    #[test]
    fn a_ban_is_told_apart_from_other_refusals() {
        let banned = EngineError::new(ErrorKind::PermissionDenied, "banned: spamming");
        assert_eq!(
            BridgeError::from(banned),
            BridgeError::Banned {
                detail: "spamming".into()
            }
        );
        let no_reason = EngineError::new(ErrorKind::PermissionDenied, "banned: ");
        assert_eq!(
            BridgeError::from(no_reason),
            BridgeError::Banned {
                detail: String::new()
            }
        );
        let kicked = EngineError::new(ErrorKind::PermissionDenied, "kicked: bye");
        assert_eq!(
            BridgeError::from(kicked),
            BridgeError::PermissionDenied {
                detail: "kicked: bye".into()
            }
        );
    }

    #[test]
    fn other_kinds_become_internal() {
        let error = BridgeError::from(EngineError::new(ErrorKind::ShareFull, "full"));
        assert_eq!(error, BridgeError::internal("full"));
    }
}
