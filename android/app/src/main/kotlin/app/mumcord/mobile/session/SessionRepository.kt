// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.AudioEndpoints
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.bridge.parseMumbleLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The result of a command that the screen which started it reports itself (a form, a sheet). */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val error: ConnectionError) : Outcome<Nothing>
}

/**
 * Owns the server list and the one connection. Screens read [state] and call the commands
 * through a view model; the engine itself is only reached through [EngineCalls].
 *
 * @param scope lives as long as the process; its dispatcher folds the events, so it must not
 *   be the main thread.
 * @param engineDispatcher where the engine is created and every waiting command runs; see
 *   [EngineCalls].
 * @param log receives short technical messages; never names, addresses, passwords or other
 *   personal data.
 * @param mediaPolicy decides whether images and GIFs from the web are loaded; see [chat].
 * @param linkParser reads a `mumble://` link; a seam because the real one needs the native
 *   library.
 * @param checkThreads fail a command that is given from inside the engine's listener; off in
 *   release builds.
 */
class SessionRepository(
    engineFactory: EngineFactory,
    private val scope: CoroutineScope,
    engineDispatcher: CoroutineDispatcher,
    private val log: (String) -> Unit,
    private val linkParser: (String) -> MumbleLink = ::parseMumbleLink,
    private val mediaPolicy: MediaPolicy = MediaPolicy { true },
    checkThreads: Boolean = true,
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    // The engine calls back on its own thread, which must never wait: the callback only queues
    // the event, and a single reader keeps them in order.
    private val events = Channel<BridgeEvent>(Channel.UNLIMITED)

    private val engine = EngineCalls(
        factory = engineFactory,
        dispatcher = engineDispatcher,
        listener = object : EngineListener {
            override fun onEvent(event: BridgeEvent) {
                when (event) {
                    is BridgeEvent.InputLevelChanged -> mutableInputLevel.value = event.percent.toInt()
                    is BridgeEvent.ChatMessageReceived,
                    is BridgeEvent.ChatUnreadChanged,
                    -> chatEvents.trySend(event)
                    else -> events.trySend(event)
                }
            }
        },
        checkThreads = checkThreads,
    )

    private val mutableInputLevel = MutableStateFlow(0)

    /** The microphone level for the level meter, 0 to 100, up to 20 times a second. */
    val inputLevel: StateFlow<Int> = mutableInputLevel.asStateFlow()

    /**
     * Chat traffic, in order, for the one reader that keeps the chat ([chat]). It is kept out
     * of [state] so that a busy chat does not redraw the channel list.
     */
    internal val chatEvents: Channel<BridgeEvent> = Channel(Channel.UNLIMITED)

    /** The chat of the connection. What arrives before it is first asked for waits in the queue. */
    val chat: ChatRepository by lazy { ChatRepository(this, scope, mediaPolicy) }

    init {
        scope.launch {
            for (event in events) {
                mutableState.update { it.fold(event) }
            }
        }
    }

    /** Reads the saved servers once; later changes arrive as events. A failure can be retried. */
    suspend fun loadServers() {
        if (mutableState.value.serversLoaded) return
        when (val servers = call("load servers") { it.snapshot().servers to it.talkMode() }) {
            // An event that arrived in between is newer than the snapshot.
            is Outcome.Ok -> mutableState.update {
                val (saved, talkMode) = servers.value
                if (it.serversLoaded) it.copy(talkMode = talkMode) else it.copy(servers = saved, serversLoaded = true, talkMode = talkMode)
            }
            is Outcome.Failed -> mutableState.update { it.copy(phase = ConnectionPhase.Failed(servers.error)) }
        }
    }

    suspend fun addServer(input: ServerInput): Outcome<SavedServer> =
        call("add server") { it.addServer(input) }

    suspend fun updateServer(serverId: Long, input: ServerInput): Outcome<SavedServer> =
        call("update server") { it.updateServer(serverId, input) }

    suspend fun removeServer(serverId: Long): Outcome<Unit> =
        call("remove server") { it.removeServer(serverId) }

    /** People online and latency of a saved server, or null when it does not answer. */
    suspend fun pingServer(serverId: Long, refresh: Boolean): ServerPing? =
        when (val ping = call("ping server") { it.pingServer(serverId, refresh) }) {
            is Outcome.Ok -> ping.value
            is Outcome.Failed -> null
        }

    /** Saves the talk mode; the engine applies it at once. */
    suspend fun setTalkMode(mode: TalkMode): Outcome<Unit> =
        call("set talk mode") { it.setTalkMode(mode) }.also { saved ->
            if (saved is Outcome.Ok) mutableState.update { it.copy(talkMode = mode) }
        }

    /** The target of a `mumble://` link. Nothing is saved or connected by reading it. */
    fun parseLink(url: String): Outcome<MumbleLink> =
        try {
            Outcome.Ok(linkParser(url))
        } catch (e: BridgeException) {
            failed("parse link", e.toConnectionError(), e)
        } catch (e: LinkageError) {
            failed("parse link", ConnectionError.Internal, e)
        }

    // Follow-up task 2.7: before connecting to a server on the local network (a private
    // address), ask for the ACCESS_LOCAL_NETWORK permission with an explanation. Without it
    // Android 17 drops the connection silently and the attempt ends as ServerUnreachable.
    /**
     * Connects to a saved server. [password] replaces the saved one for this attempt only;
     * a non-empty [channelPath] (from a link) is joined after connecting. The caller makes sure
     * that no other connection is alive (one server at a time).
     */
    suspend fun connectServer(serverId: Long, password: String?, channelPath: List<String>) {
        // Shown at once: creating the engine on first use takes a moment.
        mutableState.update {
            it.copy(
                phase = ConnectionPhase.Connecting,
                certificatePrompt = null,
                server = null,
                activeServerId = serverId,
            )
        }
        val result = call("connect") { it.connectServer(serverId, password, channelPath) }
        if (result is Outcome.Failed) {
            mutableState.update {
                it.copy(phase = ConnectionPhase.Failed(result.error), certificatePrompt = null, server = null)
            }
        }
    }

    suspend fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        mutableState.update { it.copy(certificatePrompt = null) }
        val result = call("answer certificate prompt") { it.answerCertificatePrompt(fingerprint, trust) }
        if (result is Outcome.Failed) {
            mutableState.update { it.copy(phase = ConnectionPhase.Failed(result.error), server = null) }
        }
    }

    suspend fun disconnect() {
        val result = call("disconnect") { it.disconnect() }
        if (result is Outcome.Failed) {
            mutableState.update {
                it.copy(phase = ConnectionPhase.Failed(result.error), certificatePrompt = null, server = null)
            }
        }
    }

    /** Asks to enter a channel; a refusal arrives as a password question or a denial. */
    suspend fun joinChannel(channelId: UInt, password: String?) {
        mutableState.update { it.copy(denied = null) }
        report(call("join channel") { it.joinChannel(channelId, password) })
    }

    /** How loud a person is for the user only, 0 to 200 percent. */
    suspend fun setPersonVolume(session: UInt, percent: Int) {
        report(call("set person volume") { it.setPersonVolume(session, percent.coerceIn(0, 200).toUShort()) })
    }

    suspend fun setPersonLocalMute(session: UInt, muted: Boolean) {
        report(call("set person mute") { it.setPersonLocalMute(session, muted) })
    }

    /** Mutes or unmutes the user; the new state arrives as a change of their own entry. */
    suspend fun setSelfMute(muted: Boolean) {
        report(call("set self mute") { it.setSelfMute(muted) })
    }

    /** Deafens the user, which also mutes them, or ends it. */
    suspend fun setSelfDeaf(deafened: Boolean) {
        report(call("set self deaf") { it.setSelfDeaf(deafened) })
    }

    /**
     * The hold-to-talk button went down or up. Never waits, so a release always gets through;
     * without an engine there is nothing to hold.
     */
    fun setPushToTalkPressed(pressed: Boolean) {
        engine.now { it.setPushToTalkPressed(pressed) }
    }

    /**
     * Sends the user's voice to [target] instead of their channel until [stopWhisper]. False
     * when it was refused; the reason is then shown like any other refusal.
     */
    suspend fun startWhisper(target: WhisperTarget): Boolean =
        try {
            engine.call { it.startWhisper(target) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            // Only the kind is logged; the text can name a person or a channel.
            log("start whisper failed: ${e.toConnectionError()}")
            mutableState.update { it.copy(denied = DeniedAction(channelId = null, detail = e.detail)) }
            false
        } catch (e: RuntimeException) {
            report(failed("start whisper", ConnectionError.Internal, e))
            false
        }

    /** Ends the whisper or shout. Never waits, so a release always gets through. */
    fun stopWhisper() {
        engine.now { it.stopWhisper() }
    }

    /**
     * Where the phone's audio streams exchange their samples with the engine, or null while no
     * engine exists. The handles stay valid for the whole process.
     */
    fun audioEndpoints(): AudioEndpoints? = engine.now { it.audioEndpoints() }

    /** Gives up on a channel password question. */
    fun dismissChannelPassword() = mutableState.update { it.copy(channelPassword = null) }

    fun dismissDenied() = mutableState.update { it.copy(denied = null) }

    /** A command of the connected screens that failed in the engine is shown like a refusal. */
    private fun report(outcome: Outcome<Unit>) {
        if (outcome is Outcome.Failed) reportFailure()
    }

    /** Shows the neutral refusal, for a command whose failure has no words of its own. */
    fun reportFailure() = mutableState.update { it.copy(denied = DeniedAction(channelId = null, detail = "")) }

    /** Puts a shown error away. */
    fun dismissFailure() {
        mutableState.update {
            if (it.phase is ConnectionPhase.Failed) it.copy(phase = ConnectionPhase.Idle, activeServerId = null) else it
        }
    }

    /**
     * Tells the engine which chat is on screen, or that none is. Never waits; without an engine
     * there is no chat to open.
     */
    fun setOpenChat(chat: ChatId?) {
        engine.now { it.setOpenChat(chat) }
    }

    /**
     * A command that may wait; [name] is what the log calls it when it fails. The chat uses it
     * too.
     */
    internal suspend fun <T> call(name: String, command: EngineCommand<T>): Outcome<T> =
        try {
            Outcome.Ok(engine.call(command))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            failed(name, e.toConnectionError(), e)
        } catch (e: RuntimeException) {
            failed(name, ConnectionError.Internal, e)
        } catch (e: LinkageError) {
            // The native library is missing or does not fit this device.
            failed(name, ConnectionError.Internal, e)
        }

    private fun failed(command: String, error: ConnectionError, cause: Throwable): Outcome.Failed {
        // Only the kind of failure: the detail text can contain the server address.
        log("$command failed: $error (${cause.javaClass.simpleName})")
        return Outcome.Failed(error)
    }
}
