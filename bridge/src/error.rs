// SPDX-License-Identifier: AGPL-3.0-only

//! The error of every bridge command.

/// Why a command or a connection failed. The variant is the kind the app words for the user;
/// `detail` is technical detail or the server's own text and never contains secrets.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum BridgeError {
    #[error("not connected: {detail}")]
    NotConnected { detail: String },
    #[error("server unreachable: {detail}")]
    ServerUnreachable { detail: String },
    #[error("wrong password: {detail}")]
    WrongPassword { detail: String },
    #[error("name in use: {detail}")]
    NameInUse { detail: String },
    /// The user declined an unknown server certificate.
    #[error("certificate rejected: {detail}")]
    CertificateRejected { detail: String },
    /// The user declined a certificate that differs from the trusted one.
    #[error("certificate changed: {detail}")]
    CertificateChanged { detail: String },
    #[error("server too old: {detail}")]
    ServerTooOld { detail: String },
    #[error("invalid input: {detail}")]
    InvalidInput { detail: String },
    #[error("secure storage unavailable: {detail}")]
    SecureStorageUnavailable { detail: String },
    #[error("storage error: {detail}")]
    Io { detail: String },
    #[error("internal error: {detail}")]
    Internal { detail: String },
}

impl BridgeError {
    pub(crate) fn invalid(detail: &str) -> Self {
        Self::InvalidInput {
            detail: detail.to_owned(),
        }
    }

    pub(crate) fn internal(detail: impl Into<String>) -> Self {
        Self::Internal {
            detail: detail.into(),
        }
    }
}
