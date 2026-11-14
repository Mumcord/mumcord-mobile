// SPDX-License-Identifier: AGPL-3.0-only

//! What the apps see of the chat. Message content is a small closed model, never HTML: the apps
//! draw text runs, links and images and cannot be made to interpret markup.

/// A chat: a channel, or the private conversation with one person.
#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum ChatId {
    /// Channel ids are valid for the current connection.
    Channel { channel_id: u32 },
    /// A person with a certificate; the conversation is stored and survives reconnects.
    Person { cert_hash: String },
    /// A person without a certificate; the conversation lasts for their session only.
    Session { session: u32 },
}

/// The quoted original at the top of a reply: plain text, capped (64 and 101 characters).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ReplyQuote {
    pub name: String,
    pub snippet: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LinkKind {
    Web,
    /// The address names a still image (png, jpg, jpeg, webp).
    Image,
    /// The address names a GIF.
    Gif,
}

/// A link that was checked: `http` or `https` with a host, nothing else.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SafeLink {
    /// The address to open, normalised.
    pub url: String,
    /// The host the link really goes to (ASCII; international names in their `xn--` form).
    pub host: String,
    pub kind: LinkKind,
    /// The shown text names another address than the link goes to; show `host` with it.
    pub misleading: bool,
}

/// A run of text with one style.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Span {
    /// Plain text; line breaks are `\n`.
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    pub code: bool,
    pub link: Option<SafeLink>,
    /// The run is an `@` mention of the user.
    pub mention: bool,
}

/// An image of a message, by reference; the bytes are fetched on demand.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ImageRef {
    /// Sent inside the message: `MumcordEngine::chat_image` with the message id and `image_id`.
    Embedded {
        image_id: String,
        mime: String,
        width: u32,
        height: u32,
        byte_len: u32,
        animated: bool,
    },
    /// On the web: `MumcordEngine::load_remote_image`. Never fetched without that call.
    Remote { link: SafeLink },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ContentBlock {
    Paragraph {
        spans: Vec<Span>,
    },
    Quote {
        spans: Vec<Span>,
    },
    /// A code block, shown as it is in a fixed-width font.
    Code {
        text: String,
    },
    Image {
        image: ImageRef,
    },
}

/// The content of a message, safe to show.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MessageContent {
    /// Set when the message is a reply; the blocks then hold the reply without the quote.
    pub reply: Option<ReplyQuote>,
    pub blocks: Vec<ContentBlock>,
    /// The message was longer than the bridge passes on (20,000 characters, 16 images).
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatMessage {
    /// Paging cursor. `tmp-…` for a message that was shown but not stored.
    pub id: String,
    pub chat: ChatId,
    pub sender_name: String,
    /// The sender's session while connected; none for history.
    pub sender_session: Option<u32>,
    pub sender_cert_hash: Option<String>,
    pub own: bool,
    pub sent_at_ms: i64,
    /// The sender is blocked: channel messages still arrive, to be shown collapsed or hidden.
    pub blocked: bool,
    /// The message mentions the user (`@name`, a reply to an own message, `@all`, `@channel`).
    pub mentions_me: bool,
    pub content: MessageContent,
    /// What a reply to this message quotes: pass it to `send_message`. The snippet is empty for
    /// a message without text (an image); the app then puts its translated word for "Image".
    pub quote: ReplyQuote,
}

/// A page of stored messages, oldest first.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatPage {
    pub messages: Vec<ChatMessage>,
    /// There may be older messages: ask again with the id of the first message as `before`.
    pub has_more: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatUnread {
    pub chat: ChatId,
    pub count: u32,
}

/// The size rules of the connected server, in characters; 0 is no limit.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ChatLimits {
    pub text: u32,
    /// For a message with an image.
    pub image: u32,
    /// Off: the server shows messages as plain text.
    pub allow_html: bool,
}

/// The bytes of an image in their original encoding (png, jpeg, gif or webp), with the size
/// read from its header.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ImageData {
    pub bytes: Vec<u8>,
    pub mime: String,
    pub width: u32,
    pub height: u32,
    pub animated: bool,
}

/// A suggestion of the `@` autocomplete.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MentionCandidate {
    /// None for the group mentions `all` and `channel`.
    pub session: Option<u32>,
    /// The text after the `@`.
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct GifFavourite {
    pub url: String,
    pub added_at_ms: i64,
    pub last_used_at_ms: i64,
}

/// A private conversation in the list of the Messages tab.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Conversation {
    pub chat: ChatId,
    /// The name the person last wrote under; empty when only own messages are stored.
    pub peer_name: String,
    /// Their session when they are on the server now.
    pub peer_session: Option<u32>,
    pub last: ChatMessage,
    pub unread: u32,
}

/// A held chat or message request (private message rules).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MessageRequest {
    pub id: u64,
    pub session: u32,
    pub name: String,
    pub cert_hash: Option<String>,
    /// A chat request of another Mumcord user; otherwise held messages of a standard client.
    pub chat_request: bool,
    /// The plain text note of a chat request.
    pub note: Option<String>,
    /// The held messages, oldest first.
    pub messages: Vec<ChatMessage>,
    pub received_at_ms: i64,
}

/// A chat request the user sent.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SentChatRequest {
    pub session: u32,
    pub name: String,
    /// False while it is pending; a decline is never told.
    pub accepted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct MessageRequests {
    pub incoming: Vec<MessageRequest>,
    pub sent: Vec<SentChatRequest>,
}

/// "Private messages from".
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PrivateMessageRule {
    Everyone,
    FriendsOnly,
    Nobody,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PrivateMessageSettings {
    pub rule: PrivateMessageRule,
    /// People in the own channel may always send private messages.
    pub allow_current_channel: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BlockedPerson {
    /// For `unblock_person`.
    pub id: i64,
    /// The name the person had when they were blocked.
    pub name: String,
    /// The server the block was set on.
    pub server_label: String,
    pub blocked_at_ms: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum NotificationKind {
    PrivateMessage,
    Mention,
}

/// A message worth a system notification: a private message or a mention in a chat that is not
/// on screen, from a person who is not blocked and not held back by the private message rules.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ChatNotification {
    /// The chat to open on a tap.
    pub chat: ChatId,
    pub kind: NotificationKind,
    pub sender_name: String,
    /// Plain text, at most 140 characters; empty for a message without text (an image).
    pub preview: String,
    pub message_id: String,
}

// Names and message text never reach a log, also not through `{:?}`.
impl std::fmt::Debug for ChatNotification {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ChatNotification")
            .field("kind", &self.kind)
            .finish_non_exhaustive()
    }
}

impl std::fmt::Debug for ImageData {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ImageData")
            .field("mime", &self.mime)
            .field("width", &self.width)
            .field("height", &self.height)
            .field("bytes", &self.bytes.len())
            .finish()
    }
}
