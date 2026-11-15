// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.AudioEndpoints
import app.mumcord.mobile.bridge.BanInput
import app.mumcord.mobile.bridge.BanResult
import app.mumcord.mobile.bridge.BanTarget
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.PublicServer
import app.mumcord.mobile.bridge.PublicServerQuery
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.TransferScan
import app.mumcord.mobile.bridge.VoiceSettings
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.bridge.parseMumbleLink
import app.mumcord.mobile.platform.call.SelfVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The result of a command that the screen which started it reports itself (a form, a sheet). */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    /** [refusal] is the server's own text of a [ConnectionError.PermissionDenied]: untrusted, never logged. */
    data class Failed(val error: ConnectionError, val refusal: String? = null) : Outcome<Nothing>
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
                    is BridgeEvent.ChatNotification,
                    is BridgeEvent.MessageRequestsChanged,
                    is BridgeEvent.BlockedPeopleChanged,
                    -> chatEvents.trySend(event)
                    else -> events.trySend(event)
                }
            }
        },
        checkThreads = checkThreads,
    )

    /**
     * The password typed for the running connection only (not the saved one). Kept in memory
     * so that [reconnectNow] can use it again; forgotten when the connection ends, never
     * stored and never logged.
     */
    @Volatile
    private var sessionPassword: String? = null

    // ----- getting back in by the app itself, see reconnectNow -----

    /** True while the state hides that the connection is ended and started again. */
    @Volatile
    private var resuming = false
    private var resumeJob: Job? = null

    /** What the engine last said about the connection, also what [resuming] hides. */
    private val connection = MutableStateFlow<ConnectionState?>(null)

    /** How often a server's channels arrived; grows with every connection that got in. */
    private val synced = MutableStateFlow(0)

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
                if (event is BridgeEvent.ConnectionChanged) connection.value = event.state
                if (event is BridgeEvent.ServerSynced) synced.update { it + 1 }
                mutableState.update { if (resuming) it.foldResuming(event) else it.fold(event) }
                if (!resuming && !mutableState.value.isBusy) sessionPassword = null
            }
        }
    }

    /** Reads the saved servers once; later changes arrive as events. A failure can be retried. */
    suspend fun loadServers() {
        if (mutableState.value.serversLoaded) return
        when (val loaded = call("load servers") { it.snapshot() to it.talkMode() }) {
            // An event that arrived in between is newer than the snapshot.
            is Outcome.Ok -> mutableState.update {
                val (snapshot, talkMode) = loaded.value
                val known = it.copy(talkMode = talkMode, mobileSettings = it.mobileSettings ?: snapshot.mobileSettings)
                val identified = known.copy(identities = it.identities ?: snapshot.identities)
                if (it.serversLoaded) identified else identified.copy(servers = snapshot.servers, serversLoaded = true)
            }
            is Outcome.Failed -> mutableState.update { it.copy(phase = ConnectionPhase.Failed(loaded.error)) }
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

    // ----- public servers -----

    /**
     * The whole public server list, read page by page. The engine downloads it on the first
     * call and when [refresh] is set, otherwise it answers from the last download; a failed
     * download leaves that one in place.
     */
    suspend fun publicServers(refresh: Boolean): Outcome<List<PublicServer>> =
        call("public servers") { engine ->
            val servers = mutableListOf<PublicServer>()
            do {
                val page = engine.publicServers(
                    PublicServerQuery(
                        search = "",
                        countryCode = null,
                        offset = servers.size.toUInt(),
                        limit = PUBLIC_PAGE,
                        // Only the first page may download; the others read that download.
                        refresh = refresh && servers.isEmpty(),
                    ),
                )
                servers += page.servers
            } while (page.servers.isNotEmpty() && servers.size.toUInt() < page.total)
            servers
        }

    /** People online, user limit and latency of a listed server, or null when it does not answer. */
    suspend fun pingPublicServer(host: String, port: UShort): ServerPing? =
        when (val ping = call("ping public server") { it.pingPublicServer(host, port) }) {
            is Outcome.Ok -> ping.value
            is Outcome.Failed -> null
        }

    /** Saves the talk mode; the engine applies it at once. */
    // ----- identities -----

    /** A new certificate with [name] inside; the label is the name. */
    suspend fun createIdentity(name: String): Outcome<Identity> =
        call("create identity") { it.createIdentity(name, null) }

    /** The bytes of a `.p12` file as an additional identity, labelled with the name inside it. */
    suspend fun importIdentity(data: ByteArray, password: String): Outcome<Identity> =
        call("import identity") { it.importIdentity(data, password, null) }

    /** The identity as the bytes of a `.p12` file protected with [password]. */
    suspend fun exportIdentity(identityId: Long, password: String): Outcome<ByteArray> =
        call("export identity") { it.exportIdentity(identityId, password) }

    suspend fun setDefaultIdentity(identityId: Long): Outcome<Unit> =
        call("set default identity") { it.setDefaultIdentity(identityId) }

    suspend fun deleteIdentity(identityId: Long): Outcome<Unit> =
        call("delete identity") { it.deleteIdentity(identityId) }

    /** A new "Send to your phone" scan, or null when the engine could not be started. */
    suspend fun newTransfer(): TransferScan? =
        when (val scan = call("start transfer") { it.newTransfer() }) {
            is Outcome.Ok -> scan.value
            is Outcome.Failed -> null
        }

    /**
     * A step of a transfer that waits (checking the typed code, importing). It runs where the
     * engine's commands run, for the reason [EngineCalls] gives, and off the main thread: the
     * check of the code is slow on purpose.
     */
    suspend fun <T> transferStep(step: suspend () -> T): T = engine.call { step() }

    suspend fun setTalkMode(mode: TalkMode): Outcome<Unit> =
        call("set talk mode") { it.setTalkMode(mode) }.also { saved ->
            if (saved is Outcome.Ok) mutableState.update { it.copy(talkMode = mode) }
        }

    /** The phone settings as the engine keeps them, or null when they cannot be read. */
    suspend fun mobileSettings(): MobileSettings? =
        when (val settings = call("read phone settings") { it.mobileSettings() }) {
            is Outcome.Ok -> settings.value
            is Outcome.Failed -> null
        }

    /**
     * Changes the phone settings. Reading and writing are one command, so a change of one
     * setting never writes back an old value of another.
     */
    suspend fun updateMobileSettings(change: (MobileSettings) -> MobileSettings): Outcome<Unit> =
        call("save phone settings") { it.setMobileSettings(change(it.mobileSettings())) }

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
        sessionPassword = password
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
        sessionPassword = null
        if (resuming) {
            // Leaving wins over getting back in; what the state hid is shown now.
            resumeJob?.cancel()
            resuming = false
            mutableState.update { it.fold(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null))) }
        }
        val result = call("disconnect") { it.disconnect() }
        if (result is Outcome.Failed) {
            mutableState.update {
                it.copy(phase = ConnectionPhase.Failed(result.error), certificatePrompt = null, server = null)
            }
        }
    }

    /**
     * Gets back into the server at once, for the moment a network is there again while the
     * engine still waits before its next attempt (FR-5.40). The engine has no command for
     * that, so the connection is ended and started again: into the channel the user was in,
     * with the mute and deafen they had, and with the password of this connection. Meanwhile
     * the state stays "Reconnecting". A running whisper ends.
     *
     * Does nothing unless the engine is reconnecting, and never runs twice at a time. When it
     * does not get in, the connection ends as lost, like the engine's own last attempt.
     */
    suspend fun reconnectNow() {
        val job = synchronized(this) {
            if (resumeJob?.isActive == true || mutableState.value.phase !is ConnectionPhase.Reconnecting) return
            scope.launch(start = CoroutineStart.LAZY) { resume() }.also { resumeJob = it }
        }
        job.start()
        job.join()
    }

    private suspend fun resume() {
        val before = mutableState.value
        val serverId = before.activeServerId ?: return
        val channelPath = before.server?.selfChannelPath().orEmpty()
        val voice = selfVoice(before)
        val syncedBefore = synced.value
        resuming = true
        try {
            val back = try {
                withTimeoutOrNull(RESUME_TIMEOUT_MILLIS) {
                    engine.call { it.disconnect() }
                    connection.first { it is ConnectionState.Disconnected }
                    // Forgotten, so that the end waited for below is the new connection's.
                    connection.value = null
                    engine.call { it.connectServer(serverId, sessionPassword, channelPath) }
                    val end = connection.first { it is ConnectionState.Connected || it is ConnectionState.Disconnected }
                    if (end is ConnectionState.Connected) {
                        synced.first { it > syncedBefore }
                        true
                    } else {
                        false
                    }
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                // Only the kind: the detail text can contain the server address.
                log("reconnecting at once failed: ${e.toConnectionError()}")
                false
            } catch (e: RuntimeException) {
                log("reconnecting at once failed: ${e.javaClass.simpleName}")
                false
            }
            if (back) {
                resuming = false
                voice?.let { restore(it) }
            } else {
                lost()
            }
        } finally {
            resuming = false
        }
    }

    /**
     * The connection could not be got back: it ends as lost, like the engine's own last
     * attempt. The engine is told to stop while the state still hides its orderly end, which
     * would otherwise look as if the user had left.
     */
    private suspend fun lost() {
        if (connection.value !is ConnectionState.Disconnected) {
            call("disconnect") { it.disconnect() }
            withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { connection.first { it is ConnectionState.Disconnected } }
        }
        sessionPassword = null
        // A failure the engine reported itself is already shown with its own reason.
        mutableState.update {
            if (it.phase is ConnectionPhase.Failed) {
                it
            } else {
                it.copy(
                    phase = ConnectionPhase.Failed(ConnectionError.ServerUnreachable),
                    certificatePrompt = null,
                    server = null,
                )
            }
        }
    }

    /** Puts back the mute and deafen the user had, where the new connection starts otherwise. */
    private suspend fun restore(voice: SelfVoice) {
        val now = selfVoice(mutableState.value) ?: return
        if (now == voice) return
        if (voice.deafened) {
            setSelfDeaf(true)
        } else {
            if (now.deafened) setSelfDeaf(false)
            setSelfMute(voice.muted)
        }
    }

    /**
     * Limits the voice bitrate in kbit/s, or lifts the limit with null. The engine does not
     * keep it across a restart, so it is set again whenever it could have been lost. Never
     * waits; without an engine there is nothing to limit yet.
     */
    fun setBitrateLimit(kbps: UInt?) {
        engine.now { it.setBitrateLimitKbps(kbps) }
    }

    /** The voice settings as the engine applies them, or null when they cannot be read. */
    suspend fun voiceSettings(): VoiceSettings? =
        when (val settings = call("read voice settings") { it.voiceSettings() }) {
            is Outcome.Ok -> settings.value
            is Outcome.Failed -> null
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

    // ----- moderation (FR-5.90 … FR-5.93) -----
    // The commands return true when the server confirmed the change and false when it did not
    // answer in time; either way the tree shows what happened.

    suspend fun movePerson(session: UInt, channelId: UInt): Outcome<Boolean> =
        call("move person") { it.movePerson(session, channelId) }

    suspend fun setServerMute(session: UInt, muted: Boolean): Outcome<Boolean> =
        call("set server mute") { it.setServerMute(session, muted) }

    suspend fun kickPerson(session: UInt, reason: String?): Outcome<Boolean> =
        call("kick person") { it.kickPerson(session, reason) }

    /** What the ban sheet needs about a person; asks the server (up to a few seconds). */
    suspend fun banTarget(session: UInt): Outcome<BanTarget> = call("read ban target") { it.banTarget(session) }

    suspend fun banPerson(session: UInt, ban: BanInput): Outcome<BanResult> =
        call("ban person") { it.banPerson(session, ban) }

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
     * there is no chat to open. Only [ChatRepository] decides which chat that is.
     */
    internal fun setOpenChat(chat: ChatId?) {
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
        return Outcome.Failed(error, (cause as? BridgeException.PermissionDenied)?.detail)
    }

    private companion object {
        /** How long ending the connection and getting in again may take together. */
        const val RESUME_TIMEOUT_MILLIS = 30_000L

        /** How long the engine may take to end a connection it is told to end. */
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The most servers the bridge returns in one page. */
        const val PUBLIC_PAGE = 100u
    }
}
