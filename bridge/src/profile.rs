// SPDX-License-Identifier: AGPL-3.0-only

//! Profiles: the comment and avatar of a person on the connected server, and editing the own
//! ones. Mumble keeps both per server, so editing needs a connection; the engine checks the
//! server's size limits and sanitises what it receives.

use std::path::PathBuf;

use mumcord_engine::ProfileView;

use crate::chat;
use crate::engine::{MumcordEngine, connected_server};
use crate::error::BridgeError;
use crate::events::{BridgeEvent, lock};
use crate::identities::PrivateFile;
use crate::types::{Profile, ProfileImage};

/// The file name an avatar passes through; the engine reads it by its content.
const AVATAR_FILE: &str = "avatar";

pub(crate) fn profile(view: ProfileView) -> Profile {
    let html = view.comment.unwrap_or_default();
    let (comment, comment_images) = if html.trim().is_empty() {
        (None, Vec::new())
    } else {
        let (content, images) = chat::comment_content(&html);
        let images = images
            .into_iter()
            .map(|(image_id, image)| ProfileImage { image_id, image })
            .collect();
        (Some(content), images)
    };
    Profile {
        comment,
        comment_text: mumcord_engine::plain_text::plain_lines(&html),
        comment_images,
        avatar: view.avatar.as_deref().and_then(chat::avatar_image),
    }
}

#[uniffi::export]
impl MumcordEngine {
    /// The comment and avatar of a person on the connected server, the user's own included.
    /// Fetched from the server when it has only announced them, which can take a moment. Fails
    /// with `NotFound` when the person is gone.
    pub async fn person_profile(&self, session: u32) -> Result<Profile, BridgeError> {
        let (engine, connection) = (self.engine.clone(), self.connection.clone());
        self.run(async move {
            let server = connected_server(&connection)?;
            Ok(profile(engine.person_profile(server, session).await?))
        })
        .await
    }

    /// Saves the user's comment on the connected server; blank removes it. `text` is plain
    /// text (line breaks kept); the server's limit is [`MumcordEngine::chat_limits`]'s `text`
    /// and a longer comment fails with `InvalidInput`. The change arrives as
    /// [`BridgeEvent::ProfileChanged`] of the user's own session.
    pub async fn set_own_comment(&self, text: String) -> Result<(), BridgeError> {
        let (engine, connection, events) = (
            self.engine.clone(),
            self.connection.clone(),
            self.saved.events.clone(),
        );
        self.run(async move {
            let server = connected_server(&connection)?;
            engine
                .set_own_comment(server, chat::comment_html(&text))
                .await?;
            own_profile_changed(&connection, &events);
            Ok(())
        })
        .await
    }

    /// Sets the user's avatar on the connected server from the bytes of an image (png, jpeg,
    /// gif or webp); `None` removes it. The engine scales it to the server's image limit and
    /// sends it again on later connections to this server when the server does not keep it.
    /// Fails with `InvalidInput` for bytes that are no image. The change arrives as
    /// [`BridgeEvent::ProfileChanged`] of the user's own session.
    pub async fn set_own_avatar(&self, image: Option<Vec<u8>>) -> Result<(), BridgeError> {
        let (engine, connection, events, data_dir) = (
            self.engine.clone(),
            self.connection.clone(),
            self.saved.events.clone(),
            self.data_dir.clone(),
        );
        self.run(async move {
            let server = connected_server(&connection)?;
            // The engine reads avatars from a file only.
            let file = image
                .map(|bytes| PrivateFile::create(&data_dir, AVATAR_FILE, &bytes))
                .transpose()?;
            let path: Option<PathBuf> = file.as_ref().map(|file| file.path.clone());
            let set = engine.set_own_avatar(server, path).await;
            drop(file);
            set?;
            own_profile_changed(&connection, &events);
            Ok(())
        })
        .await
    }
}

/// Reports that the user's own profile changed. The engine's person update does not tell a
/// changed comment from an unchanged one, so the command that changed it says so.
fn own_profile_changed(
    connection: &crate::events::SharedConnection,
    events: &tokio::sync::mpsc::UnboundedSender<BridgeEvent>,
) {
    let own = lock(connection).own_user().map(|user| user.session);
    if let Some(session) = own {
        // The event thread is gone only while the engine object is being released.
        let _ = events.send(BridgeEvent::ProfileChanged { session });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_comment_becomes_safe_content_and_plain_text() {
        let shown = profile(ProfileView {
            comment: Some("Playing <b>late</b><br>Ask me".into()),
            avatar: None,
            note: String::new(),
        });
        let comment = shown.comment.expect("comment");
        assert!(!comment.blocks.is_empty());
        assert_eq!(shown.comment_text, "Playing late\nAsk me");
        assert!(shown.comment_images.is_empty());
        assert_eq!(shown.avatar, None);
    }

    #[test]
    fn no_comment_and_an_unreadable_avatar_are_left_out() {
        let shown = profile(ProfileView {
            comment: Some("  ".into()),
            avatar: Some("data:image/png;base64,bm90IGFuIGltYWdl".into()),
            note: "a private note".into(),
        });
        assert_eq!(shown.comment, None);
        assert_eq!(shown.comment_text, "");
        assert_eq!(shown.avatar, None);
    }
}
