// SPDX-License-Identifier: AGPL-3.0-only

//! Records and enums of the bridge interface. They are the bridge's own types; the engine's
//! types are mapped onto them in `convert`.

use crate::error::BridgeError;

/// Start-up settings of [`MumcordEngine`](crate::MumcordEngine).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EngineConfig {
    /// App-private folder for the engine's database and files. Created when missing.
    pub data_dir: String,
}

/// The server to connect to and who to connect as.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConnectRequest {
    /// Host name or IP address.
    pub address: String,
    pub port: u16,
    pub username: String,
    /// Server password for this connection only; it is not saved.
    pub password: Option<String>,
}

/// Where the connection stands.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ConnectionState {
    /// `error` says why the last connection ended, unless it ended on request.
    Disconnected {
        error: Option<BridgeError>,
    },
    Connecting,
    Connected,
    Reconnecting {
        attempt: u32,
        max_attempts: u32,
        next_retry_secs: u32,
    },
}

/// The question whether to trust a server certificate. Connecting waits for the answer
/// ([`MumcordEngine::answer_certificate_prompt`](crate::MumcordEngine::answer_certificate_prompt)).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CertificatePrompt {
    /// SHA-256 fingerprint of the certificate the server presented, as shown to the user.
    pub fingerprint: String,
    /// Fingerprint trusted so far. Set when the certificate changed (strong warning), `None` for
    /// a server seen for the first time.
    pub previous_fingerprint: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Channel {
    pub id: u32,
    /// `None` for the root channel.
    pub parent_id: Option<u32>,
    /// Chosen by the server's users: untrusted text.
    pub name: String,
    /// Sort position among the channels of the same parent, then by name.
    pub position: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Person {
    /// Id of this person's connection; it changes with every connect.
    pub session: u32,
    pub channel_id: u32,
    /// Chosen by the person: untrusted text.
    pub name: String,
}

/// Channels and people of the connected server.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ServerState {
    /// The [`Person::session`] of the user themselves.
    pub self_session: u32,
    pub channels: Vec<Channel>,
    pub people: Vec<Person>,
}

/// The full current state.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BridgeSnapshot {
    pub connection: ConnectionState,
    /// The certificate question that is waiting for an answer, if any.
    pub certificate_prompt: Option<CertificatePrompt>,
    /// `None` until the server has sent its channels and people.
    pub server: Option<ServerState>,
}
