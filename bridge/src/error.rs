// SPDX-License-Identifier: AGPL-3.0-only

//! The error of every bridge command.

/// Why a command or a connection failed. The variant is the kind the app words for the user;
/// `message` is technical detail or the server's own text and never contains secrets.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum BridgeError {
    #[error("not connected: {message}")]
    NotConnected { message: String },
    #[error("server unreachable: {message}")]
    ServerUnreachable { message: String },
    #[error("wrong password: {message}")]
    WrongPassword { message: String },
    #[error("name in use: {message}")]
    NameInUse { message: String },
    /// The user declined an unknown server certificate.
    #[error("certificate rejected: {message}")]
    CertificateRejected { message: String },
    /// The user declined a certificate that differs from the trusted one.
    #[error("certificate changed: {message}")]
    CertificateChanged { message: String },
    #[error("server too old: {message}")]
    ServerTooOld { message: String },
    #[error("invalid input: {message}")]
    InvalidInput { message: String },
    #[error("secure storage unavailable: {message}")]
    SecureStorageUnavailable { message: String },
    #[error("storage error: {message}")]
    Io { message: String },
    #[error("internal error: {message}")]
    Internal { message: String },
}

impl BridgeError {
    pub(crate) fn invalid(message: &str) -> Self {
        Self::InvalidInput {
            message: message.to_owned(),
        }
    }

    pub(crate) fn internal(message: impl Into<String>) -> Self {
        Self::Internal {
            message: message.into(),
        }
    }
}
