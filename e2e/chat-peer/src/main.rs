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
const CHANNEL: &str = "Lobby";
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

fn phone_name() -> Result<String, String> {
    let mut arguments = std::env::args().skip(1);
    match (arguments.next().as_deref(), arguments.next()) {
        (Some("--phone"), Some(name)) => Ok(name),
        _ => Err("usage: chat-peer --phone <user name of the phone>".into()),
    }
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
    let phone = phone_name()?;
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
    runtime
        .block_on(engine.connect(ConnectRequest {
            address: "127.0.0.1".into(),
            port,
            username: USER_NAME.into(),
            password: None,
        }))
        .map_err(|error| format!("connecting failed: {error}"))?;
    let fingerprint = wait_for(&events, "the certificate question", |event| match event {
        BridgeEvent::CertificatePromptRequired { prompt } => Some(prompt.fingerprint.clone()),
        _ => None,
    })?;
    runtime
        .block_on(engine.answer_certificate_prompt(fingerprint, true))
        .map_err(|error| format!("trusting the certificate failed: {error}"))?;
    let channel = wait_for(&events, "the server's channels", |event| match event {
        BridgeEvent::ServerSynced { server } => Some(
            server
                .channels
                .iter()
                .find(|channel| channel.name == CHANNEL)
                .map(|channel| channel.id),
        ),
        _ => None,
    })?
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
