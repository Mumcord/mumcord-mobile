// SPDX-License-Identifier: AGPL-3.0-only

use mumcord_engine::{EngineError, ErrorKind};

use crate::error::BridgeError;

impl From<EngineError> for BridgeError {
    fn from(error: EngineError) -> Self {
        let detail = error.message;
        match error.kind {
            // Banned while connected; `detail` is the server's reason, possibly empty.
            ErrorKind::Banned => Self::Banned { detail },
            ErrorKind::PermissionDenied => Self::PermissionDenied { detail },
            ErrorKind::DuplicateIdentity => Self::DuplicateIdentity { detail },
            ErrorKind::NotFound => Self::NotFound { detail },
            ErrorKind::NotConnected => Self::NotConnected { detail },
            ErrorKind::ServerUnreachable => Self::ServerUnreachable { detail },
            ErrorKind::WrongPassword => Self::WrongPassword { detail },
            ErrorKind::NameInUse => Self::NameInUse { detail },
            ErrorKind::CertificateRejected => Self::CertificateRejected { detail },
            ErrorKind::CertificateChanged => Self::CertificateChanged { detail },
            ErrorKind::ServerTooOld => Self::ServerTooOld { detail },
            // A message above the server's size limit, found here or refused by the server.
            ErrorKind::InvalidInput | ErrorKind::MessageTooLarge => Self::InvalidInput { detail },
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
        let banned = EngineError::new(ErrorKind::Banned, "spamming");
        assert_eq!(
            BridgeError::from(banned),
            BridgeError::Banned {
                detail: "spamming".into()
            }
        );
        let no_reason = EngineError::new(ErrorKind::Banned, "");
        assert_eq!(
            BridgeError::from(no_reason),
            BridgeError::Banned {
                detail: String::new()
            }
        );
        // A refusal is never read as a ban by its text.
        let kicked = EngineError::new(ErrorKind::PermissionDenied, "banned: bye");
        assert_eq!(
            BridgeError::from(kicked),
            BridgeError::PermissionDenied {
                detail: "banned: bye".into()
            }
        );
    }

    #[test]
    fn giving_up_on_a_server_that_closes_after_login_stays_unreachable() {
        let detail = "gave up: the server closed the connection right after login, 3 times";
        let error = BridgeError::from(EngineError::new(ErrorKind::ServerUnreachable, detail));
        assert_eq!(
            error,
            BridgeError::ServerUnreachable {
                detail: detail.into()
            }
        );
    }

    #[test]
    fn a_known_certificate_is_a_duplicate_identity() {
        let error = BridgeError::from(EngineError::new(ErrorKind::DuplicateIdentity, "known"));
        assert_eq!(
            error,
            BridgeError::DuplicateIdentity {
                detail: "known".into()
            }
        );
    }

    #[test]
    fn other_kinds_become_internal() {
        let error = BridgeError::from(EngineError::new(ErrorKind::ShareFull, "full"));
        assert_eq!(error, BridgeError::internal("full"));
    }
}
