// SPDX-License-Identifier: AGPL-3.0-only

//! Commands in: the one object the apps hold.

use std::future::Future;
use std::path::Path;
use std::sync::Arc;

use mumcord_engine::{Engine, PasswordChange, SecretStore, ServerFields, ServerId};
use tokio::runtime::{Handle, Runtime};
use tokio::sync::oneshot;

use crate::convert;
use crate::error::BridgeError;
use crate::events::{self, Connection, EngineListener, SharedConnection, lock};
use crate::storage::{SecureStorage, StorageBackend};
use crate::types::{BridgeSnapshot, ConnectRequest, EngineConfig};

/// The Mumcord engine of the app. Create one per process and keep it for the app's lifetime.
#[derive(uniffi::Object)]
pub struct MumcordEngine {
    engine: Engine,
    connection: SharedConnection,
    /// Serialises `connect`, so two calls cannot both find the connection free.
    connect_gate: Arc<tokio::sync::Mutex<()>>,
    handle: Handle,
    /// Taken on drop.
    runtime: Option<Runtime>,
    /// Dropping it ends the event thread.
    _stop_events: oneshot::Sender<()>,
}

#[uniffi::export]
impl MumcordEngine {
    /// Opens the engine's database in `config.data_dir` and starts delivering events to
    /// `listener`. Reads the database and the secure storage, so call it off the main thread.
    #[uniffi::constructor]
    pub fn new(
        config: EngineConfig,
        storage: Arc<dyn SecureStorage>,
        listener: Arc<dyn EngineListener>,
    ) -> Result<Arc<Self>, BridgeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("mumcord-bridge")
            .build()
            .map_err(|e| BridgeError::internal(format!("starting the runtime failed: {e}")))?;
        let handle = runtime.handle().clone();
        let secrets = SecretStore::with_backend(Arc::new(StorageBackend(storage)));
        let (engine, receiver) = {
            // The engine starts its background tasks while it is created.
            let _runtime = runtime.enter();
            Engine::new(Path::new(&config.data_dir), secrets, handle.clone())?
        };
        let connection = SharedConnection::default();
        let (stop_events, stop) = oneshot::channel();
        events::spawn_pump(handle.clone(), receiver, connection.clone(), listener, stop)
            .map_err(|e| BridgeError::internal(format!("starting the event thread failed: {e}")))?;
        Ok(Arc::new(Self {
            engine,
            connection,
            connect_gate: Arc::default(),
            handle,
            runtime: Some(runtime),
            _stop_events: stop_events,
        }))
    }

    /// Starts connecting; progress and the result arrive as events
    /// ([`BridgeEvent::ConnectionChanged`](crate::BridgeEvent::ConnectionChanged), a certificate
    /// prompt, then [`BridgeEvent::ServerSynced`](crate::BridgeEvent::ServerSynced)). Only one
    /// connection at a time: fails with `InvalidInput` while another one is connected,
    /// connecting or reconnecting.
    pub async fn connect(&self, request: ConnectRequest) -> Result<(), BridgeError> {
        let (engine, connection, gate) = (
            self.engine.clone(),
            self.connection.clone(),
            self.connect_gate.clone(),
        );
        self.run(async move {
            let _gate = gate.lock().await;
            connect(&engine, &connection, request).await
        })
        .await
    }

    /// Answers the pending certificate prompt with that `fingerprint`: `trust = true` saves the
    /// certificate as trusted and goes on connecting, `false` ends the attempt.
    pub async fn answer_certificate_prompt(
        &self,
        fingerprint: String,
        trust: bool,
    ) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = lock(&connection)
                .server
                .ok_or_else(|| BridgeError::invalid("no certificate question is pending"))?;
            engine.cert_trust_decide(server, &fingerprint, trust)?;
            let mut connection = lock(&connection);
            if connection
                .certificate_prompt
                .as_ref()
                .is_some_and(|prompt| prompt.fingerprint == fingerprint)
            {
                connection.certificate_prompt = None;
            }
            Ok(())
        })
        .await
    }

    /// Ends the connection, also while it is connecting, reconnecting or waiting for a
    /// certificate answer. The end is reported as `ConnectionChanged` with `Disconnected`; a new
    /// `connect` is possible after that. Does nothing when not connected.
    pub async fn disconnect(&self) -> Result<(), BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = lock(&connection).server;
            if let Some(server) = server {
                engine.disconnect(server);
            }
            Ok(())
        })
        .await
    }

    /// The full current state.
    pub fn snapshot(&self) -> BridgeSnapshot {
        let (server, connection, certificate_prompt) = {
            let connection = lock(&self.connection);
            (
                connection.server,
                connection.state.clone(),
                connection.certificate_prompt.clone(),
            )
        };
        BridgeSnapshot {
            connection,
            certificate_prompt,
            server: server
                .and_then(|server| self.engine.snapshot_of(server))
                .map(convert::server_state),
        }
    }
}

impl MumcordEngine {
    /// Runs a command on the bridge runtime, so the caller's thread is never blocked and the
    /// command finishes even if the caller stops waiting.
    async fn run<T: Send + 'static>(
        &self,
        command: impl Future<Output = Result<T, BridgeError>> + Send + 'static,
    ) -> Result<T, BridgeError> {
        self.handle
            .spawn(command)
            .await
            .map_err(|e| BridgeError::internal(format!("the command was aborted: {e}")))?
    }
}

impl Drop for MumcordEngine {
    fn drop(&mut self) {
        if let Some(server) = lock(&self.connection).server {
            self.engine.disconnect(server);
        }
        if let Some(runtime) = self.runtime.take() {
            // The object can be released from any thread, also from inside the runtime, where a
            // blocking shutdown would panic.
            runtime.shutdown_background();
        }
    }
}

async fn connect(
    engine: &Engine,
    connection: &SharedConnection,
    request: ConnectRequest,
) -> Result<(), BridgeError> {
    let address = request.address.trim();
    let username = request.username.trim();
    if address.is_empty() {
        return Err(BridgeError::invalid("address is empty"));
    }
    if username.is_empty() {
        return Err(BridgeError::invalid("user name is empty"));
    }
    if lock(connection).server.is_some() {
        return Err(BridgeError::invalid("already connected to a server"));
    }

    // First slice only: an identity is created silently from the user name. The identity flow
    // of phase 6 (create or bring an identity during setup) replaces this.
    if engine.identity().await?.is_none() {
        engine.identity_create(username.to_owned(), None).await?;
    }
    let server = saved_server(engine, address, request.port, username).await?;

    *lock(connection) = Connection {
        server: Some(server),
        ..Connection::default()
    };
    // The password is used for this connection only; nothing is saved.
    let started = engine.connect_with_password(server, None, request.password);
    if started.is_err() {
        lock(connection).server = None;
    }
    Ok(started?)
}

/// The saved server for this address and user name; added when there is none. The engine
/// connects saved servers only (it keeps the trusted certificate with them).
async fn saved_server(
    engine: &Engine,
    address: &str,
    port: u16,
    username: &str,
) -> Result<ServerId, BridgeError> {
    let known = engine
        .servers()
        .await?
        .into_iter()
        .find(|s| s.address == address && s.port == port && s.username == username);
    if let Some(server) = known {
        return Ok(server.id);
    }
    let added = engine
        .server_add(ServerFields {
            label: address.to_owned(),
            address: address.to_owned(),
            port,
            username: username.to_owned(),
            password: PasswordChange::Keep,
        })
        .await?;
    Ok(added.id)
}
