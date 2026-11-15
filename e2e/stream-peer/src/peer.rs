// SPDX-License-Identifier: AGPL-3.0-only

//! What the sharer and the viewer share: an engine of their own on the host, connected to the
//! test server under a name, with direct connections allowed.

use std::collections::HashMap;
use std::error::Error;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mumcord_engine::p2p::P2pConfig;
use mumcord_engine::store::Consent;
use mumcord_engine::{
    Engine, EngineEvent, EventReceiver, PasswordChange, SecretStore, ServerFields, ServerId,
};
use mumcord_store::SecretBackend;

/// How long connecting may take.
const CONNECT_WAIT: Duration = Duration::from_secs(60);

/// Secrets live in memory only: every run is a new person.
#[derive(Default)]
struct Memory(Mutex<HashMap<String, Vec<u8>>>);

impl SecretBackend for Memory {
    fn set(&self, account: &str, secret: &[u8]) -> mumcord_store::Result<()> {
        lock(&self.0).insert(account.to_owned(), secret.to_vec());
        Ok(())
    }

    fn get(&self, account: &str) -> mumcord_store::Result<Option<Vec<u8>>> {
        Ok(lock(&self.0).get(account).cloned())
    }

    fn delete(&self, account: &str) -> mumcord_store::Result<()> {
        lock(&self.0).remove(account);
        Ok(())
    }
}

pub fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// A connected engine of this program.
pub struct Peer {
    pub engine: Engine,
    pub events: EventReceiver,
    pub server: ServerId,
    /// The own session on the server.
    pub own: u32,
    dir: PathBuf,
}

impl Drop for Peer {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// Starts an engine, connects it to `address`:`port` as `name` and waits until it is on the
/// server. Direct connections are allowed, port mapping on the router is off, and like every
/// engine it asks no outside server for its addresses.
pub async fn connect(name: &str, address: String, port: u16) -> Result<Peer, Box<dyn Error>> {
    let dir = std::env::temp_dir().join(format!("mumcord-stream-peer-{}", std::process::id()));
    std::fs::create_dir_all(&dir)?;
    let (engine, mut events) = Engine::new(
        &dir,
        SecretStore::with_backend(Arc::new(Memory::default())),
        tokio::runtime::Handle::current(),
    )?;
    engine.identity_create(name.into(), None).await?;
    let mut settings = engine.settings()?;
    settings.allow_direct_connections = Consent::Yes;
    engine.update_settings(settings).await?;
    engine.p2p_set_config(P2pConfig {
        connect_timeout: Duration::from_secs(10),
        port_mapping: false,
        allow_loopback: false,
    });
    let password = std::env::var("SERVER_PASSWORD")
        .ok()
        .filter(|p| !p.is_empty());
    let server = engine
        .server_add(ServerFields {
            label: "e2e".into(),
            address,
            port,
            username: name.into(),
            password: password.map_or(PasswordChange::Keep, PasswordChange::Set),
        })
        .await?;
    engine.connect(server.id, None)?;
    let own = tokio::time::timeout(CONNECT_WAIT, async {
        loop {
            match events.recv().await {
                Some(EngineEvent::CertTrustRequired {
                    server_id,
                    fingerprint,
                    ..
                }) => {
                    engine.cert_trust_decide(server_id, &fingerprint, true)?;
                }
                Some(EngineEvent::ServerSynced(snapshot)) => {
                    return Ok::<_, Box<dyn Error>>(snapshot.self_session);
                }
                Some(_) => {}
                None => return Err("the engine stopped".into()),
            }
        }
    })
    .await
    .map_err(|_| "not connected in time")??;
    println!("CONNECTED");
    Ok(Peer {
        engine,
        events,
        server: server.id,
        own,
        dir,
    })
}
