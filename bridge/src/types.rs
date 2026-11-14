// SPDX-License-Identifier: AGPL-3.0-only

//! Records and enums of the bridge interface. They are the bridge's own types; the engine's
//! types are mapped onto them in `convert`.

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
    /// A whisper or shout is being held (`start_whisper`); normal talking is paused meanwhile.
    pub whispering: bool,
}
