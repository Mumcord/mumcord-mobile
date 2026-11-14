// SPDX-License-Identifier: AGPL-3.0-only

//! Chat: channel chat, private conversations, message requests, blocking, images and GIF
//! favourites, as the engine has them, in a form the phone apps can show safely.
//!
//! The engine routes, sanitises, stores and counts; `content` turns its sanitised HTML into the
//! safe model, `wire` holds the conventions shared with the desktop app (replies, mentions,
//! typed text), `images` the image limits, `api` the commands.

mod api;
mod content;
mod images;
mod types;
mod wire;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use mumcord_engine::{
    ChatMessageView, ConnState, ConversationRef, Engine, EngineEvent, MessageRequestView,
    PmRequestsView, ServerId, UserView,
};
use mumcord_store::BlockedUser;

pub use types::{
    BlockedPerson, ChatId, ChatLimits, ChatMessage, ChatNotification, ChatPage, ChatUnread,
    ContentBlock, Conversation, GifFavourite, ImageData, ImageRef, LinkKind, MentionCandidate,
    MessageContent, MessageRequest, MessageRequests, NotificationKind, PrivateMessageRule,
    PrivateMessageSettings, ReplyQuote, SafeLink, SentChatRequest, Span,
};

use crate::events::{BridgeEvent, SharedConnection, lock};
use images::ImageCache;

/// Longest name passed on; Mumble names are much shorter.
const MAX_NAME_CHARS: usize = 128;
/// Longest preview of a notification.
const PREVIEW_CHARS: usize = 140;
/// Stored messages looked at to find the original of a reply.
const REPLY_HISTORY: u32 = 100;

/// What the bridge keeps about the chat of the current connection.
#[derive(Default)]
pub(crate) struct ChatState {
    /// The chat on screen, as the app last said.
    pub(crate) open: Option<ChatId>,
    pub(crate) unread: HashMap<ChatId, u32>,
    /// The user's name, and the names they had earlier in this session: a rename must not hide
    /// mentions that were meant for them.
    own_name: Option<String>,
    earlier_names: Vec<String>,
    blocked: Vec<BlockedUser>,
    /// `@all` and `@channel` are switched off for this server.
    group_mentions_muted: bool,
    pub(crate) images: ImageCache,
}

pub(crate) type SharedChat = Arc<Mutex<ChatState>>;

impl ChatState {
    pub(crate) fn unread_list(&self) -> Vec<ChatUnread> {
        let mut list: Vec<ChatUnread> = self
            .unread
            .iter()
            .filter(|(_, count)| **count > 0)
            .map(|(chat, count)| ChatUnread {
                chat: chat.clone(),
                count: *count,
            })
            .collect();
        list.sort_by_key(|unread| format!("{:?}", unread.chat));
        list
    }
}

pub(crate) fn chat_id(conversation: &ConversationRef) -> ChatId {
    match conversation {
        ConversationRef::Channel { channel_id } => ChatId::Channel {
            channel_id: *channel_id,
        },
        ConversationRef::Private { cert_hash } => ChatId::Person {
            cert_hash: cert_hash.clone(),
        },
        ConversationRef::PrivateSession { session } => ChatId::Session { session: *session },
    }
}

pub(crate) fn conversation(chat: &ChatId) -> ConversationRef {
    match chat {
        ChatId::Channel { channel_id } => ConversationRef::Channel {
            channel_id: *channel_id,
        },
        ChatId::Person { cert_hash } => ConversationRef::Private {
            cert_hash: cert_hash.clone(),
        },
        ChatId::Session { session } => ConversationRef::PrivateSession { session: *session },
    }
}

fn clean_name(name: &str) -> String {
    content::clean_label(name)
        .chars()
        .take(MAX_NAME_CHARS)
        .collect()
}

/// The user as the chat rules need them.
pub(crate) struct Me {
    server: ServerId,
    name: Option<String>,
    earlier_names: Vec<String>,
    channel: Option<u32>,
    cert_hash: Option<String>,
    group_mentions_muted: bool,
    blocked: Vec<BlockedUser>,
}

impl Me {
    /// Nothing is known about the user before the server is synced.
    pub(crate) fn of(connection: &SharedConnection, chat: &SharedChat) -> Option<Self> {
        let (server, own) = {
            let connection = lock(connection);
            (connection.server?, connection.own_user().cloned())
        };
        let chat = lock(chat);
        Some(Self {
            server,
            name: own.as_ref().map(|user| user.name.clone()),
            earlier_names: chat.earlier_names.clone(),
            channel: own.as_ref().map(|user| user.channel_id),
            cert_hash: own.and_then(|user| user.cert_hash),
            group_mentions_muted: chat.group_mentions_muted,
            blocked: chat.blocked.clone(),
        })
    }

    fn blocks(&self, cert_hash: Option<&str>, name: &str) -> bool {
        self.cert_hash.as_deref().is_some_and(|own| {
            self.blocked
                .iter()
                .any(|block| block.covers(own, self.server, cert_hash, name))
        })
    }

    /// Every `@word` that means the user in `chat`.
    fn mention_names(&self, chat: &ChatId) -> Vec<String> {
        let mut names: Vec<String> = self.name.iter().cloned().collect();
        names.extend(self.earlier_names.iter().cloned());
        if let (ChatId::Channel { channel_id }, false) = (chat, self.group_mentions_muted) {
            names.push("all".to_owned());
            // `@channel` is for the people in the channel, not for those who only listen to it.
            if Some(*channel_id) == self.channel {
                names.push("channel".to_owned());
            }
        }
        names
    }
}

/// A message on its way to the app: read, but not yet judged against the others.
pub(crate) struct Draft {
    view: ChatMessageView,
    parsed: content::Parsed,
    text: String,
}

impl Draft {
    pub(crate) fn new(view: ChatMessageView) -> Self {
        let parsed = content::parse(&view.body);
        let text = content::plain_text(&parsed.content);
        Self { view, parsed, text }
    }

    fn candidate(&self) -> wire::Candidate<'_> {
        wire::Candidate {
            id: &self.view.id,
            own: self.view.own,
            sender_name: &self.view.sender_name,
            text: &self.text,
        }
    }

    /// Whether finding out if this is a reply to an own message needs the earlier messages.
    fn quotes(&self, me: &Me) -> bool {
        !self.view.own
            && self.parsed.content.reply.as_ref().is_some_and(|quote| {
                me.name
                    .as_deref()
                    .is_some_and(|name| wire::capped(name, "").name == quote.name)
            })
    }

    fn finish(
        self,
        me: &Me,
        history: &[wire::Candidate<'_>],
    ) -> (ChatMessage, Vec<(String, ImageData)>) {
        let Self { view, parsed, text } = self;
        let chat = chat_id(&view.conversation);
        let mut content = parsed.content;
        let blocked = !view.own && me.blocks(view.sender_cert_hash.as_deref(), &view.sender_name);
        let mut mentions_me = false;
        if !view.own && !blocked {
            mentions_me = content::mark_mentions(&mut content, &me.mention_names(&chat));
            if let (Some(quote), Some(name)) = (&content.reply, &me.name) {
                mentions_me |= wire::replies_to_own(&view.id, quote, name, history);
            }
        }
        let sender_name = clean_name(&view.sender_name);
        let message = ChatMessage {
            quote: wire::capped(&sender_name, &wire::snippet(&text)),
            id: view.id,
            chat,
            sender_name,
            sender_session: view.sender_session,
            sender_cert_hash: view.sender_cert_hash,
            own: view.own,
            sent_at_ms: view.sent_at,
            blocked,
            mentions_me,
            content,
        };
        (message, parsed.images)
    }
}

/// Messages of one chat for the app, oldest first; their embedded images go to the cache.
pub(crate) fn messages(
    views: Vec<ChatMessageView>,
    me: &Me,
    chat: &SharedChat,
) -> Vec<ChatMessage> {
    let drafts: Vec<Draft> = views.into_iter().map(Draft::new).collect();
    let texts: Vec<(String, bool, String, String)> = drafts
        .iter()
        .map(|d| {
            (
                d.view.id.clone(),
                d.view.own,
                d.view.sender_name.clone(),
                d.text.clone(),
            )
        })
        .collect();
    let history: Vec<wire::Candidate<'_>> = texts
        .iter()
        .map(|(id, own, sender_name, text)| wire::Candidate {
            id,
            own: *own,
            sender_name,
            text,
        })
        .collect();
    let mut out = Vec::with_capacity(drafts.len());
    for draft in drafts {
        let (message, images) = draft.finish(me, &history);
        if !images.is_empty() {
            lock(chat).images.put(&message.id, images);
        }
        out.push(message);
    }
    out
}

/// One message outside a page (a new one, an own one, the last of a conversation).
pub(crate) fn message(
    engine: &Engine,
    view: ChatMessageView,
    me: &Me,
    chat: &SharedChat,
) -> ChatMessage {
    let draft = Draft::new(view);
    let earlier: Vec<Draft> = if draft.quotes(me) {
        // Without the stored messages the name in the quote decides.
        engine
            .history_load(
                me.server,
                draft.view.conversation.clone(),
                None,
                REPLY_HISTORY,
            )
            .unwrap_or_default()
            .into_iter()
            .map(Draft::new)
            .collect()
    } else {
        Vec::new()
    };
    let history: Vec<wire::Candidate<'_>> = earlier.iter().map(Draft::candidate).collect();
    let (message, images) = draft.finish(me, &history);
    if !images.is_empty() {
        lock(chat).images.put(&message.id, images);
    }
    message
}

pub(crate) fn message_request(
    engine: &Engine,
    request: MessageRequestView,
    me: &Me,
    chat: &SharedChat,
) -> MessageRequest {
    MessageRequest {
        id: request.id,
        session: request.session,
        name: clean_name(&request.name),
        cert_hash: request.cert_hash,
        chat_request: request.chat_request,
        note: request.note.as_deref().map(content::clean_text),
        messages: request
            .messages
            .into_iter()
            .map(|view| message(engine, view, me, chat))
            .collect(),
        received_at_ms: request.received_at,
    }
}

pub(crate) fn message_requests(
    engine: &Engine,
    view: PmRequestsView,
    me: &Me,
    chat: &SharedChat,
) -> MessageRequests {
    MessageRequests {
        incoming: view
            .incoming
            .into_iter()
            .map(|request| message_request(engine, request, me, chat))
            .collect(),
        sent: view
            .sent
            .into_iter()
            .map(|sent| SentChatRequest {
                session: sent.session,
                name: clean_name(&sent.name),
                accepted: sent.accepted,
            })
            .collect(),
    }
}

/// The blocks of the identity the user is connected with.
pub(crate) fn blocked_people(
    blocked: &[BlockedUser],
    own_cert_hash: Option<&str>,
) -> Vec<BlockedPerson> {
    blocked
        .iter()
        .filter(|block| own_cert_hash.is_none_or(|own| block.own_cert_hash == own))
        .map(|block| BlockedPerson {
            id: block.id,
            name: clean_name(&block.name),
            server_label: clean_name(&block.server_label),
            blocked_at_ms: block.blocked_at,
        })
        .collect()
}

/// The notification for a new message, when it is worth one: a private message or a mention,
/// from someone else who is not blocked, in a chat that is not on screen. Messages held or
/// dropped by the private message rules never get here; the engine keeps them back.
fn notification(message: &ChatMessage, open: Option<&ChatId>) -> Option<ChatNotification> {
    if message.own || message.blocked || open == Some(&message.chat) {
        return None;
    }
    let kind = match message.chat {
        ChatId::Channel { .. } if message.mentions_me => NotificationKind::Mention,
        ChatId::Channel { .. } => return None,
        _ => NotificationKind::PrivateMessage,
    };
    Some(ChatNotification {
        chat: message.chat.clone(),
        kind,
        sender_name: message.sender_name.clone(),
        preview: content::plain_text(&message.content)
            .chars()
            .take(PREVIEW_CHARS)
            .collect(),
        message_id: message.id.clone(),
    })
}

/// Whether the chat has something to do with this engine event; only those are kept for it.
pub(crate) fn wants(event: &EngineEvent) -> bool {
    matches!(
        event,
        EngineEvent::ServerSynced(_)
            | EngineEvent::StateChanged(_)
            | EngineEvent::UserUpserted(_)
            | EngineEvent::MessageReceived(_)
            | EngineEvent::UnreadChanged(_)
            | EngineEvent::PmRequestsChanged(_)
            | EngineEvent::BlockedUsersChanged(_)
    )
}

fn renamed(chat: &SharedChat, own: Option<&UserView>) {
    let Some(own) = own else { return };
    let mut chat = lock(chat);
    match chat.own_name.replace(own.name.clone()) {
        Some(before) if before != own.name && !chat.earlier_names.contains(&before) => {
            chat.earlier_names.push(before);
        }
        _ => {}
    }
}

/// Applies an engine event of the connected server to the chat state and turns it into the
/// events for the app. Runs after the connection state took the event over.
pub(crate) async fn handle(
    engine: &Engine,
    connection: &SharedConnection,
    chat: &SharedChat,
    event: EngineEvent,
) -> Vec<BridgeEvent> {
    match event {
        EngineEvent::ServerSynced(snapshot) => {
            // Logged without detail: an error text could name a person.
            let blocked = engine.blocked_users().unwrap_or_else(|_| {
                tracing::warn!("reading the blocked people failed");
                Vec::new()
            });
            let muted = engine.servers().await.ok().is_some_and(|servers| {
                servers
                    .iter()
                    .any(|s| s.id == snapshot.server_id && s.group_mentions_muted)
            });
            let own_name = snapshot
                .users
                .iter()
                .find(|user| user.session == snapshot.self_session)
                .map(|user| user.name.clone());
            let mut state = lock(chat);
            state.unread = snapshot
                .unread
                .iter()
                .map(|unread| (chat_id(&unread.conversation), unread.count))
                .collect();
            state.own_name = own_name;
            state.earlier_names.clear();
            state.blocked = blocked;
            state.group_mentions_muted = muted;
            state.images.clear();
            state
                .unread_list()
                .into_iter()
                .map(|unread| BridgeEvent::ChatUnreadChanged {
                    chat: unread.chat,
                    count: unread.count,
                })
                .collect()
        }
        EngineEvent::StateChanged(ConnState::Disconnected { .. }) => {
            let mut state = lock(chat);
            let open = state.open.take();
            *state = ChatState::default();
            // What is on screen is the app's to say; it stays until the app changes it.
            state.open = open;
            Vec::new()
        }
        EngineEvent::UserUpserted(_) => {
            let own = lock(connection).own_user().cloned();
            renamed(chat, own.as_ref());
            Vec::new()
        }
        EngineEvent::MessageReceived(view) => {
            let Some(me) = Me::of(connection, chat) else {
                return Vec::new();
            };
            let message = message(engine, *view, &me, chat);
            let notification = notification(&message, lock(chat).open.as_ref());
            let mut events = vec![BridgeEvent::ChatMessageReceived { message }];
            events.extend(
                notification.map(|notification| BridgeEvent::ChatNotification { notification }),
            );
            events
        }
        EngineEvent::UnreadChanged(unread) => {
            let id = chat_id(&unread.conversation);
            lock(chat).unread.insert(id.clone(), unread.count);
            vec![BridgeEvent::ChatUnreadChanged {
                chat: id,
                count: unread.count,
            }]
        }
        EngineEvent::PmRequestsChanged(view) => {
            let Some(me) = Me::of(connection, chat) else {
                return Vec::new();
            };
            vec![BridgeEvent::MessageRequestsChanged {
                requests: message_requests(engine, view, &me, chat),
            }]
        }
        EngineEvent::BlockedUsersChanged(blocked) => {
            let own = lock(connection)
                .own_user()
                .and_then(|user| user.cert_hash.clone());
            let people = blocked_people(&blocked, own.as_deref());
            lock(chat).blocked = blocked;
            vec![BridgeEvent::BlockedPeopleChanged { people }]
        }
        _ => Vec::new(),
    }
}

#[cfg(test)]
mod live_tests;

#[cfg(test)]
mod tests {
    use super::*;

    fn message(chat: ChatId, mentions_me: bool) -> ChatMessage {
        let parsed = content::parse("hello <b>there</b>");
        ChatMessage {
            id: "7".into(),
            chat,
            sender_name: "Maya".into(),
            sender_session: Some(3),
            sender_cert_hash: None,
            own: false,
            sent_at_ms: 0,
            blocked: false,
            mentions_me,
            content: parsed.content,
            quote: wire::capped("Maya", "hello there"),
        }
    }

    fn private() -> ChatId {
        ChatId::Person {
            cert_hash: "ab".into(),
        }
    }

    #[test]
    fn a_private_message_notifies_unless_its_chat_is_open() {
        let note = notification(&message(private(), false), None).expect("notification");
        assert_eq!(note.kind, NotificationKind::PrivateMessage);
        assert_eq!(
            (note.sender_name.as_str(), note.preview.as_str()),
            ("Maya", "hello there")
        );
        assert_eq!(note.chat, private());
        let other = ChatId::Channel { channel_id: 1 };
        assert!(notification(&message(private(), false), Some(&other)).is_some());
        assert!(notification(&message(private(), false), Some(&private())).is_none());
    }

    #[test]
    fn a_channel_message_notifies_only_as_a_mention() {
        let channel = ChatId::Channel { channel_id: 4 };
        assert!(notification(&message(channel.clone(), false), None).is_none());
        let note = notification(&message(channel.clone(), true), None).expect("notification");
        assert_eq!(note.kind, NotificationKind::Mention);
        assert!(notification(&message(channel.clone(), true), Some(&channel)).is_none());
    }

    #[test]
    fn own_and_blocked_messages_never_notify() {
        let mut own = message(private(), true);
        own.own = true;
        assert!(notification(&own, None).is_none());
        let mut blocked = message(ChatId::Channel { channel_id: 4 }, true);
        blocked.blocked = true;
        assert!(notification(&blocked, None).is_none());
    }

    #[test]
    fn a_notification_shows_nothing_of_itself_in_debug_output() {
        let note = notification(&message(private(), false), None).expect("notification");
        let shown = format!("{note:?}");
        assert!(
            !shown.contains("Maya") && !shown.contains("hello"),
            "{shown}"
        );
    }

    #[test]
    fn names_are_one_clean_line() {
        assert_eq!(clean_name("Ma\u{202E}ya\n\u{200B}x"), "Mayax");
        assert_eq!(clean_name(&"n".repeat(500)).chars().count(), MAX_NAME_CHARS);
    }
}
