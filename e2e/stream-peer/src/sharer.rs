// SPDX-License-Identifier: AGPL-3.0-only

use std::collections::HashMap;
use std::error::Error;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mumcord_engine::p2p::P2pConfig;
use mumcord_engine::store::{Consent, ScreenCodec};
use mumcord_engine::{Engine, EngineEvent, PasswordChange, SecretStore, ServerFields};
use mumcord_store::SecretBackend;
use tokio::io::{AsyncBufReadExt, BufReader};

/// The name on the server; the phone shows "e2e-sharer's screen".
const NAME: &str = "e2e-sharer";
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

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub fn main() -> Result<(), Box<dyn Error>> {
    let mut arguments = std::env::args().skip(1);
    let address = arguments.next().ok_or("the server address is missing")?;
    let port: u16 = arguments
        .next()
        .ok_or("the server port is missing")?
        .parse()?;
    // SAFETY: set before the runtime and the engine start any thread. The share sends no sound
    // and plays none, so the host's sound devices stay untouched.
    unsafe { std::env::set_var("MUMCORD_AUDIO_BACKEND", "null") };
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    runtime.block_on(share(address, port))
}

async fn share(address: String, port: u16) -> Result<(), Box<dyn Error>> {
    let dir = std::env::temp_dir().join(format!("mumcord-stream-peer-{}", std::process::id()));
    std::fs::create_dir_all(&dir)?;
    let (engine, mut events) = Engine::new(
        &dir,
        SecretStore::with_backend(Arc::new(Memory::default())),
        tokio::runtime::Handle::current(),
    )?;
    engine.identity_create(NAME.into(), None).await?;
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
            username: NAME.into(),
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
    engine
        .screen_share_start(server.id, false, None, Some(ScreenCodec::Vp9))
        .await?;
    println!("SHARING");

    let mut viewers = 0usize;
    let mut input = BufReader::new(tokio::io::stdin()).lines();
    loop {
        tokio::select! {
            event = events.recv() => match event {
                Some(EngineEvent::ScreenSharesChanged(shares)) => {
                    let now = shares.iter().find(|s| s.session == own).map_or(0, |s| s.viewers.len());
                    if now != viewers {
                        viewers = now;
                        println!("VIEWERS {viewers}");
                    }
                }
                Some(_) => {}
                None => return Err("the engine stopped".into()),
            },
            line = input.next_line() => match line? {
                Some(line) => {
                    if let Some(word) = line.strip_prefix("mark ") {
                        println!("MARK {word} viewers={viewers}");
                    }
                }
                None => break,
            },
        }
    }
    let _ = std::fs::remove_dir_all(&dir);
    Ok(())
}
