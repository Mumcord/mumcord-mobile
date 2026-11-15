// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.AudioWanted
import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.Stream
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeSettings

/** Everything the screens need to know about the one connection, as an immutable value. */
data class SessionState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val certificatePrompt: CertificatePromptState? = null,
    val server: ServerModel? = null,
    /** The server list in its saved order; empty until [serversLoaded]. */
    val servers: List<SavedServer> = emptyList(),
    val serversLoaded: Boolean = false,
    /**
     * The saved server of the current connection or attempt. It stays set after a failure, so
     * the error can name the server and "Try again" knows where to connect.
     */
    val activeServerId: Long? = null,
    /** The channel whose password the server asks for, until it is typed or given up. */
    val channelPassword: ChannelPasswordPrompt? = null,
    /** The latest thing the server refused, until it is put away. */
    val denied: DeniedAction? = null,
    /** Which of the phone's audio streams the engine wants to run right now. */
    val audio: AudioWanted = AudioWanted(capture = false, playback = false),
    /** How the user talks; voice activity until the saved choice has been read. */
    val talkMode: TalkMode = TalkMode.VOICE_ACTIVITY,
    /**
     * How often the engine ended a whisper by itself (the person left, the connection dropped).
     * The whisper bar closes each time it grows.
     */
    val whispersEnded: Int = 0,
    /** The identities on the phone, the default one first; null until they were read. */
    val identities: List<Identity>? = null,
    /** The phone settings as the engine keeps them; null until they have been read. */
    val mobileSettings: MobileSettings? = null,
    /** The settings shared with the desktop's settings model; null until they have been read. */
    val appSettings: AppSettings? = null,
    /** The activity log of the connection (Spec 11), newest first, at most [MAX_ACTIVITY]. */
    val activity: List<ActivityEntry> = emptyList(),
    /** Things the app cannot show, newest last; see [withHint]. */
    val hints: List<UnsupportedHint> = emptyList(),
    /** The appearance mode and the themes; null until they have been read. */
    val themes: ThemeSettings? = null,
    /** The themes that come with the app, Mumcord first; read with [themes]. */
    val builtInThemes: List<Theme> = emptyList(),
    /**
     * How often the user's own comment or avatar may have changed on the shown server; the
     * profile is read again each time it grows.
     */
    val ownProfileChanges: Int = 0,
    /** The screen shares and cameras of the people in the user's channel, as the bridge last sent them. */
    val streams: List<Stream> = emptyList(),
    /** The engine waits for the answer to "Allow direct connections?" (a stream needs it). */
    val directConnectionsQuestion: Boolean = false,
) {
    /** True once the server's channels are known and the connection is still alive. */
    val showsServer: Boolean
        get() = server != null &&
            (phase == ConnectionPhase.Connected || phase is ConnectionPhase.Reconnecting)

    /** True while a connection exists or is being made, so no new one may be started. */
    val isBusy: Boolean
        get() = phase != ConnectionPhase.Idle && phase !is ConnectionPhase.Failed
}

sealed interface ConnectionPhase {
    data object Idle : ConnectionPhase

    data object Connecting : ConnectionPhase

    data object Connected : ConnectionPhase

    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionPhase

    data class Failed(val error: ConnectionError) : ConnectionPhase
}

/**
 * The certificate question waiting for an answer. [previousFingerprint] is set when a server
 * that was trusted before now presents a different certificate.
 */
data class CertificatePromptState(
    val fingerprint: String,
    val previousFingerprint: String?,
) {
    val isChanged: Boolean
        get() = previousFingerprint != null
}

/**
 * The server's channels and people as the engine reported them, plus the tree derived from
 * them. The tree is built when the value is created, which happens off the main thread.
 */
data class ServerModel(
    val selfSession: UInt,
    val channels: Map<UInt, Channel>,
    val people: Map<UInt, Person>,
) {
    val root: ChannelNode? = buildChannelTree(channels.values, people.values)

    /** The channel the user is in, or null while the server has not said. */
    val selfChannelId: UInt?
        get() = people[selfSession]?.channelId

    /**
     * The names from below the root channel down to the user's own channel, as a connection
     * takes them to enter that channel; empty in the root channel or while it is unknown.
     */
    fun selfChannelPath(): List<String> {
        val path = ArrayDeque<String>()
        var channel = selfChannelId?.let(channels::get)
        // The length bounds the walk should the server ever send a loop.
        repeat(channels.size) {
            val current = channel ?: return@repeat
            val parent = current.parentId ?: return path
            path.addFirst(current.name)
            channel = channels[parent]
        }
        return path
    }
}

/** [wrong] is true after the server refused the typed password, so the sheet can say so. */
data class ChannelPasswordPrompt(val channelId: UInt, val wrong: Boolean)

/** A refusal by the server. [detail] is the server's own text and therefore untrusted. */
data class DeniedAction(val channelId: UInt?, val detail: String)

enum class UnsupportedKind { FileTransfer, WatchTogether, PluginContent }

/**
 * Something a person did that this app cannot show (FR-5.14). [personName] is untrusted text.
 * [id] tells two hints of the same kind by the same person apart.
 */
data class UnsupportedHint(val id: Long, val personName: String, val kind: UnsupportedKind)

/** As many entries as the bridge keeps for a connection. */
const val MAX_ACTIVITY = 200

/** How many hints stay visible; older ones make room so the list stays calm. */
const val MAX_HINTS = 3

fun SessionState.withHint(hint: UnsupportedHint): SessionState =
    copy(hints = (hints + hint).takeLast(MAX_HINTS))
