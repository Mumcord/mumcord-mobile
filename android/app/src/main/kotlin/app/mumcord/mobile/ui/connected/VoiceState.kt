// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.ServerModel
import app.mumcord.mobile.session.SessionState

/**
 * What the mute button shows and does. The server's mute comes first, because the user cannot
 * lift it; then the missing microphone, because unmuting would not help.
 */
enum class MuteControl {
    Unmuted,
    Muted,

    /** Muted by the server or not allowed to speak in this channel; the button is disabled. */
    ServerMuted,

    /** The microphone may not be used; the button leads to the permission. */
    NoMicrophone,
}

/** A person of the user's own channel in the full voice view. [name] is untrusted text. */
data class VoiceTile(
    val session: UInt,
    val name: String,
    val isSelf: Boolean,
    val talking: Boolean,
    val muted: Boolean,
)

/** The whisper bar. [name] is the person or channel it goes to; untrusted text. */
data class WhisperBarState(val name: String, val shout: Boolean, val held: Boolean)

/** The voice bar and the full voice view as a value. */
data class VoiceUiState(
    val channelName: String,
    val serverName: String,
    /** False while the connection is being restored. */
    val connected: Boolean,
    /** The user is heard right now. */
    val talking: Boolean,
    val mute: MuteControl,
    val deafened: Boolean,
    val talkMode: TalkMode,
    /** The hold-to-talk button is down. */
    val talkHeld: Boolean,
    /** The full voice view covers the screen. */
    val open: Boolean,
    /** The people of the user's channel in rows, see [voiceTileRows]. */
    val tileRows: List<List<VoiceTile>>,
    val whisper: WhisperBarState?,
)

/** How many people stand next to each other in the full voice view. */
const val VOICE_TILES_PER_ROW = 3

fun muteControl(self: Person, microphone: MicrophoneNotice): MuteControl = when {
    self.serverMuted || self.suppressed -> MuteControl.ServerMuted
    microphone == MicrophoneNotice.Denied || microphone == MicrophoneNotice.Blocked -> MuteControl.NoMicrophone
    self.selfMuted || self.selfDeafened -> MuteControl.Muted
    else -> MuteControl.Unmuted
}

/**
 * The people of one channel in rows of [VOICE_TILES_PER_ROW]. They keep the order in which
 * they connected, so nobody jumps when somebody starts talking; the user comes last.
 */
fun voiceTileRows(people: Collection<Person>, channelId: UInt): List<List<VoiceTile>> =
    people
        .filter { it.channelId == channelId }
        .sortedWith(compareBy<Person> { it.isSelf }.thenBy { it.session })
        .map {
            VoiceTile(
                session = it.session,
                name = it.name,
                isSelf = it.isSelf,
                talking = it.talking,
                muted = it.selfMuted || it.selfDeafened || it.serverMuted || it.suppressed,
            )
        }
        .chunked(VOICE_TILES_PER_ROW)

/** The name the whisper bar shows, or null when the person or channel is gone. */
fun ServerModel.whisperName(target: WhisperTarget): String? = when (target) {
    is WhisperTarget.Person -> people[target.session]?.name
    is WhisperTarget.Channel -> channels[target.channelId]?.name
}

fun voiceUiState(
    session: SessionState,
    server: ServerModel,
    local: ConnectedLocal,
    serverName: String,
    microphone: MicrophoneNotice,
): VoiceUiState? {
    val self = server.people[server.selfSession] ?: return null
    val whisperName = local.whisperTarget?.let(server::whisperName)
    return VoiceUiState(
        channelName = server.channels[self.channelId]?.name.orEmpty(),
        serverName = serverName,
        connected = session.phase == ConnectionPhase.Connected,
        talking = self.talking,
        mute = muteControl(self, microphone),
        deafened = self.selfDeafened || self.serverDeafened,
        talkMode = session.talkMode,
        talkHeld = local.talkHeld,
        open = local.voiceOpen,
        tileRows = voiceTileRows(server.people.values, self.channelId),
        whisper = whisperName?.let {
            WhisperBarState(name = it, shout = local.whisperTarget is WhisperTarget.Channel, held = local.whisperHeld)
        },
    )
}
