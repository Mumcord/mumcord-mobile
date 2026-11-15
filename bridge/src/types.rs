// SPDX-License-Identifier: AGPL-3.0-only

//! Records and enums of the bridge interface. They are the bridge's own types; the engine's
//! types are mapped onto them in `convert`.

use crate::chat::{ChatId, ChatUnread, ImageData, MessageContent};
use crate::error::BridgeError;

/// Start-up settings of [`MumcordEngine`](crate::MumcordEngine).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EngineConfig {
    /// App-private folder for the engine's database and files. Created when missing.
    pub data_dir: String,
    /// PCM layout of the audio the app moves through the C functions of
    /// `bridge/include/mumcord_audio.h`. `None` is 48 kHz mono in both directions.
    #[uniffi(default = None)]
    pub audio: Option<AudioConfig>,
}

/// PCM layout of the app's microphone and speaker streams, fixed for the engine's lifetime.
/// Interleaved samples; 48 kHz needs no resampling. Captured channels are mixed down to mono.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct AudioConfig {
    /// Frames per second of the microphone stream, 8 000 to 192 000.
    pub capture_rate: u32,
    pub capture_channels: u16,
    /// Frames per second of the speaker stream, 8 000 to 192 000.
    pub playback_rate: u32,
    pub playback_channels: u16,
}

/// Handles for the C functions of `bridge/include/mumcord_audio.h`. Valid until the process
/// ends; once the engine object is released the functions do nothing with them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct AudioEndpoints {
    /// For `mumcord_audio_capture_push_*` (microphone callback).
    pub capture: u64,
    /// For `mumcord_audio_playback_pull_*` (speaker callback).
    pub playback: u64,
}

/// Which platform audio streams the engine needs right now. The app opens and closes its audio
/// session along it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct AudioWanted {
    /// The microphone: voice is running and the user may be heard (not muted or deafened, by
    /// themselves or the server). In push-to-talk mode it stays wanted between presses, so the
    /// start of speech is not cut off.
    pub capture: bool,
    /// The speaker: voice is running on the connection.
    pub playback: bool,
}

/// Who cancels the echo of the speaker in the microphone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EchoCancellation {
    /// The voice processing of the phone system; there is nothing to switch.
    System,
    /// The engine's own canceller.
    Engine,
}

/// The voice settings. Talk mode, noise suppression and automatic gain are saved; the bitrate
/// limit lasts until the engine object is released.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct VoiceSettings {
    pub talk_mode: TalkMode,
    pub noise_suppression: bool,
    pub auto_gain: bool,
    pub echo_cancellation: EchoCancellation,
    /// Limit set with `set_bitrate_limit_kbps`; `None` for no limit.
    pub bitrate_limit_kbps: Option<u32>,
}

/// Who hears the user while a whisper or shout is held.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WhisperTarget {
    /// Whisper to one person ([`Person::session`]).
    Person { session: u32 },
    /// Shout to everyone in a channel ([`Channel::id`]).
    Channel { channel_id: u32 },
}

/// A saved server of the server list. Every saved server is a favourite. The password is never
/// part of it; `has_password` says whether one is kept in the secure storage.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SavedServer {
    pub id: i64,
    pub name: String,
    /// Host name or IP address.
    pub address: String,
    pub port: u16,
    pub username: String,
    pub has_password: bool,
    /// Identity to connect with; `None` uses the default identity.
    pub identity_id: Option<i64>,
}

/// An identity: the certificate the user is known by on servers. The certificate and its key
/// stay in the secure storage and never cross the bridge.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Identity {
    pub id: i64,
    /// Local label the user chose; the certificate is not changed by it.
    pub label: String,
    /// Common name inside the certificate.
    pub common_name: String,
    /// Certificate hash, 40 hex digits: what a server knows the user by.
    pub fingerprint: String,
    /// When the identity was added on this phone, Unix time in milliseconds.
    pub created_ms: i64,
    /// Used by every server that names no identity of its own.
    pub is_default: bool,
    /// The saved servers that name this identity ([`SavedServer::identity_id`]).
    pub server_ids: Vec<i64>,
}

/// Where voice plays when no headset is connected.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DefaultOutput {
    /// Not chosen yet: the app asks once and saves the answer.
    Ask,
    Speaker,
    Earpiece,
}

/// The settings only the phone apps have. Saved in the engine's database; the engine attaches
/// no behaviour to them, the app does.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct MobileSettings {
    /// The user picked a talk mode on the first-start screen. Set by `set_talk_mode` only;
    /// `set_mobile_settings` never clears it.
    pub talk_mode_chosen: bool,
    pub default_output: DefaultOutput,
    /// On mobile data, streams start by themselves.
    pub mobile_data_autoplay_streams: bool,
    /// On mobile data, images and GIFs are loaded.
    pub mobile_data_load_media: bool,
    /// On mobile data, voice uses a reduced quality.
    pub mobile_data_reduced_voice: bool,
    /// Disconnect after 30 minutes alone in a channel.
    pub idle_disconnect: bool,
    /// Notifications show the sender without the message text.
    pub notify_sender_only: bool,
    /// Android: look for a newer version once a day.
    pub update_check: bool,
}

/// The settings the phone shares with the desktop's settings model: chat, notifications and
/// language. Saved in the engine's database.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppSettings {
    /// App language as an ISO 639-1 code of one of the 24 languages; `None` follows the phone.
    pub language: Option<String>,
    /// Show link previews in chat; the engine then fetches linked pages itself.
    pub link_previews: bool,
    /// Chat history older than this many days is deleted; `None` keeps it.
    pub history_max_age_days: Option<u32>,
    /// Private messages raise a system notification ([`BridgeEvent::ChatNotification`](crate::BridgeEvent::ChatNotification)).
    pub notify_private_messages: bool,
    /// Mentions raise a system notification.
    pub notify_mentions: bool,
}

/// What a person's profile shows. The comment is the server's text, sanitised by the engine and
/// reduced to the safe content model; the avatar is image bytes as the server holds them.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct Profile {
    pub comment: Option<MessageContent>,
    /// The comment as plain text, for the edit field (line breaks kept, formatting dropped).
    pub comment_text: String,
    /// The images embedded in the comment, by the `image_id` of its `ImageRef::Embedded`.
    pub comment_images: Vec<ProfileImage>,
    pub avatar: Option<ImageData>,
}

/// An image embedded in a profile comment.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProfileImage {
    pub image_id: String,
    pub image: ImageData,
}

/// What the ban dialog needs to know about a person before banning them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BanTarget {
    /// The address the server reported (only to admins); `None` when it did not. Personal data:
    /// shown to the admin, never logged.
    pub address: Option<String>,
    /// How long the person has been connected, when the server reported it.
    pub online_secs: Option<u64>,
    /// The server lets the user read its ban list: a ban with its own length and targets is
    /// possible. Otherwise only the server's default ban is (`BanInput::quick`).
    pub ban_list_readable: bool,
    /// The person has a certificate, so "Ban by certificate" can be on.
    pub has_certificate: bool,
    pub can_kick: bool,
    pub can_ban: bool,
}

/// A ban as the dialog sends it (Spec 10, FR-10.12, FR-10.13).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BanInput {
    /// Shown to the person; `None` or blank for none.
    pub reason: Option<String>,
    /// Seconds; 0 is permanent. Ignored by the quick ban, which uses the server's own length.
    pub duration_secs: u32,
    pub by_certificate: bool,
    /// Bans the person's single address (/32 or /128).
    pub by_address: bool,
    /// After saving the ban the person is kicked with the same reason (needs the kick right).
    pub remove_now: bool,
    /// "Save mine anyway" after [`BanResult::Conflict`].
    pub force: bool,
    /// The server's own "remove with ban" (default length, certificate and address), for
    /// servers whose ban list cannot be read ([`BanTarget::ban_list_readable`] is false).
    pub quick: bool,
}

/// What a ban produced.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BanResult {
    /// The entry is on the server's ban list; `removed` when the server confirmed the person
    /// was taken off (otherwise they are stopped on their next connect).
    Saved { removed: bool },
    /// The server's own ban with its default length.
    QuickBanned,
    /// The entry changed on the server meanwhile (another admin): ask, then send again with
    /// `force` or give up.
    Conflict,
}

/// What an activity entry reports.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ActivityKind {
    /// The user connected; the first entry of every connection.
    Connected,
    /// A person came onto the server, into `channel_id`.
    Joined,
    /// A person left the server. Kicks and bans look the same for now.
    Left,
    /// A person moved to `channel_id`.
    Moved,
    StreamStarted,
    StreamStopped,
}

/// One entry of the activity log (Spec 11): kept for the connection only, 200 at most.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ActivityEntry {
    /// Rises with every entry of the bridge's lifetime.
    pub id: u64,
    pub kind: ActivityKind,
    pub session: u32,
    /// The person's name as the server sent it (untrusted); the last known one for `Left`.
    pub name: String,
    pub is_self: bool,
    /// The channel of a join, a move or a stream start, while it is known.
    pub channel_id: Option<u32>,
    pub channel_name: Option<String>,
    /// When the bridge saw it, in milliseconds since the Unix epoch.
    pub at_ms: i64,
}

/// A server of the public server list. Everything in it is untrusted text.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PublicServer {
    pub name: String,
    /// Host name or IP address.
    pub host: String,
    pub port: u16,
    pub country: String,
    /// Two upper-case letters, or empty.
    pub country_code: String,
}

/// Which part of the public server list to return.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PublicServerQuery {
    /// Kept when it is part of the name, the host or the country, whatever the case. Empty
    /// keeps everything.
    pub search: String,
    /// Only servers of this country ([`PublicServer::country_code`]); `None` for all.
    pub country_code: Option<String>,
    /// How many matches to skip.
    pub offset: u32,
    /// Most servers to return; at most 100, more is cut to 100.
    pub limit: u32,
    /// Download the list again instead of using the one fetched before.
    pub refresh: bool,
}

/// One page of the public server list.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PublicServerPage {
    pub servers: Vec<PublicServer>,
    /// How many servers match the search and the country in all.
    pub total: u32,
}

/// What happens to the saved server password.
#[derive(Clone, PartialEq, Eq, uniffi::Enum)]
pub enum PasswordInput {
    /// Leave the saved password as it is (none for a new server).
    Keep,
    Clear,
    /// Kept in the secure storage, never in the database.
    Set {
        value: String,
    },
}

/// The fields of the add and edit server forms.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ServerInput {
    /// Empty uses the address as the name.
    pub name: String,
    pub address: String,
    pub port: u16,
    pub username: String,
    pub password: PasswordInput,
    /// `None` uses the default identity.
    pub identity_id: Option<i64>,
}

/// What a server answered to a ping, without connecting.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ServerPing {
    pub latency_ms: u32,
    pub people_online: u32,
    pub max_people: u32,
}

/// The target of a `mumble://` link. Shown to the user for confirmation; nothing connects by
/// parsing. A password inside the link is not taken over.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MumbleLink {
    pub address: String,
    pub port: u16,
    pub username: Option<String>,
    /// Channel names from the root down; empty for no channel.
    pub channel_path: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TalkMode {
    VoiceActivity,
    PushToTalk,
}

/// The server to connect to and who to connect as.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConnectRequest {
    /// Host name or IP address.
    pub address: String,
    pub port: u16,
    pub username: String,
    /// Server password for this connection only; it is not saved.
    pub password: Option<String>,
}

/// Where the connection stands.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum ConnectionState {
    /// `error` says why the last connection ended, unless it ended on request.
    Disconnected {
        error: Option<BridgeError>,
    },
    Connecting,
    Connected,
    Reconnecting {
        attempt: u32,
        max_attempts: u32,
        next_retry_secs: u32,
    },
}

/// The question whether to trust a server certificate. Connecting waits for the answer
/// ([`MumcordEngine::answer_certificate_prompt`](crate::MumcordEngine::answer_certificate_prompt)).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CertificatePrompt {
    /// SHA-256 fingerprint of the certificate the server presented, as shown to the user.
    pub fingerprint: String,
    /// Fingerprint trusted so far. Set when the certificate changed (strong warning), `None` for
    /// a server seen for the first time.
    pub previous_fingerprint: Option<String>,
}

/// What the server allows the user in a channel, as far as it has answered. Moderation entries
/// are shown only where the matching right is `true`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ChannelPermissions {
    pub speak: bool,
    pub whisper: bool,
    pub text_message: bool,
    /// Move other people out of this channel.
    pub move_people: bool,
    /// Server-mute and deafen people here.
    pub mute_deafen: bool,
    pub kick: bool,
    pub ban: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Channel {
    pub id: u32,
    /// `None` for the root channel.
    pub parent_id: Option<u32>,
    /// Chosen by the server's users: untrusted text.
    pub name: String,
    /// Sort position among the channels of the same parent, then by name.
    pub position: i32,
    pub has_description: bool,
    /// User limit of the channel; `None` when it has none.
    pub max_people: Option<u32>,
    pub temporary: bool,
    /// Entering needs a password (access token) or a group membership: the lock icon.
    pub restricted: bool,
    /// The server lets the user in. A restricted channel with `can_enter = false` needs a
    /// password to join.
    pub can_enter: bool,
    pub permissions: ChannelPermissions,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Person {
    /// Id of this person's connection; it changes with every connect.
    pub session: u32,
    pub channel_id: u32,
    /// Chosen by the person: untrusted text.
    pub name: String,
    /// The user themselves.
    pub is_self: bool,
    pub talking: bool,
    pub self_muted: bool,
    pub self_deafened: bool,
    /// Muted by the server or an admin.
    pub server_muted: bool,
    pub server_deafened: bool,
    /// May not speak in the current channel.
    pub suppressed: bool,
    /// Volume for me in percent (0–200); it never leaves the phone.
    pub local_volume: u16,
    /// Muted for me; it never leaves the phone.
    pub locally_muted: bool,
    /// Shares their screen. Known for people in the user's own channel only.
    pub streaming: bool,
    /// Has their camera on. Known for people in the user's own channel only.
    pub camera: bool,
    /// Blocked by the user (`block_person`); the entry of `blocked_people` with this
    /// [`Person::session`] lifts it.
    pub blocked: bool,
    /// Their app understands chat requests (`send_chat_request`); `false` for other clients.
    pub supports_chat_requests: bool,
}

/// Where joining a channel that needs a password stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChannelPasswordState {
    /// The join was refused for a missing permission to enter: ask for the password.
    Required,
    /// The server refused the join again with the typed password.
    Wrong,
    /// The attempt is over: the user is in the channel, or the join was refused for another
    /// reason (reported as `ActionDenied`).
    Ended,
}

/// Channels and people of the connected server. Member counts and the own channel follow from
/// [`Person::channel_id`] and `self_session`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ServerState {
    /// The [`Person::session`] of the user themselves.
    pub self_session: u32,
    pub channels: Vec<Channel>,
    pub people: Vec<Person>,
}

/// The full current state.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BridgeSnapshot {
    /// The server list in its saved order.
    pub servers: Vec<SavedServer>,
    pub connection: ConnectionState,
    /// The [`SavedServer::id`] of the current connection, from connecting until it has ended.
    pub connected_server_id: Option<i64>,
    /// The certificate question that is waiting for an answer, if any.
    pub certificate_prompt: Option<CertificatePrompt>,
    /// `None` until the server has sent its channels and people.
    pub server: Option<ServerState>,
    /// The platform audio streams the engine needs right now.
    pub audio: AudioWanted,
    /// The chats with unread messages on the current connection.
    pub chat_unread: Vec<ChatUnread>,
    /// The chat on screen, as the app last told with `set_open_chat`.
    pub open_chat: Option<ChatId>,
    /// A whisper or shout is being held (`start_whisper`); normal talking is paused meanwhile.
    pub whispering: bool,
    /// Every identity, the default one first.
    pub identities: Vec<Identity>,
    pub mobile_settings: MobileSettings,
}
