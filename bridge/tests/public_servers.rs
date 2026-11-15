// SPDX-License-Identifier: AGPL-3.0-only

//! The public server browser through the bridge, against a list served by this test on this
//! machine. The real public list is never contacted: the engine reads its address from
//! `MUMCORD_PUBLIC_SERVERS_URL`, which is set before the engine exists and makes it refuse
//! every address that is not this machine.
//!
//! One test in a file of its own, because the address is set for the whole process.
//! With `MUMCORD_LIVE=1` and the local test server running (`scripts/testenv.sh up`) the ping
//! of a listed server is checked against it.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use mumcord_mobile::{
    BridgeError, BridgeEvent, EngineConfig, EngineListener, MumcordEngine, PublicServerQuery,
    SecureStorage, SecureStorageError,
};

const LISTED: usize = 250;

#[derive(Default)]
struct MemoryStorage(Mutex<HashMap<String, Vec<u8>>>);

impl SecureStorage for MemoryStorage {
    fn set(&self, account: String, secret: Vec<u8>) -> Result<(), SecureStorageError> {
        self.0.lock().expect("storage").insert(account, secret);
        Ok(())
    }

    fn get(&self, account: String) -> Result<Option<Vec<u8>>, SecureStorageError> {
        Ok(self.0.lock().expect("storage").get(&account).cloned())
    }

    fn delete(&self, account: String) -> Result<(), SecureStorageError> {
        self.0.lock().expect("storage").remove(&account);
        Ok(())
    }
}

struct Ignore;

impl EngineListener for Ignore {
    fn on_event(&self, _event: BridgeEvent) {}
}

/// The list this test serves: the local test server first, then servers that do not exist.
fn list(port: u16) -> String {
    let mut xml = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<servers>\n");
    xml.push_str(&format!(
        "<server name=\"Local &amp; Test\" ca=\"0\" continent_code=\"EU\" country=\"Germany\" \
         country_code=\"de\" ip=\"127.0.0.1\" port=\"{port}\" region=\"\" url=\"\" />\n"
    ));
    for n in 1..LISTED {
        let (country, code) = if n % 2 == 0 {
            ("Sweden", "se")
        } else {
            ("Germany", "de")
        };
        xml.push_str(&format!(
            "<server name=\"Server {n}\" ca=\"0\" continent_code=\"EU\" country=\"{country}\" \
             country_code=\"{code}\" ip=\"s{n}.example\" port=\"64738\" region=\"\" url=\"\" />\n"
        ));
    }
    xml.push_str("</servers>\n");
    xml
}

/// What the list server does: how often it was asked, and whether it answers with an error.
#[derive(Default)]
struct Fixture {
    requests: AtomicUsize,
    broken: AtomicBool,
}

/// Serves `body` on this machine and returns the address of the list.
fn serve(body: String, fixture: Arc<Fixture>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("list server");
    let url = format!("http://{}/v1/list", listener.local_addr().expect("address"));
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue };
            let mut request = Vec::new();
            let mut buffer = [0; 1024];
            while !request.windows(4).any(|window| window == b"\r\n\r\n") {
                match stream.read(&mut buffer) {
                    Ok(read) if read > 0 => request.extend_from_slice(&buffer[..read]),
                    _ => break,
                }
            }
            fixture.requests.fetch_add(1, Ordering::SeqCst);
            let response = if fixture.broken.load(Ordering::SeqCst) {
                "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .to_owned()
            } else {
                format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: {}\r\n\
                     Connection: close\r\n\r\n{body}",
                    body.len()
                )
            };
            let _ = stream.write_all(response.as_bytes());
        }
    });
    url
}

fn query(search: &str, country: Option<&str>, offset: u32, limit: u32) -> PublicServerQuery {
    PublicServerQuery {
        search: search.to_owned(),
        country_code: country.map(str::to_owned),
        offset,
        limit,
        refresh: false,
    }
}

#[test]
fn the_public_list_is_read_in_pages_from_a_local_fixture() {
    let live = std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1");
    let test_server: u16 = std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(64738);
    let fixture = Arc::new(Fixture::default());
    let url = serve(list(test_server), fixture.clone());
    // SAFETY: nothing else runs in this process yet; the test has not started a thread that
    // reads the environment, and the engine is created only afterwards.
    unsafe { std::env::set_var("MUMCORD_PUBLIC_SERVERS_URL", &url) };

    let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join("bridge-public-servers");
    let _ = std::fs::remove_dir_all(&dir);
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: dir.to_string_lossy().into_owned(),
            audio: None,
        },
        Arc::new(MemoryStorage::default()),
        Arc::new(Ignore),
    )
    .expect("engine");
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("runtime");
    runtime.block_on(async {
        // Nothing is fetched before the browser is opened.
        assert_eq!(fixture.requests.load(Ordering::SeqCst), 0);

        // The first page downloads the list; a page never holds more than 100 servers.
        let first = engine
            .public_servers(query("", None, 0, 1000))
            .await
            .expect("first page");
        assert_eq!(fixture.requests.load(Ordering::SeqCst), 1);
        assert_eq!(first.total as usize, LISTED);
        assert_eq!(first.servers.len(), 100);
        let local = &first.servers[0];
        assert_eq!(local.name, "Local & Test");
        assert_eq!(
            (local.host.as_str(), local.port),
            ("127.0.0.1", test_server)
        );
        assert_eq!(
            (local.country.as_str(), local.country_code.as_str()),
            ("Germany", "DE")
        );

        // More pages, a search and a country come from the same download.
        let last = engine
            .public_servers(query("", None, 200, 100))
            .await
            .expect("last page");
        assert_eq!(last.servers.len(), LISTED - 200);
        assert_eq!(last.servers[0].name, "Server 200");
        let found = engine
            .public_servers(query("server 12", None, 0, 100))
            .await
            .expect("search");
        let names: Vec<&str> = found.servers.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names[0], "Server 12");
        assert_eq!(found.total, 11, "12 and 120 to 129");
        let sweden = engine
            .public_servers(query("", Some("se"), 0, 5))
            .await
            .expect("country");
        assert_eq!(sweden.total as usize, (LISTED - 1) / 2);
        assert!(sweden.servers.iter().all(|s| s.country_code == "SE"));
        assert_eq!(fixture.requests.load(Ordering::SeqCst), 1);

        // A refresh that fails says so and leaves the list as it was.
        fixture.broken.store(true, Ordering::SeqCst);
        let refresh = PublicServerQuery {
            refresh: true,
            ..query("", None, 0, 10)
        };
        let failed = engine.public_servers(refresh.clone()).await;
        assert!(
            matches!(failed, Err(BridgeError::ServerUnreachable { .. })),
            "a failed download is `ServerUnreachable`"
        );
        assert_eq!(fixture.requests.load(Ordering::SeqCst), 2);
        let kept = engine
            .public_servers(query("", None, 0, 10))
            .await
            .expect("the list of before");
        assert_eq!(kept.total as usize, LISTED);
        assert_eq!(fixture.requests.load(Ordering::SeqCst), 2);

        // A refresh that works downloads again.
        fixture.broken.store(false, Ordering::SeqCst);
        let fresh = engine.public_servers(refresh).await.expect("refresh");
        assert_eq!(fresh.total as usize, LISTED);
        assert_eq!(fixture.requests.load(Ordering::SeqCst), 3);

        // A listed server that does not exist does not answer the ping.
        let silent = engine
            .ping_public_server("s1.example".into(), 64738)
            .await
            .expect("ping");
        assert_eq!(silent, None);
        if live {
            let ping = engine
                .ping_public_server(local.host.clone(), local.port)
                .await
                .expect("ping")
                .expect("the local test server answers");
            assert!(ping.max_people > 0);
        }
    });
    drop(engine);
    let _ = std::fs::remove_dir_all(&dir);
}
