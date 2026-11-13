// SPDX-License-Identifier: AGPL-3.0-only

use mumcord_engine::{EngineError, ErrorKind};

use crate::error::BridgeError;

impl From<EngineError> for BridgeError {
    fn from(error: EngineError) -> Self {
        let message = error.message;
        match error.kind {
            ErrorKind::NotConnected => Self::NotConnected { message },
            ErrorKind::ServerUnreachable => Self::ServerUnreachable { message },
            ErrorKind::WrongPassword => Self::WrongPassword { message },
            ErrorKind::NameInUse => Self::NameInUse { message },
            ErrorKind::CertificateRejected => Self::CertificateRejected { message },
            ErrorKind::CertificateChanged => Self::CertificateChanged { message },
            ErrorKind::ServerTooOld => Self::ServerTooOld { message },
            ErrorKind::InvalidInput => Self::InvalidInput { message },
            ErrorKind::SecureStorageUnavailable => Self::SecureStorageUnavailable { message },
            ErrorKind::Io => Self::Io { message },
            // Kinds of features the bridge does not offer yet keep their message.
            _ => Self::Internal { message },
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
            (
                ErrorKind::SecureStorageUnavailable,
                "SecureStorageUnavailable",
            ),
        ];
        for (kind, name) in cases {
            let error = BridgeError::from(EngineError::new(kind, "detail"));
            let debug = format!("{error:?}");
            assert!(debug.starts_with(name), "{debug}");
            assert!(debug.contains("detail"), "{debug}");
        }
    }

    #[test]
    fn other_kinds_become_internal() {
        let error = BridgeError::from(EngineError::new(ErrorKind::ShareFull, "full"));
        assert_eq!(error, BridgeError::internal("full"));
    }
}
