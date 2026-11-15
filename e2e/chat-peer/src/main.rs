// SPDX-License-Identifier: AGPL-3.0-only

//! The second person of the end-to-end chat test (`e2e/run.sh`).
//!
//! Connects to the local test server as a normal client through the bridge, as the desktop app
//! would through the engine, and enters the channel "Lobby". Once the phone (`--phone <name>`)
//! is there too, it sends what a person sends in a chat: a text, a link, a picture, the address
//! of a GIF and a mention of the phone's user. It prints `SENT` when all of it is out.
//!
//! Every message it receives afterwards is reported as one line,
//! `RECEIVED from=<name> text=<text> links=<addresses> images=<count>`, which is how the test
//! run checks what the phone sent. It runs until it is stopped. The server's port comes from
//! `MUMCORD_PORT_BASE`.
//!
//! With `--private` it writes nothing into the channel. It prints `READY` once the phone is
//! there and then sends the phone one private message, "Private note <n>", for every number
//! `<n>` it reads as a line from its standard input, and reports `PM SENT <n>`, or
//! `PM FAILED <n>` when the phone is no longer on the server.
//!
//! With `--visitor` it is the person the phone's activity log watches: it starts without a
//! connection, prints `READY`, and for every line it reads does one step as the user
//! `e2e-visitor`: `join` connects to the server, `move` enters the channel "Raid" and `leave`
//! disconnects. It reports each as `VISITOR JOINED`, `VISITOR MOVED` and `VISITOR LEFT`.

use std::collections::HashMap;
use std::process::ExitCode;
use std::sync::mpsc::{Receiver, channel};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use mumcord_mobile::{
    BridgeEvent, ChatId, ChatMessage, ConnectRequest, ContentBlock, EngineConfig, EngineListener,
    MumcordEngine, SecureStorage, SecureStorageError,
};

const USER_NAME: &str = "e2e-peer";
const VISITOR_NAME: &str = "e2e-visitor";
const CHANNEL: &str = "Lobby";
/// Where the visitor moves to.
const MOVE_TO: &str = "Raid";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// How long the phone may take to connect and enter the channel.
const PHONE_TIMEOUT: Duration = Duration::from_secs(240);
const POLL: Duration = Duration::from_millis(250);
/// A 16 x 16 pixel PNG.
const PICTURE: &[u8] = include_bytes!("picture.png");

/// The identity of this run only; nothing of it is kept.
#[derive(Default)]
struct MemoryStorage(Mutex<HashMap<String, Vec<u8>>>);

impl SecureStorage for MemoryStorage {
    fn set(&self, account: String, secret: Vec<u8>) -> Result<(), SecureStorageError> {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(account, secret);
        Ok(())
    }

    fn get(&self, account: String) -> Result<Option<Vec<u8>>, SecureStorageError> {
        Ok(self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .get(&account)
            .cloned())
    }

    fn delete(&self, account: String) -> Result<(), SecureStorageError> {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .remove(&account);
        Ok(())
    }
}

/// Passes the engine's events on to the main thread.
struct Events(Mutex<std::sync::mpsc::Sender<BridgeEvent>>);

impl EngineListener for Events {
    fn on_event(&self, event: BridgeEvent) {
        // The receiver is gone only while the program ends.
        let _ = self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .send(event);
    }
}

fn wait_for<T>(
    events: &Receiver<BridgeEvent>,
    what: &str,
    pick: impl Fn(&BridgeEvent) -> Option<T>,
) -> Result<T, String> {
    let deadline = Instant::now() + CONNECT_TIMEOUT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        match events.recv_timeout(left) {
            Ok(event) => {
                if let Some(found) = pick(&event) {
                    return Ok(found);
                }
            }
            Err(_) => return Err(format!("timed out waiting for {what}")),
        }
    }
}

/// What the second person does.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    /// Writes into the channel and reports what it receives.
    Channel,
    /// Writes to the phone in private when asked.
    Private,
    /// Joins, moves and leaves when asked.
    Visitor,
}

/// The phone's user name and the mode.
fn arguments() -> Result<(String, Mode), String> {
    let mut arguments = std::env::args().skip(1);
    match (
        arguments.next().as_deref(),
        arguments.next(),
        arguments.next().as_deref(),
    ) {
        (Some("--phone"), Some(name), None) => Ok((name, Mode::Channel)),
        (Some("--phone"), Some(name), Some("--private")) => Ok((name, Mode::Private)),
        (Some("--phone"), Some(name), Some("--visitor")) => Ok((name, Mode::Visitor)),
        _ => {
            Err("usage: chat-peer --phone <user name of the phone> [--private | --visitor]".into())
        }
    }
}

/// Connects as `name`, trusts the test server's certificate and returns the server's channels
/// by name.
fn connect(
    engine: &MumcordEngine,
    runtime: &tokio::runtime::Runtime,
    events: &Receiver<BridgeEvent>,
    port: u16,
    name: &str,
) -> Result<HashMap<String, u32>, String> {
    runtime
        .block_on(engine.connect(ConnectRequest {
            address: "127.0.0.1".into(),
            port,
            username: name.into(),
            password: None,
        }))
        .map_err(|error| format!("connecting failed: {error}"))?;
    let fingerprint = wait_for(events, "the certificate question", |event| match event {
        BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt.fingerprint.clone()),
        _ => None,
    })?;
    runtime
        .block_on(engine.answer_certificate_prompt(fingerprint, true))
        .map_err(|error| format!("trusting the certificate failed: {error}"))?;
    wait_for(events, "the server's channels", |event| match event {
        BridgeEvent::ServerSynced { server } => Some(
            server
                .channels
                .iter()
                .map(|channel| (channel.name.clone(), channel.id))
                .collect(),
        ),
        _ => None,
    })
}

/// Does one step of the visit per line read from the standard input, until the input ends.
fn visit(
    engine: &MumcordEngine,
    runtime: &tokio::runtime::Runtime,
    events: &Receiver<BridgeEvent>,
    port: u16,
) -> Result<(), String> {
    println!("READY");
    let mut channels = HashMap::new();
    for line in std::io::stdin().lines() {
        let line = line.map_err(|error| format!("reading the next step failed: {error}"))?;
        match line.trim() {
            "join" => {
                channels = connect(engine, runtime, events, port, VISITOR_NAME)?;
                println!("VISITOR JOINED");
            }
            "move" => {
                let channel = *channels
                    .get(MOVE_TO)
                    .ok_or(format!("the server has no channel {MOVE_TO}"))?;
                runtime
                    .block_on(engine.join_channel(channel, None))
                    .map_err(|error| format!("moving to {MOVE_TO} failed: {error}"))?;
                println!("VISITOR MOVED");
            }
            "leave" => {
                runtime
                    .block_on(engine.disconnect())
                    .map_err(|error| format!("leaving failed: {error}"))?;
                println!("VISITOR LEFT");
            }
            _ => {}
        }
    }
    Ok(())
}

/// Sends the phone one private message per number read from the standard input, until the
/// input ends.
fn write_in_private(
    engine: &MumcordEngine,
    runtime: &tokio::runtime::Runtime,
    phone: &str,
) -> Result<(), String> {
    println!("READY");
    for line in std::io::stdin().lines() {
        let line = line.map_err(|error| format!("reading the next number failed: {error}"))?;
        let number = line.trim();
        if number.is_empty() || !number.chars().all(|digit| digit.is_ascii_digit()) {
            continue;
        }
        let session = engine.snapshot().server.and_then(|server| {
            server
                .people
                .iter()
                .find(|person| person.name == phone)
                .map(|person| person.session)
        });
        let sent = session
            .and_then(|session| engine.chat_with(session).ok())
            .is_some_and(|chat| {
                runtime
                    .block_on(engine.send_message(chat, format!("Private note {number}"), None))
                    .is_ok()
            });
        println!("PM {} {number}", if sent { "SENT" } else { "FAILED" });
    }
    Ok(())
}

/// One line about a received message: its text, the addresses of its links and its pictures.
fn report(message: &ChatMessage) -> String {
    let mut text = String::new();
    let mut links = Vec::new();
    let mut images = 0;
    for block in &message.content.blocks {
        match block {
            ContentBlock::Paragraph { spans } | ContentBlock::Quote { spans } => {
                for span in spans {
                    text.push_str(&span.text);
                    if let Some(link) = &span.link {
                        links.push(link.url.clone());
                    }
                }
            }
            ContentBlock::Code { text: code } => text.push_str(code),
            ContentBlock::Image { .. } => images += 1,
        }
    }
    format!(
        "RECEIVED from={} text={text:?} links={links:?} images={images}",
        message.sender_name
    )
}

fn run() -> Result<(), String> {
    let (phone, mode) = arguments()?;
    let port = std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .ok_or("MUMCORD_PORT_BASE is not set to the test server's port")?;
    let data_dir = std::env::temp_dir().join(format!("mumcord-chat-peer-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&data_dir);

    let (sender, events) = channel();
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: data_dir.to_string_lossy().into_owned(),
            audio: None,
        },
        Arc::new(MemoryStorage::default()),
        Arc::new(Events(Mutex::new(sender))),
    )
    .map_err(|error| format!("creating the engine failed: {error}"))?;

    let runtime = tokio::runtime::Builder::new_current_thread()
        .build()
        .map_err(|error| format!("starting the runtime failed: {error}"))?;
    if mode == Mode::Visitor {
        visit(&engine, &runtime, &events, port)?;
        // The input ended; stay until stopped, as in the other modes.
        loop {
            std::thread::sleep(Duration::from_secs(60));
        }
    }
    let channel = *connect(&engine, &runtime, &events, port, USER_NAME)?
        .get(CHANNEL)
        .ok_or(format!("the server has no channel {CHANNEL}"))?;
    runtime
        .block_on(engine.join_channel(channel, None))
        .map_err(|error| format!("joining {CHANNEL} failed: {error}"))?;
    println!("CONNECTED as {USER_NAME} in {CHANNEL}, waiting for {phone}");

    // A Mumble server keeps no chat history: a message only reaches who is in the channel.
    let deadline = Instant::now() + PHONE_TIMEOUT;
    loop {
        let here = engine.snapshot().server.is_some_and(|server| {
            server
                .people
                .iter()
                .any(|person| person.name == phone && person.channel_id == channel)
        });
        if here {
            break;
        }
        if Instant::now() > deadline {
            return Err(format!("{phone} did not enter {CHANNEL}"));
        }
        std::thread::sleep(POLL);
    }

    if mode == Mode::Private {
        write_in_private(&engine, &runtime, &phone)?;
        // The input ended; stay on the server until stopped, as in the other mode.
        loop {
            std::thread::sleep(Duration::from_secs(60));
        }
    }

    let chat = ChatId::Channel {
        channel_id: channel,
    };
    let texts = [
        "Hello from the desk".to_owned(),
        "Rules are here: https://nordlicht.example/rules".to_owned(),
        "https://gifs.example/party.gif".to_owned(),
        format!("@{phone} can you host the first round?"),
    ];
    for text in texts {
        runtime
            .block_on(engine.send_message(chat.clone(), text, None))
            .map_err(|error| format!("sending a message failed: {error}"))?;
    }
    runtime
        .block_on(engine.send_image(chat, PICTURE.to_vec(), "image/png".into()))
        .map_err(|error| format!("sending the picture failed: {error}"))?;
    println!("SENT");

    loop {
        match events.recv() {
            Ok(BridgeEvent::ChatMessageReceived { message }) => println!("{}", report(&message)),
            Ok(_) => {}
            Err(_) => return Err("the engine stopped".into()),
        }
    }
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("chat-peer: {message}");
            ExitCode::FAILURE
        }
    }
}
