// SPDX-License-Identifier: AGPL-3.0-only

//! The chat against the local 1.5 test server (`scripts/testenv.sh up`), with two bridge
//! engines as two people:
//! `MUMCORD_LIVE=1 cargo test -p mumcord-mobile-bridge --lib live_chat -- --ignored`.
//! `MUMCORD_PORT_BASE` selects the port (default 64738), `SERVER_PASSWORD` the connect password.
//!
//! Inside the crate, not under `tests/`: a hostile message has to be sent as raw HTML, which the
//! bridge's own commands never do.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use super::*;
use crate::{
    BridgeError, BridgeEvent, ConnectRequest, ConnectionState, EngineConfig, EngineListener,
    MumcordEngine, PasswordInput, SecureStorage, SecureStorageError, ServerInput, ServerState,
};

const WAIT: Duration = Duration::from_secs(20);

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

struct Recorder(Mutex<Sender<BridgeEvent>>);

impl EngineListener for Recorder {
    fn on_event(&self, event: BridgeEvent) {
        assert_eq!(std::thread::current().name(), Some("mumcord-bridge-events"));
        // The test may have finished already; late events are of no interest then.
        let _ = self.0.lock().expect("sender").send(event);
    }
}

/// One person: a bridge engine with its own data folder, connected to the test server.
struct Client {
    engine: Arc<MumcordEngine>,
    events: Receiver<BridgeEvent>,
    name: String,
    session: u32,
    channel: ChatId,
}

fn data_dir(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("mumcord-bridge-chat-{}-{name}", std::process::id()))
}

fn port() -> u16 {
    std::env::var("MUMCORD_PORT_BASE")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(64738)
}

fn wait_for<T>(
    events: &Receiver<BridgeEvent>,
    what: &str,
    mut pick: impl FnMut(BridgeEvent) -> Option<T>,
) -> T {
    let deadline = Instant::now() + WAIT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let event = events
            .recv_timeout(left)
            .unwrap_or_else(|_| panic!("timed out waiting for {what}"));
        if let Some(found) = pick(event) {
            return found;
        }
    }
}

/// Starts an engine in the folder `name` and connects it as `name`. `spare_server` puts another
/// entry into the server list first, so the two engines of this process use different server
/// ids (the engine counts unread messages per process and server id).
async fn client(name: &str, storage: Arc<MemoryStorage>, spare_server: bool) -> Client {
    let (sender, events) = channel();
    let engine = MumcordEngine::new(
        EngineConfig {
            data_dir: data_dir(name).to_string_lossy().into_owned(),
            audio: None,
        },
        storage,
        Arc::new(Recorder(Mutex::new(sender))),
    )
    .expect("engine");
    if spare_server && engine.snapshot().servers.is_empty() {
        engine
            .add_server(ServerInput {
                name: "spare".into(),
                address: "127.0.0.2".into(),
                port: 1,
                username: "nobody".into(),
                password: PasswordInput::Keep,
                identity_id: None,
            })
            .await
            .expect("spare server");
    }
    let username = format!("{name}-{:05x}", std::process::id() & 0xf_ffff);
    engine
        .connect(ConnectRequest {
            address: "127.0.0.1".into(),
            port: port(),
            username: username.clone(),
            password: std::env::var("SERVER_PASSWORD")
                .ok()
                .filter(|password| !password.is_empty()),
        })
        .await
        .expect("connect");
    let server: ServerState = wait_for(&events, "the synced server", |event| match event {
        BridgeEvent::CertificatePromptRequired { prompt } => {
            let engine = engine.clone();
            // Answered from another thread: this one is inside the test's runtime.
            std::thread::spawn(move || {
                tokio::runtime::Runtime::new()
                    .expect("runtime")
                    .block_on(engine.answer_certificate_prompt(prompt.fingerprint, true))
                    .expect("trust");
            });
            None
        }
        BridgeEvent::ConnectionChanged {
            state: ConnectionState::Disconnected { error },
        } => panic!("connecting failed: {error:?}"),
        BridgeEvent::ServerSynced { server } => Some(server),
        _ => None,
    });
    let own = server
        .people
        .iter()
        .find(|person| person.is_self)
        .expect("own person");
    Client {
        engine,
        events,
        name: username,
        session: own.session,
        channel: ChatId::Channel {
            channel_id: own.channel_id,
        },
    }
}

impl Client {
    /// Sends raw HTML to the own channel, as any Mumble client could.
    async fn send_raw(&self, html: &str) {
        let server = lock(&self.engine.connection).server.expect("connected");
        let ChatId::Channel { channel_id } = self.channel else {
            unreachable!("the own chat is a channel");
        };
        self.engine
            .engine
            .send_channel_message(server, channel_id, html)
            .await
            .expect("raw message");
    }

    async fn say(&self, chat: &ChatId, text: &str) -> ChatMessage {
        self.engine
            .send_message(chat.clone(), text.to_owned(), None)
            .await
            .expect("message")
    }

    /// The next message of someone else, with the notifications that came up to it.
    fn next_message(&self) -> (ChatMessage, Vec<ChatNotification>) {
        let mut notifications = Vec::new();
        let message = wait_for(&self.events, "a message", |event| match event {
            BridgeEvent::ChatMessageReceived { message } => Some(message),
            _ => None,
        });
        // The notification of a message follows it directly.
        while let Ok(event) = self.events.recv_timeout(Duration::from_millis(300)) {
            if let BridgeEvent::ChatNotification { notification } = event {
                notifications.push(notification);
            }
        }
        (message, notifications)
    }

    async fn disconnect(self) {
        self.engine.disconnect().await.expect("disconnect");
        wait_for(&self.events, "the disconnect", |event| match event {
            BridgeEvent::ConnectionChanged {
                state: ConnectionState::Disconnected { .. },
            } => Some(()),
            _ => None,
        });
    }
}

fn shown(message: &ChatMessage) -> String {
    content::plain_text(&message.content)
}

fn all_spans(message: &ChatMessage) -> Vec<Span> {
    message
        .content
        .blocks
        .iter()
        .flat_map(|block| match block {
            ContentBlock::Paragraph { spans } | ContentBlock::Quote { spans } => spans.clone(),
            _ => Vec::new(),
        })
        .collect()
}

fn noise_png(width: u32, height: u32) -> Vec<u8> {
    let mut seed = 7u32;
    let image = image::RgbImage::from_fn(width, height, |_, _| {
        seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12_345);
        image::Rgb([(seed >> 16) as u8, (seed >> 8) as u8, seed as u8])
    });
    let mut bytes = Vec::new();
    image
        .write_with_encoder(image::codecs::png::PngEncoder::new(&mut bytes))
        .expect("png");
    bytes
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "needs the local test server (MUMCORD_LIVE=1)"]
async fn live_chat() {
    if !std::env::var("MUMCORD_LIVE").is_ok_and(|v| v == "1") {
        eprintln!("skipped: set MUMCORD_LIVE=1 and start scripts/testenv.sh up");
        return;
    }
    for name in ["alice", "bob"] {
        let _ = std::fs::remove_dir_all(data_dir(name));
    }
    let alice_storage = Arc::new(MemoryStorage::default());
    let alice = client("alice", alice_storage.clone(), false).await;
    let bob = client("bob", Arc::default(), true).await;
    assert_eq!(
        alice.channel, bob.channel,
        "both start in the default channel"
    );
    let channel = alice.channel.clone();

    markup_arrives_harmless(&alice, &bob).await;
    replies_and_mentions(&alice, &bob).await;
    images_within_and_above_the_limits(&alice, &bob).await;
    message_requests_flow(&alice, &bob).await;
    let private = private_messages(&alice, &bob).await;
    blocking(&alice, &bob, &private).await;

    // History survives a restart of the app and pages correctly.
    for number in 1..=5 {
        alice.say(&channel, &format!("history {number}")).await;
    }
    let newest = alice
        .engine
        .chat_page(channel.clone(), None, 3)
        .await
        .expect("page");
    let texts: Vec<String> = newest.messages.iter().map(shown).collect();
    assert_eq!(texts, ["history 3", "history 4", "history 5"]);
    assert!(newest.has_more);
    let older = alice
        .engine
        .chat_page(channel.clone(), Some(newest.messages[0].id.clone()), 2)
        .await
        .expect("older page");
    let texts: Vec<String> = older.messages.iter().map(shown).collect();
    assert_eq!(texts, ["history 1", "history 2"]);
    let everything = alice
        .engine
        .chat_page(channel.clone(), None, 100)
        .await
        .expect("all");
    assert!(!everything.has_more);
    let before: Vec<(String, String, bool)> = everything
        .messages
        .iter()
        .map(|m| (m.id.clone(), shown(m), m.own))
        .collect();
    let with_image = everything
        .messages
        .iter()
        .find_map(|m| {
            m.content.blocks.iter().find_map(|block| match block {
                ContentBlock::Image {
                    image: ImageRef::Embedded { image_id, .. },
                } => Some((m.id.clone(), image_id.clone())),
                _ => None,
            })
        })
        .expect("the stored image message");

    alice.disconnect().await;
    let alice = client("alice", alice_storage, false).await;
    let after = alice
        .engine
        .chat_page(alice.channel.clone(), None, 100)
        .await
        .expect("history after the restart");
    let restored: Vec<(String, String, bool)> = after
        .messages
        .iter()
        .map(|m| (m.id.clone(), shown(m), m.own))
        .collect();
    assert_eq!(restored, before);
    // The image of a stored message is read from the store when it is no longer in memory.
    let image = alice
        .engine
        .chat_image(alice.channel.clone(), with_image.0, with_image.1)
        .await
        .expect("stored image");
    assert!(image.mime.starts_with("image/"));
    let conversations = alice
        .engine
        .private_conversations()
        .await
        .expect("conversations after the restart");
    assert_eq!(conversations.len(), 1);
    assert_eq!(conversations[0].chat, private);

    alice.disconnect().await;
    bob.disconnect().await;
    for name in ["alice", "bob"] {
        let _ = std::fs::remove_dir_all(data_dir(name));
    }
}

/// AC-5.17: script and markup from the server are shown harmlessly.
async fn markup_arrives_harmless(alice: &Client, bob: &Client) {
    bob.send_raw(
        "<script>alert(1)</script><b onclick=\"alert(2)\" style=\"position:fixed\">bold</b> \
         <a href=\"javascript:alert(3)\">bad</a> <a href=\"https://example.com/page\">good</a>\
         <img src=\"https://tracker.example/pixel.gif\" onerror=\"alert(4)\"><iframe src=\"https://evil.example\"></iframe>",
    )
    .await;
    let (message, notifications) = alice.next_message();
    assert_eq!(message.sender_name, bob.name);
    assert_eq!(message.sender_session, Some(bob.session));
    assert_eq!(message.chat, alice.channel);
    assert!(!message.own && !message.blocked && !message.mentions_me);
    assert_eq!(shown(&message), "bold bad good");
    let spans = all_spans(&message);
    assert!(spans.iter().any(|s| s.text == "bold" && s.bold));
    let links: Vec<&SafeLink> = spans.iter().filter_map(|s| s.link.as_ref()).collect();
    assert_eq!(links.len(), 1);
    assert_eq!(links[0].url, "https://example.com/page");
    let images: Vec<&ContentBlock> = message
        .content
        .blocks
        .iter()
        .filter(|block| matches!(block, ContentBlock::Image { .. }))
        .collect();
    assert!(
        matches!(images[..], [ContentBlock::Image { image: ImageRef::Remote { link } }] if link.host == "tracker.example"),
        "{images:?}"
    );
    assert!(
        notifications.is_empty(),
        "a channel message without mention"
    );
    // The remote image is not fetched without the app's go-ahead.
    let image = alice
        .engine
        .load_remote_image("https://tracker.example/pixel.gif".into(), false)
        .await
        .expect("no fetch");
    assert_eq!(image, None);

    // Typed markup is sent as text and arrives as text.
    let own = bob
        .say(&bob.channel, "<b>not bold</b> & <script>x</script>")
        .await;
    assert!(own.own);
    let (message, _) = alice.next_message();
    assert_eq!(shown(&message), "<b>not bold</b> & <script>x</script>");
    assert!(
        all_spans(&message)
            .iter()
            .all(|s| !s.bold && s.link.is_none())
    );
}

async fn replies_and_mentions(alice: &Client, bob: &Client) {
    let channel = alice.channel.clone();
    let question = alice.say(&channel, "lunch at noon?").await;
    let (received, _) = bob.next_message();
    assert_eq!(received.quote, question.quote);
    assert_eq!(
        question.quote,
        ReplyQuote {
            name: alice.name.clone(),
            snippet: "lunch at noon?".into()
        }
    );

    // A reply from the phone: quoted for every client, a mention for the one replied to.
    let reply = bob
        .engine
        .send_message(channel.clone(), "yes".into(), Some(received.quote.clone()))
        .await
        .expect("reply");
    assert_eq!(reply.content.reply, Some(question.quote.clone()));
    let (message, notifications) = alice.next_message();
    assert_eq!(message.content.reply, Some(question.quote.clone()));
    assert_eq!(shown(&message), "yes");
    assert!(message.mentions_me);
    let [notification] = &notifications[..] else {
        panic!("one notification for the reply");
    };
    assert_eq!(notification.kind, NotificationKind::Mention);
    assert_eq!(notification.chat, channel);
    assert_eq!(notification.sender_name, bob.name);
    assert_eq!(notification.preview, "yes");
    assert_eq!(notification.message_id, message.id);

    // A reply as the desktop app writes it, in both of its forms.
    for raw in [
        format!(
            "<blockquote><b>{}</b>: lunch at noon?</blockquote>sure",
            alice.name
        ),
        format!("&gt; {}: lunch at noon?<br>sure", alice.name),
    ] {
        bob.send_raw(&raw).await;
        let (message, _) = alice.next_message();
        assert_eq!(message.content.reply, Some(question.quote.clone()), "{raw}");
        assert_eq!(shown(&message), "sure");
        assert!(message.mentions_me);
    }

    // A reply to someone else's message is no mention.
    let own = bob.say(&channel, "anyone?").await;
    alice.next_message();
    bob.engine
        .send_message(channel.clone(), "me".into(), Some(own.quote))
        .await
        .expect("reply to an own message");
    let (message, notifications) = alice.next_message();
    assert!(message.content.reply.is_some() && !message.mentions_me);
    assert!(notifications.is_empty());

    // Mentions by name and by group; the chat on screen raises no notification.
    bob.say(
        &channel,
        &format!("hi @{}, there", alice.name.to_uppercase()),
    )
    .await;
    let (message, notifications) = alice.next_message();
    assert!(message.mentions_me);
    let mention: Vec<Span> = all_spans(&message)
        .into_iter()
        .filter(|s| s.mention)
        .collect();
    assert_eq!(mention.len(), 1);
    assert_eq!(mention[0].text, format!("@{}", alice.name.to_uppercase()));
    assert_eq!(notifications.len(), 1);

    alice.engine.set_open_chat(Some(channel.clone()));
    assert_eq!(alice.engine.snapshot().open_chat, Some(channel.clone()));
    bob.say(&channel, "@all look").await;
    let (message, notifications) = alice.next_message();
    assert!(message.mentions_me);
    assert!(notifications.is_empty(), "the chat is on screen");
    alice.engine.set_open_chat(None);

    bob.say(&channel, &format!("mail@{}.example", alice.name))
        .await;
    let (message, notifications) = alice.next_message();
    assert!(!message.mentions_me && notifications.is_empty());

    let candidates = alice.engine.mention_candidates("bob".into(), 8);
    assert_eq!(candidates[0].name, bob.name);
    assert_eq!(candidates[0].session, Some(bob.session));
    assert!(
        alice
            .engine
            .mention_candidates("alice".into(), 8)
            .is_empty(),
        "never the user themself"
    );
}

async fn images_within_and_above_the_limits(alice: &Client, bob: &Client) {
    let limits = bob.engine.chat_limits().await.expect("limits");
    let limit = match limits.image {
        0 => mumcord_engine::chat::IMAGE_SOFT_CAP,
        limit => limit,
    } as usize;
    // Noise does not compress: the picture is larger than the server allows and is scaled.
    let source = noise_png(600, 400);
    assert!(source.len() > limit, "{} <= {limit}", source.len());
    let own = bob
        .engine
        .send_image(bob.channel.clone(), source, "image/png".into())
        .await
        .expect("image");
    assert!(matches!(
        own.content.blocks[..],
        [ContentBlock::Image { .. }]
    ));
    let (message, _) = alice.next_message();
    let [
        ContentBlock::Image {
            image:
                ImageRef::Embedded {
                    image_id,
                    width,
                    height,
                    byte_len,
                    ..
                },
        },
    ] = &message.content.blocks[..]
    else {
        panic!("an embedded image: {:?}", message.content.blocks);
    };
    assert!(*width < 600 && (f64::from(*width) / f64::from(*height) - 1.5).abs() < 0.05);
    assert!((*byte_len as usize) < limit);
    assert_eq!(message.quote.snippet, "", "an image has no text to quote");
    let image = alice
        .engine
        .chat_image(message.chat.clone(), message.id.clone(), image_id.clone())
        .await
        .expect("image bytes");
    assert_eq!((image.width, image.height), (*width, *height));
    assert_eq!(image.bytes.len(), *byte_len as usize);
    let decoded = image::load_from_memory(&image.bytes).expect("a real image");
    assert_eq!((decoded.width(), decoded.height()), (*width, *height));
    let missing = alice
        .engine
        .chat_image(message.chat.clone(), message.id.clone(), "9".into())
        .await;
    assert!(
        matches!(missing, Err(BridgeError::NotFound { .. })),
        "{missing:?}"
    );

    // A small image goes out unchanged.
    let small = noise_png(20, 10);
    bob.engine
        .send_image(bob.channel.clone(), small.clone(), "image/png".into())
        .await
        .expect("small image");
    let (message, _) = alice.next_message();
    let [
        ContentBlock::Image {
            image: ImageRef::Embedded { image_id, .. },
        },
    ] = &message.content.blocks[..]
    else {
        panic!("an embedded image");
    };
    let image = alice
        .engine
        .chat_image(message.chat.clone(), message.id.clone(), image_id.clone())
        .await
        .expect("image bytes");
    assert_eq!(image.bytes, small);

    // What is no image, the wrong kind or too large is refused before anything is sent.
    for (bytes, mime) in [
        (b"not an image".to_vec(), "image/png"),
        (noise_png(20, 10), "image/svg+xml"),
        (vec![0; 26 * 1024 * 1024], "image/jpeg"),
    ] {
        let refused = bob
            .engine
            .send_image(bob.channel.clone(), bytes, mime.into())
            .await;
        assert!(
            matches!(refused, Err(BridgeError::InvalidInput { .. })),
            "{refused:?}"
        );
    }
    // Text above the server's limit is refused by the engine's size rule.
    if limits.text > 0 {
        let long = "a".repeat(limits.text as usize + 1);
        let refused = bob
            .engine
            .send_message(bob.channel.clone(), long, None)
            .await;
        assert!(refused.is_err());
    }
    let empty = bob
        .engine
        .send_message(bob.channel.clone(), "  \n ".into(), None)
        .await;
    assert!(
        matches!(empty, Err(BridgeError::InvalidInput { .. })),
        "{empty:?}"
    );
}

/// A private message: conversation list, unread count, notification only when its chat is not
/// on screen. Returns the chat with bob as alice sees it.
async fn private_messages(alice: &Client, bob: &Client) -> ChatId {
    let to_alice = bob.engine.chat_with(alice.session).expect("chat");
    assert!(matches!(to_alice, ChatId::Person { .. }), "{to_alice:?}");
    bob.say(&to_alice, "psst <b>hi</b>").await;
    let (message, notifications) = alice.next_message();
    let private = message.chat.clone();
    assert_eq!(private, alice.engine.chat_with(bob.session).expect("chat"));
    assert_eq!(shown(&message), "psst <b>hi</b>");
    let [notification] = &notifications[..] else {
        panic!("one notification: {}", notifications.len());
    };
    assert_eq!(notification.kind, NotificationKind::PrivateMessage);
    assert_eq!(notification.chat, private);
    assert_eq!(notification.sender_name, bob.name);
    assert_eq!(notification.preview, "psst <b>hi</b>");

    let unread = |client: &Client| {
        client
            .engine
            .snapshot()
            .chat_unread
            .into_iter()
            .find(|unread| unread.chat == private)
            .map_or(0, |unread| unread.count)
    };
    assert_eq!(unread(alice), 1);
    let conversations = alice.engine.private_conversations().await.expect("list");
    let [conversation] = &conversations[..] else {
        panic!("one conversation");
    };
    assert_eq!(conversation.chat, private);
    assert_eq!(conversation.peer_name, bob.name);
    assert_eq!(conversation.peer_session, Some(bob.session));
    assert_eq!(conversation.unread, 1);
    assert_eq!(shown(&conversation.last), "psst <b>hi</b>");
    assert_eq!(conversation.last.sent_at_ms, message.sent_at_ms);

    // With the conversation on screen there is no notification; reading it clears the count.
    alice.engine.set_open_chat(Some(private.clone()));
    bob.say(&to_alice, "second").await;
    let (message, notifications) = alice.next_message();
    assert_eq!(shown(&message), "second");
    assert!(notifications.is_empty());
    assert_eq!(unread(alice), 2);
    alice
        .engine
        .mark_chat_read(private.clone())
        .await
        .expect("read");
    wait_for(&alice.events, "the read count", |event| match event {
        BridgeEvent::ChatUnreadChanged { chat, count: 0 } if chat == private => Some(()),
        _ => None,
    });
    assert_eq!(unread(alice), 0);
    alice.engine.set_open_chat(None);

    // The answer and the paged history of the conversation.
    alice.say(&private, "hello bob").await;
    let (answer, _) = bob.next_message();
    assert_eq!(shown(&answer), "hello bob");
    let page = alice
        .engine
        .chat_page(private.clone(), None, 2)
        .await
        .expect("page");
    let texts: Vec<String> = page.messages.iter().map(shown).collect();
    assert_eq!(texts, ["second", "hello bob"]);
    assert!(page.has_more && page.messages[1].own);
    private
}

/// Bob sends a channel message and Alice reads up to it: what was sent before has arrived by
/// then if it was going to. Returns the private messages and notifications seen on the way.
async fn up_to_marker(alice: &Client, bob: &Client, marker: &str) -> (Vec<ChatMessage>, usize) {
    bob.say(&bob.channel, marker).await;
    let mut private = Vec::new();
    let mut notifications = 0;
    wait_for(&alice.events, "the marker", |event| match event {
        BridgeEvent::ChatMessageReceived { message } if shown(&message) == marker => Some(()),
        BridgeEvent::ChatMessageReceived { message } => {
            if !matches!(message.chat, ChatId::Channel { .. }) {
                private.push(message);
            }
            None
        }
        BridgeEvent::ChatNotification { .. } => {
            notifications += 1;
            None
        }
        _ => None,
    });
    (private, notifications)
}

async fn message_requests_flow(alice: &Client, bob: &Client) {
    let to_alice = bob.engine.chat_with(alice.session).expect("chat");
    let everyone = alice
        .engine
        .private_message_settings()
        .await
        .expect("settings");
    assert_eq!(everyone.rule, PrivateMessageRule::Everyone);
    alice
        .engine
        .set_private_message_settings(PrivateMessageSettings {
            rule: PrivateMessageRule::FriendsOnly,
            allow_current_channel: false,
        })
        .await
        .expect("rule");

    // Friends only, and bob is none: the message is neither shown nor notified, and nothing
    // tells bob.
    bob.say(&to_alice, "filtered").await;
    let (private, notifications) = up_to_marker(alice, bob, "marker one").await;
    assert!(private.is_empty() && notifications == 0);

    // Another Mumcord user asks for a chat instead; the hello that says so may still be on
    // its way right after connecting.
    let deadline = Instant::now() + WAIT;
    loop {
        match bob
            .engine
            .send_chat_request(alice.session, Some("it is bob".into()))
            .await
        {
            Ok(()) => break,
            Err(_) if Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(250)).await;
            }
            Err(error) => panic!("chat request: {error:?}"),
        }
    }
    let requests = wait_for(&alice.events, "the chat request", |event| match event {
        BridgeEvent::MessageRequestsChanged { requests } if !requests.incoming.is_empty() => {
            Some(requests)
        }
        _ => None,
    });
    let [request] = &requests.incoming[..] else {
        panic!("one request");
    };
    assert!(request.chat_request);
    assert_eq!(request.name, bob.name);
    assert_eq!(request.session, bob.session);
    assert_eq!(request.note.as_deref(), Some("it is bob"));
    assert_eq!(
        alice.engine.message_requests().await.expect("requests"),
        requests
    );

    alice
        .engine
        .accept_message_request(request.id)
        .await
        .expect("accept");
    wait_for(&alice.events, "the emptied requests", |event| match event {
        BridgeEvent::MessageRequestsChanged { requests } if requests.incoming.is_empty() => {
            Some(())
        }
        _ => None,
    });
    // Accepted: bob's messages arrive although the rule still says friends only.
    bob.say(&to_alice, "accepted").await;
    let (private, notifications) = up_to_marker(alice, bob, "marker two").await;
    assert_eq!(private.iter().map(shown).collect::<Vec<_>>(), ["accepted"]);
    assert_eq!(notifications, 1);

    alice
        .engine
        .set_private_message_settings(everyone)
        .await
        .expect("rule back");
    alice
        .engine
        .mark_chat_read(private[0].chat.clone())
        .await
        .expect("read");
}

async fn blocking(alice: &Client, bob: &Client, private: &ChatId) {
    let to_alice = bob.engine.chat_with(alice.session).expect("chat");
    alice.engine.block_person(bob.session).await.expect("block");
    let people = wait_for(&alice.events, "the blocked people", |event| match event {
        BridgeEvent::BlockedPeopleChanged { people } => Some(people),
        _ => None,
    });
    let [blocked] = &people[..] else {
        panic!("one blocked person");
    };
    assert_eq!(blocked.name, bob.name);
    assert_eq!(alice.engine.blocked_people().await.expect("list"), people);

    // Blocked: no private message, no notification; a channel message arrives marked, without
    // notification even when it mentions the user.
    bob.say(&to_alice, "blocked private").await;
    bob.say(&bob.channel, &format!("@{} blocked mention", alice.name))
        .await;
    let mut marked = None;
    bob.say(&bob.channel, "marker three").await;
    let mut notifications = 0;
    wait_for(&alice.events, "the marker", |event| match event {
        BridgeEvent::ChatMessageReceived { message } if shown(&message) == "marker three" => {
            assert!(message.blocked);
            Some(())
        }
        BridgeEvent::ChatMessageReceived { message } => {
            assert_eq!(
                message.chat, alice.channel,
                "no private message of a blocked person"
            );
            marked = Some(message);
            None
        }
        BridgeEvent::ChatNotification { .. } => {
            notifications += 1;
            None
        }
        _ => None,
    });
    let marked = marked.expect("the channel message of the blocked person");
    assert!(marked.blocked && !marked.mentions_me);
    assert_eq!(notifications, 0);

    alice
        .engine
        .unblock_person(blocked.id)
        .await
        .expect("unblock");
    wait_for(
        &alice.events,
        "the emptied block list",
        |event| match event {
            BridgeEvent::BlockedPeopleChanged { people } if people.is_empty() => Some(()),
            _ => None,
        },
    );
    bob.say(&to_alice, "unblocked").await;
    let (received, notifications) = up_to_marker(alice, bob, "marker four").await;
    assert_eq!(
        received.iter().map(shown).collect::<Vec<_>>(),
        ["unblocked"]
    );
    assert_eq!(received[0].chat, *private);
    assert_eq!(notifications, 1);
}
