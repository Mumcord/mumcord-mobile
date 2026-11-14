// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.AudioEndpoints
import app.mumcord.mobile.bridge.AudioWanted
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BlockedPerson
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ChatPage
import app.mumcord.mobile.bridge.ChatUnread
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.Conversation
import app.mumcord.mobile.bridge.GifFavourite
import app.mumcord.mobile.bridge.ImageData
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.bridge.MentionCandidate
import app.mumcord.mobile.bridge.MessageContent
import app.mumcord.mobile.bridge.MessageRequest
import app.mumcord.mobile.bridge.MessageRequests
import app.mumcord.mobile.bridge.PrivateMessageRule
import app.mumcord.mobile.bridge.PrivateMessageSettings
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.SentChatRequest
import app.mumcord.mobile.bridge.EchoCancellation
import app.mumcord.mobile.bridge.VoiceSettings
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.bridge.ChannelPasswordState
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.BridgeSnapshot
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.ConnectRequest
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumcordEngineInterface
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.TalkMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One call of [FakeEngine.connectServer]. */
data class ConnectCall(val serverId: Long, val password: String?, val channelPath: List<String>)

/**
 * Stands in for the engine and behaves like it: it keeps a server list, answers pings from
 * [pings], and along the connect flow asks the certificate question (when [prompt] is set),
 * delivers [server] once trusted, and reports every end of a connection as an event.
 * [talkMode] is the saved talk mode it starts with.
 */
class FakeEngine(
    private val listener: EngineListener,
    private val server: ServerState,
    private val prompt: CertificatePrompt? = null,
    servers: List<SavedServer> = emptyList(),
    talkMode: TalkMode = TalkMode.VOICE_ACTIVITY,
) : MumcordEngineInterface {
    val connectCalls = CopyOnWriteArrayList<ConnectCall>()

    // Chat state the tests fill and read. Messages are kept oldest first.
    val messages = ConcurrentHashMap<ChatId, MutableList<ChatMessage>>()
    val unread = ConcurrentHashMap<ChatId, UInt>()
    val gifFavourites = CopyOnWriteArrayList<GifFavourite>()
    val conversations = CopyOnWriteArrayList<Conversation>()
    val incomingRequests = CopyOnWriteArrayList<MessageRequest>()
    val sentRequests = CopyOnWriteArrayList<SentChatRequest>()
    val blocked = CopyOnWriteArrayList<BlockedPerson>()
    val mentionNames = CopyOnWriteArrayList<MentionCandidate>()
    val chatImages = ConcurrentHashMap<Pair<String, String>, ImageData>()
    val remoteImages = ConcurrentHashMap<String, ImageData>()
    val openChatCalls = CopyOnWriteArrayList<ChatId?>()
    val markReadCalls = CopyOnWriteArrayList<ChatId>()

    /** When set, reading a page of a chat fails with it. */
    @Volatile
    var pageFailure: BridgeException? = null

    /** When set, sending anything to a chat fails with it. */
    @Volatile
    var sendFailure: BridgeException? = null

    @Volatile var openChat: ChatId? = null
        private set
    @Volatile var limits = ChatLimits(text = 5000u, image = 131072u, allowHtml = true)
    @Volatile var privateMessageSettings = PrivateMessageSettings(PrivateMessageRule.EVERYONE, false)
    private var nextMessageId = 1
    val certificateAnswers = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val serverInputs = CopyOnWriteArrayList<ServerInput>()
    val pingRequests = CopyOnWriteArrayList<Long>()
    var disconnects = 0
        private set

    /** What each server answers to a ping; a server without an entry does not answer. */
    val pings = ConcurrentHashMap<Long, ServerPing>()

    /** Thrown by the next [connectServer] instead of connecting. */
    @Volatile
    var connectFailure: BridgeException? = null

    /** Thrown by the next [addServer] or [updateServer]. */
    @Volatile
    var saveFailure: BridgeException? = null

    /** Thrown by the next [setTalkMode]. */
    @Volatile
    var talkModeFailure: BridgeException? = null

    /** When set, the server refuses every connection without exactly this password. */
    @Volatile
    var requiredPassword: String? = null

    /** Channels that only let in who joins with exactly this password. */
    val channelPasswords = ConcurrentHashMap<UInt, String>()

    /** Channels the server refuses to let the user into. */
    val deniedChannels: MutableSet<UInt> = ConcurrentHashMap.newKeySet()
    val joinCalls = CopyOnWriteArrayList<Pair<UInt, String?>>()
    val volumeCalls = CopyOnWriteArrayList<Pair<UInt, Int>>()
    private val people = ConcurrentHashMap(server.people.associateBy { it.session })

    @Volatile
    var talkMode = talkMode
        private set

    @Volatile
    private var connection: ConnectionState = ConnectionState.Disconnected(null)

    @Volatile
    private var connectedServerId: Long? = null

    private val savedServers = CopyOnWriteArrayList(servers)
    private var nextServerId = (servers.maxOfOrNull { it.id } ?: 0L) + 1

    fun emit(event: BridgeEvent) {
        if (event is BridgeEvent.ConnectionChanged) {
            connection = event.state
            if (event.state is ConnectionState.Disconnected) connectedServerId = null
        }
        listener.onEvent(event)
    }

    override suspend fun connectServer(serverId: Long, password: String?, channelPath: List<String>) {
        connectFailure?.let {
            connectFailure = null
            throw it
        }
        if (savedServers.none { it.id == serverId }) throw BridgeException.NotFound("no server $serverId")
        if (connection !is ConnectionState.Disconnected) throw BridgeException.InvalidInput("already connected")
        connectCalls += ConnectCall(serverId, password, channelPath)
        connectedServerId = serverId
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connecting))
        when {
            requiredPassword != null && password != requiredPassword -> emit(
                BridgeEvent.ConnectionChanged(
                    ConnectionState.Disconnected(BridgeException.WrongPassword("wrong password")),
                ),
            )
            prompt != null -> emit(BridgeEvent.CertificatePromptRequired(prompt))
            else -> finishConnecting()
        }
    }

    // The call of the first slice, which the app no longer uses.
    override suspend fun connect(request: ConnectRequest): Unit =
        throw BridgeException.Internal("connect is not used by the app")

    override suspend fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        certificateAnswers += fingerprint to trust
        if (trust) {
            finishConnecting()
        } else {
            emit(
                BridgeEvent.ConnectionChanged(
                    ConnectionState.Disconnected(BridgeException.CertificateRejected("rejected")),
                ),
            )
        }
    }

    override suspend fun disconnect() {
        disconnects++
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Disconnected(null)))
    }

    override fun snapshot(): BridgeSnapshot =
        BridgeSnapshot(
            servers = savedServers.toList(),
            connection = connection,
            connectedServerId = connectedServerId,
            certificatePrompt = null,
            server = null,
            audio = AudioWanted(false, false),
            chatUnread = unread.map { (chat, count) -> ChatUnread(chat, count) },
            openChat = openChat,
            whispering = false,
        )

    override suspend fun addServer(input: ServerInput): SavedServer {
        failSaveOnce()
        serverInputs += input
        return input.toSaved(nextServerId++, hadPassword = false).also {
            savedServers += it
            emit(BridgeEvent.ServersChanged(savedServers.toList()))
        }
    }

    override suspend fun updateServer(serverId: Long, input: ServerInput): SavedServer {
        failSaveOnce()
        val index = savedServers.indexOfFirst { it.id == serverId }
        if (index < 0) throw BridgeException.NotFound("no server $serverId")
        serverInputs += input
        return input.toSaved(serverId, hadPassword = savedServers[index].hasPassword).also {
            savedServers[index] = it
            emit(BridgeEvent.ServersChanged(savedServers.toList()))
        }
    }

    private fun failSaveOnce() {
        saveFailure?.let {
            saveFailure = null
            throw it
        }
    }

    override suspend fun removeServer(serverId: Long) {
        if (!savedServers.removeIf { it.id == serverId }) throw BridgeException.NotFound("no server $serverId")
        emit(BridgeEvent.ServersChanged(savedServers.toList()))
    }

    override suspend fun reorderServers(serverIds: List<Long>) {
        val byId = savedServers.associateBy { it.id }
        savedServers.clear()
        savedServers += serverIds.mapNotNull { byId[it] }
        emit(BridgeEvent.ServersChanged(savedServers.toList()))
    }

    override suspend fun pingServer(serverId: Long, refresh: Boolean): ServerPing? {
        pingRequests += serverId
        return pings[serverId]
    }

    override suspend fun joinChannel(channelId: UInt, password: String?) {
        joinCalls += channelId to password
        val needed = channelPasswords[channelId]
        when {
            channelId in deniedChannels -> emit(BridgeEvent.ActionDenied(channelId, "Permission denied."))
            needed != null && password == null ->
                emit(BridgeEvent.ChannelPasswordChanged(channelId, ChannelPasswordState.REQUIRED))
            needed != null && password != needed ->
                emit(BridgeEvent.ChannelPasswordChanged(channelId, ChannelPasswordState.WRONG))
            else -> {
                if (password != null) emit(BridgeEvent.ChannelPasswordChanged(channelId, ChannelPasswordState.ENDED))
                change(server.selfSession) { it.copy(channelId = channelId) }
            }
        }
    }

    override suspend fun setPersonVolume(session: UInt, percent: UShort) {
        volumeCalls += session to percent.toInt()
        change(session) { it.copy(localVolume = percent) }
    }

    override suspend fun setPersonLocalMute(session: UInt, muted: Boolean) {
        change(session) { it.copy(locallyMuted = muted) }
    }

    /** Changes a person and reports it, as the engine does. */
    fun change(session: UInt, change: (Person) -> Person) {
        val person = people[session] ?: return
        change(person).also {
            people[session] = it
            emit(BridgeEvent.PersonChanged(it))
        }
    }

    override suspend fun talkMode(): TalkMode = talkMode

    override suspend fun setTalkMode(mode: TalkMode) {
        talkModeFailure?.let {
            talkModeFailure = null
            throw it
        }
        talkMode = mode
    }

    @Volatile
    var pushToTalkPressed = false
        private set

    @Volatile
    var selfMuted = false
        private set

    @Volatile
    var selfDeafened = false
        private set

    @Volatile
    var noiseSuppression = false
        private set

    @Volatile
    var autoGain = false
        private set

    @Volatile
    var bitrateLimitKbps: UInt? = null
        private set

    /** The target of the running whisper, or null when none runs. */
    @Volatile
    var whisperTarget: WhisperTarget? = null
        private set

    override fun audioEndpoints(): AudioEndpoints = AudioEndpoints(capture = 0uL, playback = 0uL)

    /** Every press (true) and release (false) of the hold-to-talk button, in order. */
    val pushToTalkCalls = CopyOnWriteArrayList<Boolean>()

    override fun setPushToTalkPressed(pressed: Boolean) {
        pushToTalkCalls += pressed
        pushToTalkPressed = pressed
    }

    override suspend fun setSelfMute(muted: Boolean) {
        selfMuted = muted
        change(server.selfSession) { it.copy(selfMuted = muted) }
    }

    // Deafening also mutes, as in Mumble.
    override suspend fun setSelfDeaf(deafened: Boolean) {
        selfDeafened = deafened
        if (deafened) selfMuted = true
        change(server.selfSession) {
            it.copy(selfDeafened = deafened, selfMuted = it.selfMuted || deafened)
        }
    }

    override suspend fun voiceSettings(): VoiceSettings = VoiceSettings(
        talkMode = talkMode,
        noiseSuppression = noiseSuppression,
        autoGain = autoGain,
        echoCancellation = EchoCancellation.SYSTEM,
        bitrateLimitKbps = bitrateLimitKbps,
    )

    override suspend fun setNoiseSuppression(on: Boolean) {
        noiseSuppression = on
    }

    override suspend fun setAutoGain(on: Boolean) {
        autoGain = on
    }

    override fun setBitrateLimitKbps(kbps: UInt?) {
        bitrateLimitKbps = kbps
    }

    /** Thrown by the next [startWhisper]. */
    @Volatile
    var whisperFailure: BridgeException? = null

    /** Every whisper that started (its target) and ended (null), in order. */
    val whisperCalls = CopyOnWriteArrayList<WhisperTarget?>()

    override suspend fun startWhisper(target: WhisperTarget) {
        whisperFailure?.let {
            whisperFailure = null
            throw it
        }
        whisperCalls += target
        whisperTarget = target
    }

    override fun stopWhisper() {
        whisperCalls += null
        whisperTarget = null
    }

    private fun ServerInput.toSaved(id: Long, hadPassword: Boolean) = SavedServer(
        id = id,
        name = name.ifEmpty { address },
        address = address,
        port = port,
        username = username,
        hasPassword = when (password) {
            is PasswordInput.Set -> true
            PasswordInput.Clear -> false
            PasswordInput.Keep -> hadPassword
        },
        identityId = identityId,
    )

    override suspend fun chatPage(chat: ChatId, before: String?, limit: UInt): ChatPage {
        pageFailure?.let { throw it }
        val all = messages[chat].orEmpty().toList()
        val end = before?.let { id -> all.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: all.size
        val start = maxOf(0, end - limit.toInt())
        return ChatPage(messages = all.subList(start, end), hasMore = start > 0)
    }

    override suspend fun sendMessage(chat: ChatId, text: String, replyTo: ReplyQuote?): ChatMessage {
        sendFailure?.let { throw it }
        return appendOwn(chat, MessageContent(reply = replyTo, blocks = listOf(ContentBlock.Paragraph(listOf(span(text)))), truncated = false))
    }

    override suspend fun sendImage(chat: ChatId, bytes: ByteArray, mime: String): ChatMessage {
        sendFailure?.let { throw it }
        val imageId = "image-${nextMessageId}"
        val image = ImageRef.Embedded(imageId, mime, 1u, 1u, bytes.size.toUInt(), false)
        return appendOwn(chat, MessageContent(null, listOf(ContentBlock.Image(image)), false)).also {
            chatImages[it.id to imageId] = ImageData(bytes, mime, 1u, 1u, false)
        }
    }

    override suspend fun sendGifFavourite(chat: ChatId, url: String): ChatMessage {
        sendFailure?.let { throw it }
        return appendOwn(chat, messageContent(url))
    }

    /** A message of somebody else arrives: it is stored, counted as unread and reported. */
    fun receive(message: ChatMessage) {
        messages.getOrPut(message.chat) { CopyOnWriteArrayList() } += message
        val count = (unread[message.chat] ?: 0u) + 1u
        unread[message.chat] = count
        emit(BridgeEvent.ChatUnreadChanged(message.chat, count))
        emit(BridgeEvent.ChatMessageReceived(message))
    }

    override suspend fun chatImage(chat: ChatId, messageId: String, imageId: String): ImageData =
        chatImages[messageId to imageId] ?: error("no image $imageId in message $messageId")

    override suspend fun loadRemoteImage(url: String, loadMedia: Boolean): ImageData? =
        remoteImages[url].takeIf { loadMedia }

    override fun mentionCandidates(query: String, limit: UInt): List<MentionCandidate> =
        mentionNames.filter { it.name.contains(query, ignoreCase = true) }.take(limit.toInt())

    override suspend fun chatLimits(): ChatLimits = limits

    override fun chatWith(session: UInt): ChatId = ChatId.Session(session)

    override suspend fun markChatRead(chat: ChatId) {
        markReadCalls += chat
        if (unread.remove(chat) != null) emit(BridgeEvent.ChatUnreadChanged(chat, 0u))
    }

    override fun setOpenChat(chat: ChatId?) {
        openChat = chat
        openChatCalls += chat
    }

    override suspend fun gifFavourites(): List<GifFavourite> = gifFavourites.toList()

    override suspend fun addGifFavourite(url: String) {
        if (gifFavourites.none { it.url == url }) gifFavourites += GifFavourite(url, addedAtMs = 0, lastUsedAtMs = 0)
    }

    override suspend fun removeGifFavourite(url: String) {
        gifFavourites.removeAll { it.url == url }
    }

    override suspend fun privateConversations(): List<Conversation> = conversations.toList()

    override suspend fun messageRequests(): MessageRequests =
        MessageRequests(incoming = incomingRequests.toList(), sent = sentRequests.toList())

    override suspend fun acceptMessageRequest(id: ULong) {
        incomingRequests.removeAll { it.id == id }
    }

    override suspend fun declineMessageRequest(id: ULong) {
        incomingRequests.removeAll { it.id == id }
    }

    override suspend fun sendChatRequest(session: UInt, note: String?) {
        val name = server.people.firstOrNull { it.session == session }?.name.orEmpty()
        sentRequests += SentChatRequest(session, name, accepted = false)
    }

    override suspend fun privateMessageSettings(): PrivateMessageSettings = privateMessageSettings

    override suspend fun setPrivateMessageSettings(settings: PrivateMessageSettings) {
        privateMessageSettings = settings
    }

    override suspend fun blockPerson(session: UInt) {
        val name = server.people.firstOrNull { it.session == session }?.name.orEmpty()
        blocked += BlockedPerson(id = blocked.size + 1L, name = name, serverLabel = "", blockedAtMs = 0)
    }

    override suspend fun unblockPerson(id: Long) {
        blocked.removeAll { it.id == id }
    }

    override suspend fun blockedPeople(): List<BlockedPerson> = blocked.toList()

    private fun appendOwn(chat: ChatId, content: MessageContent): ChatMessage {
        val message = chatMessage(
            id = "own-${nextMessageId++}",
            chat = chat,
            own = true,
            content = content,
        )
        messages.getOrPut(chat) { CopyOnWriteArrayList() } += message
        return message
    }

    private fun finishConnecting() {
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connected))
        emit(BridgeEvent.ServerSynced(server))
    }
}
