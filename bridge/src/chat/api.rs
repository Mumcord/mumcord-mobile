// SPDX-License-Identifier: AGPL-3.0-only

//! The chat commands of [`MumcordEngine`].

use mumcord_engine::{ChatMessageView, Engine, ServerId};
use mumcord_store::PmRule;

use super::types::{
    BlockedPerson, ChatId, ChatLimits, ChatMessage, ChatPage, Conversation, GifFavourite,
    ImageData, MentionCandidate, MessageRequests, PrivateMessageRule, PrivateMessageSettings,
    ReplyQuote,
};
use super::{Me, SharedChat, content, images, wire};
use crate::engine::MumcordEngine;
use crate::error::BridgeError;
use crate::events::{SharedConnection, lock};

/// Most messages of one page.
const MAX_PAGE: u32 = 100;
/// The markup around an image that is sent. Self-closed: a Mumble server reads a message above
/// its text limit as XML to tell the image from the text, and refuses it when that fails.
const IMAGE_OPEN: &str = "<img src=\"";
const IMAGE_CLOSE: &str = "\"/>";

fn server_of(connection: &SharedConnection) -> Result<ServerId, BridgeError> {
    lock(connection)
        .server
        .ok_or_else(BridgeError::not_connected)
}

fn me(connection: &SharedConnection, chat: &SharedChat) -> Result<Me, BridgeError> {
    Me::of(connection, chat).ok_or_else(BridgeError::not_connected)
}

fn limits(engine: &Engine, server: ServerId) -> Result<ChatLimits, BridgeError> {
    let info = engine
        .snapshot_of(server)
        .ok_or_else(BridgeError::not_connected)?
        .info;
    Ok(ChatLimits {
        text: info.max_message_length,
        image: info.max_image_message_length,
        allow_html: info.allow_html,
    })
}

/// Sends the HTML of an own message to `chat` and returns the message as the engine stored it.
async fn send_html(
    engine: &Engine,
    connection: &SharedConnection,
    server: ServerId,
    chat: &ChatId,
    html: &str,
) -> Result<ChatMessageView, BridgeError> {
    let session = match chat {
        ChatId::Channel { channel_id } => {
            return Ok(engine
                .send_channel_message(server, *channel_id, html)
                .await?);
        }
        ChatId::Session { session } => *session,
        // After a reconnect the person has a new session, found by their certificate.
        ChatId::Person { cert_hash } => {
            lock(connection)
                .session_of(cert_hash)
                .ok_or_else(|| BridgeError::NotFound {
                    detail: "this person is not on the server".to_owned(),
                })?
        }
    };
    Ok(engine.send_private_message(server, session, html).await?)
}

#[uniffi::export]
impl MumcordEngine {
    /// A page of the stored messages of `chat`, oldest first: the newest ones without `before`,
    /// else the ones before the message with that id. At most 100 per page (`limit` 1–100).
    /// History is stored on the phone only, within the engine's retention setting; a chat with a
    /// person without certificate has none.
    pub async fn chat_page(
        &self,
        chat: ChatId,
        before: Option<String>,
        limit: u32,
    ) -> Result<ChatPage, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            let me = me(&connection, &state)?;
            let limit = limit.clamp(1, MAX_PAGE);
            let views = engine.history_load(
                server_of(&connection)?,
                super::conversation(&chat),
                before.as_deref(),
                limit,
            )?;
            let has_more = views.len() == limit as usize;
            Ok(ChatPage {
                messages: super::messages(views, &me, &state),
                has_more,
            })
        })
        .await
    }

    /// Sends text to `chat`: a channel the user may write in, or a person on the server. The
    /// text is sent as text (markup in it is shown, not interpreted), with line breaks and web
    /// addresses as links. `reply_to` (the `quote` of the message replied to) makes it a reply
    /// every Mumble client can read. Returns the own message; it does not arrive as an event.
    pub async fn send_message(
        &self,
        chat: ChatId,
        text: String,
        reply_to: Option<ReplyQuote>,
    ) -> Result<ChatMessage, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            let server = server_of(&connection)?;
            let allow_html = limits(&engine, server)?.allow_html;
            let body = wire::text_to_html(&text, allow_html);
            if body.is_empty() {
                return Err(BridgeError::invalid("empty message"));
            }
            let quote = reply_to
                .map(|quote| {
                    wire::quote_html(&wire::capped(&quote.name, &quote.snippet), allow_html)
                })
                .unwrap_or_default();
            let view = send_html(
                &engine,
                &connection,
                server,
                &chat,
                &format!("{quote}{body}"),
            )
            .await?;
            Ok(super::message(
                &engine,
                view,
                &me(&connection, &state)?,
                &state,
            ))
        })
        .await
    }

    /// Sends an image (png, jpeg, gif or webp; at most 25 MiB) to `chat`. An image that fits the
    /// server's limit for image messages is sent as it is; a larger one is scaled down (longest
    /// edge at most 1920 pixels) and sent as PNG or JPEG. Fails with `InvalidInput` when it
    /// cannot be made to fit.
    pub async fn send_image(
        &self,
        chat: ChatId,
        bytes: Vec<u8>,
        mime: String,
    ) -> Result<ChatMessage, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            if !["image/png", "image/jpeg", "image/gif", "image/webp"].contains(&mime.as_str()) {
                return Err(BridgeError::invalid("not an image that can be sent"));
            }
            let server = server_of(&connection)?;
            let limit = match limits(&engine, server)?.image {
                0 => mumcord_engine::chat::IMAGE_SOFT_CAP,
                limit => limit,
            };
            let budget = (limit as usize).saturating_sub(IMAGE_OPEN.len() + IMAGE_CLOSE.len());
            // Scaling a photo takes a moment; it must not hold up the runtime.
            let address =
                tokio::task::spawn_blocking(move || images::prepare_outgoing(bytes, budget))
                    .await
                    .map_err(|_| BridgeError::internal("preparing the image failed"))??;
            let html = format!("{IMAGE_OPEN}{address}{IMAGE_CLOSE}");
            let view = send_html(&engine, &connection, server, &chat, &html).await?;
            Ok(super::message(
                &engine,
                view,
                &me(&connection, &state)?,
                &state,
            ))
        })
        .await
    }

    /// The bytes of an image that was sent inside a message (`ImageRef::Embedded`).
    pub async fn chat_image(
        &self,
        chat: ChatId,
        message_id: String,
        image_id: String,
    ) -> Result<ImageData, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            let missing = || BridgeError::NotFound {
                detail: "unknown image".to_owned(),
            };
            if let Some(image) = lock(&state).images.get(&message_id, &image_id) {
                return Ok(image);
            }
            // No longer kept in memory: read from the stored message again.
            let id: i64 = message_id.parse().map_err(|_| missing())?;
            let after = id.checked_add(1).ok_or_else(missing)?.to_string();
            let view = engine
                .history_load(
                    server_of(&connection)?,
                    super::conversation(&chat),
                    Some(&after),
                    1,
                )?
                .into_iter()
                .find(|view| view.id == message_id)
                .ok_or_else(missing)?;
            let parsed = content::parse(&view.body);
            lock(&state).images.put(&message_id, parsed.images);
            lock(&state)
                .images
                .get(&message_id, &image_id)
                .ok_or_else(missing)
        })
        .await
    }

    /// Fetches an image or GIF from the web for showing it inline (`ImageRef::Remote`, a link of
    /// kind `Image` or `Gif`). `load_media` is the app's "Load images and GIFs" decision for the
    /// current network: with `false` nothing is fetched. Nothing is fetched either when link
    /// previews are switched off in the settings. The engine fetches it: public addresses only
    /// (also after redirects), at most 16 MiB, stills scaled to a display size, animations
    /// unchanged. None when the image cannot or may not be shown.
    pub async fn load_remote_image(
        &self,
        url: String,
        load_media: bool,
    ) -> Result<Option<ImageData>, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let link = content::safe_link(&url)
                .ok_or_else(|| BridgeError::invalid("not a web address"))?;
            if !load_media {
                return Ok(None);
            }
            Ok(engine
                .inline_image(&link.url)
                .await
                .and_then(|image| images::from_data_url(&image.data_url).ok()))
        })
        .await
    }

    /// The suggestions for an `@` mention: `query` is what was typed after the `@`. People on
    /// the server whose name starts with it, those in the own channel first, then `channel` and
    /// `all`; at most 8. The chosen name is put into the text as `@name`.
    pub fn mention_candidates(&self, query: String, limit: u32) -> Vec<MentionCandidate> {
        let connection = lock(&self.connection);
        let own = connection.own_user();
        let people: Vec<wire::MentionPerson<'_>> = connection
            .people()
            .map(|user| wire::MentionPerson {
                session: user.session,
                name: &user.name,
                channel_id: user.channel_id,
                hidden: user.mumcord_bot.as_ref().is_some_and(|bot| !bot.voice),
            })
            .collect();
        wire::mention_candidates(
            &people,
            &query,
            own.map(|user| user.channel_id),
            own.map(|user| user.session),
            limit,
        )
    }

    /// The size rules of the connected server for messages.
    pub async fn chat_limits(&self) -> Result<ChatLimits, BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move { limits(&engine, server_of(&connection)?) })
            .await
    }

    /// Marks `chat` as read: its unread count becomes 0 (`ChatUnreadChanged`).
    pub async fn mark_chat_read(&self, chat: ChatId) -> Result<(), BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move {
            engine.conversation_mark_read(server_of(&connection)?, super::conversation(&chat))?;
            Ok(())
        })
        .await
    }

    /// Tells the bridge which chat is on screen (none when no chat is, or the app is in the
    /// background or locked). Messages of that chat raise no `ChatNotification`.
    pub fn set_open_chat(&self, chat: Option<ChatId>) {
        lock(&self.chat).open = chat;
    }

    /// The GIF favourites, most recently used first. They are stored on the phone only.
    pub async fn gif_favourites(&self) -> Result<Vec<GifFavourite>, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            Ok(engine
                .gif_favorites()?
                .into_iter()
                .map(|favourite| GifFavourite {
                    url: favourite.url,
                    added_at_ms: favourite.added_at,
                    last_used_at_ms: favourite.last_used_at,
                })
                .collect())
        })
        .await
    }

    /// Saves the address of a GIF (a link of a message) as a favourite; at most 200 are kept.
    pub async fn add_gif_favourite(&self, url: String) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let link = content::safe_link(&url)
                .ok_or_else(|| BridgeError::invalid("not a web address"))?;
            Ok(engine.gif_favorite_add(&link.url)?)
        })
        .await
    }

    /// Removes a GIF favourite by its address as listed.
    pub async fn remove_gif_favourite(&self, url: String) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { Ok(engine.gif_favorite_remove(&url)?) })
            .await
    }

    /// Sends a GIF favourite to `chat` as a link, as the desktop app does, and moves it to the
    /// front of the favourites.
    pub async fn send_gif_favourite(
        &self,
        chat: ChatId,
        url: String,
    ) -> Result<ChatMessage, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            let link = content::safe_link(&url)
                .ok_or_else(|| BridgeError::invalid("not a web address"))?;
            let server = server_of(&connection)?;
            let allow_html = limits(&engine, server)?.allow_html;
            let html = wire::text_to_html(&link.url, allow_html);
            let view = send_html(&engine, &connection, server, &chat, &html).await?;
            // Sorting the favourites is a convenience; the message is sent either way.
            if engine.gif_favorite_use(&url).is_err() {
                tracing::warn!("moving a GIF favourite to the front failed");
            }
            Ok(super::message(
                &engine,
                view,
                &me(&connection, &state)?,
                &state,
            ))
        })
        .await
    }

    /// The private chat with a person on the server, for opening it from the people list: the
    /// stored conversation when they have a certificate, else the chat of their session.
    pub fn chat_with(&self, session: u32) -> Result<ChatId, BridgeError> {
        let connection = lock(&self.connection);
        let person = connection
            .people()
            .find(|user| user.session == session)
            .ok_or_else(|| BridgeError::NotFound {
                detail: "unknown person".to_owned(),
            })?;
        Ok(match &person.cert_hash {
            Some(cert_hash) => ChatId::Person {
                cert_hash: cert_hash.clone(),
            },
            None => ChatId::Session { session },
        })
    }

    /// The stored private conversations on the connected server, newest first, each with its
    /// last message and unread count. Read it again after a private message was received or
    /// sent. Conversations with people without certificate are not stored and not listed.
    pub async fn private_conversations(&self) -> Result<Vec<Conversation>, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            let me = me(&connection, &state)?;
            let views = engine.private_conversations(server_of(&connection)?)?;
            Ok(views
                .into_iter()
                .map(|view| {
                    let last = super::message(&engine, view.last, &me, &state);
                    let peer_session = match &last.chat {
                        ChatId::Person { cert_hash } => lock(&connection).session_of(cert_hash),
                        _ => None,
                    };
                    Conversation {
                        unread: lock(&state).unread.get(&last.chat).copied().unwrap_or(0),
                        chat: last.chat.clone(),
                        peer_name: view
                            .peer_name
                            .as_deref()
                            .map(super::clean_name)
                            .unwrap_or_default(),
                        peer_session,
                        last,
                    }
                })
                .collect())
        })
        .await
    }

    /// The held message requests and the chat requests the user sent. They last for the
    /// connection only.
    pub async fn message_requests(&self) -> Result<MessageRequests, BridgeError> {
        let (engine, connection, state) = self.chat_parts();
        self.run(async move {
            let me = me(&connection, &state)?;
            let view = engine.pm_requests(server_of(&connection)?);
            Ok(super::message_requests(&engine, view, &me, &state))
        })
        .await
    }

    /// Accepts a message request: its held messages arrive as messages of the conversation, and
    /// the person may keep writing.
    pub async fn accept_message_request(&self, id: u64) -> Result<(), BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move { Ok(engine.pm_request_accept(server_of(&connection)?, id)?) })
            .await
    }

    /// Declines a message request. The other person is never told.
    pub async fn decline_message_request(&self, id: u64) -> Result<(), BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move { Ok(engine.pm_request_decline(server_of(&connection)?, id)?) })
            .await
    }

    /// Asks another Mumcord user for a chat, with an optional short note (200 characters).
    /// Fails with `InvalidInput` for people whose client cannot receive chat requests.
    pub async fn send_chat_request(
        &self,
        session: u32,
        note: Option<String>,
    ) -> Result<(), BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move {
            Ok(engine.chat_request_send(server_of(&connection)?, session, note.as_deref())?)
        })
        .await
    }

    /// Who may send the user private messages.
    pub async fn private_message_settings(&self) -> Result<PrivateMessageSettings, BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let settings = engine.settings()?.private_messages;
            Ok(PrivateMessageSettings {
                rule: match settings.rule {
                    PmRule::Everyone => PrivateMessageRule::Everyone,
                    PmRule::FriendsOnly => PrivateMessageRule::FriendsOnly,
                    PmRule::Nobody => PrivateMessageRule::Nobody,
                },
                allow_current_channel: settings.allow_current_channel,
            })
        })
        .await
    }

    /// Saves who may send the user private messages; it applies from the next message on.
    pub async fn set_private_message_settings(
        &self,
        settings: PrivateMessageSettings,
    ) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move {
            let mut saved = engine.settings()?;
            saved.private_messages.rule = match settings.rule {
                PrivateMessageRule::Everyone => PmRule::Everyone,
                PrivateMessageRule::FriendsOnly => PmRule::FriendsOnly,
                PrivateMessageRule::Nobody => PmRule::Nobody,
            };
            saved.private_messages.allow_current_channel = settings.allow_current_channel;
            engine.update_settings(saved).await?;
            Ok(())
        })
        .await
    }

    /// The people the user blocked with the identity of the current connection (with every
    /// identity while not connected).
    pub async fn blocked_people(&self) -> Result<Vec<BlockedPerson>, BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move {
            let own = lock(&connection)
                .own_user()
                .and_then(|user| user.cert_hash.clone());
            Ok(super::blocked_people(
                &engine.blocked_users()?,
                own.as_deref(),
            ))
        })
        .await
    }

    /// Blocks a person on the server. Nobody is told. Their private messages and chat requests
    /// are dropped, their channel messages arrive marked `blocked` without unread count or
    /// notification, and their voice is muted. The list arrives as `BlockedPeopleChanged`.
    pub async fn block_person(&self, session: u32) -> Result<(), BridgeError> {
        let (engine, connection, _) = self.chat_parts();
        self.run(async move { Ok(engine.block_session(server_of(&connection)?, session)?) })
            .await
    }

    /// Lifts a block by the `id` of its list entry.
    pub async fn unblock_person(&self, id: i64) -> Result<(), BridgeError> {
        let engine = self.engine.clone();
        self.run(async move { Ok(engine.unblock(id)?) }).await
    }
}

impl MumcordEngine {
    fn chat_parts(&self) -> (Engine, SharedConnection, SharedChat) {
        (
            self.engine.clone(),
            self.connection.clone(),
            self.chat.clone(),
        )
    }
}
