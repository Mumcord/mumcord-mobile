// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.AudioEndpoints
import app.mumcord.mobile.bridge.BanInput
import app.mumcord.mobile.bridge.BanResult
import app.mumcord.mobile.bridge.BanTarget
import app.mumcord.mobile.bridge.Profile
import app.mumcord.mobile.bridge.AudioWanted
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BlockedPerson
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatLimits
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ChatNotification
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
import app.mumcord.mobile.bridge.NotificationKind
import app.mumcord.mobile.bridge.PrivateMessageRule
import app.mumcord.mobile.bridge.PrivateMessageSettings
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.SentChatRequest
import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeCodeException
import app.mumcord.mobile.bridge.ThemeCodePreview
import app.mumcord.mobile.bridge.ThemeColors
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeSettings
import app.mumcord.mobile.bridge.ThemeShare
import app.mumcord.mobile.bridge.ThemeTokens
import app.mumcord.mobile.bridge.ThemeVariant
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
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.bridge.MobileSettings
import app.mumcord.mobile.bridge.PublicServer
import app.mumcord.mobile.bridge.PublicServerPage
import app.mumcord.mobile.bridge.PublicServerQuery
import app.mumcord.mobile.bridge.TransferScan
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.TalkMode
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The phone settings of a fresh installation, which a [FakeEngine] starts with. */
/** The identity a phone has after its first start; a fingerprint has 40 hex digits. */
val FAKE_IDENTITY = Identity(
    id = 1,
    label = "Lea",
    commonName = "Lea",
    fingerprint = "4fa91c7ed208b36a".padEnd(40, '5'),
    createdMs = 0,
    isDefault = true,
    serverIds = emptyList(),
)

/** The name inside every identity file of the fake. */
const val IMPORTED_NAME = "Imported"

val FAKE_MOBILE_SETTINGS = MobileSettings(
    talkModeChosen = false,
    defaultOutput = DefaultOutput.ASK,
    mobileDataAutoplayStreams = false,
    mobileDataLoadMedia = true,
    mobileDataReducedVoice = false,
    idleDisconnect = false,
    notifySenderOnly = false,
    updateCheck = true,
)

/** The one built-in theme of the fake, with Mumcord's key colours. */
val FAKE_THEME = Theme(
    id = "preset:mumcord",
    name = "Mumcord",
    builtIn = true,
    dark = ThemeColors(
        background = "#1a1342",
        surface = "#231b55",
        text = "#f6f0e8",
        accent = "#a78bff",
        talking = "#3ee39e",
        warning = "#fbbf24",
        error = "#ff8f8f",
    ),
    light = ThemeColors(
        background = "#ffffff",
        surface = "#eee9f8",
        text = "#17113a",
        accent = "#6a40e0",
        talking = "#15803d",
        warning = "#7f4d00",
        error = "#b01c1c",
    ),
)

/** The only text the fake reads as a theme code. */
const val FAKE_THEME_CODE = "mct1.fake"

/** Tokens made of the key colours only: enough for screens that need some colours. */
private fun fakeTokens(themeId: String, variant: ThemeVariant): ThemeTokens {
    val colors = if (variant == ThemeVariant.DARK) FAKE_THEME.dark else FAKE_THEME.light
    return ThemeTokens(
        themeId = themeId,
        variant = variant,
        base = variant,
        bgServers = colors.background,
        bgSidebar = colors.background,
        bgChat = colors.background,
        bgElevated = colors.surface,
        bgHover = colors.surface,
        bgSelected = colors.surface,
        text = colors.text,
        textSecondary = colors.text,
        textMuted = colors.text,
        accent = colors.accent,
        onAccent = colors.background,
        accentText = colors.accent,
        success = colors.talking,
        warning = colors.warning,
        danger = colors.error,
        localMuted = colors.error,
        talking = colors.talking,
        accentStop1 = colors.accent,
        accentStop2 = colors.accent,
        accentStop3 = colors.accent,
        primaryBg = colors.accent,
        primaryText = colors.background,
        border = colors.surface,
        borderStrong = colors.text,
        focus = colors.accent,
    )
}

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
    identities: List<Identity> = listOf(FAKE_IDENTITY),
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

    /** The certificates of people who have one: their private chat is stored, by this hash. */
    val certificates = ConcurrentHashMap<UInt, String>()

    /** When set, a chat request fails with it. */
    @Volatile
    var chatRequestFailure: BridgeException? = null

    /** When set, blocking a person fails with it. */
    @Volatile
    var blockFailure: BridgeException? = null
    val chatRequestCalls = CopyOnWriteArrayList<Pair<UInt, String?>>()
    private var nextBlockId = 1L
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
            identities = identities.toList(),
            mobileSettings = mobileSettings,
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

    /** The engine does not keep the limit across a restart. */
    fun forgetBitrateLimit() {
        bitrateLimitKbps = null
    }

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

    // Unmuting also ends a deafen, as in Mumble.
    override suspend fun setSelfMute(muted: Boolean) {
        selfMuted = muted
        if (!muted) selfDeafened = false
        change(server.selfSession) { it.copy(selfMuted = muted, selfDeafened = it.selfDeafened && muted) }
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

    /**
     * A message of somebody else arrives: it is stored, counted as unread and reported, and a
     * private message or a mention in a chat that is not on screen is worth a notification.
     */
    fun receive(message: ChatMessage) {
        messages.getOrPut(message.chat) { CopyOnWriteArrayList() } += message
        val count = (unread[message.chat] ?: 0u) + 1u
        unread[message.chat] = count
        listConversation(message, message.senderName, message.senderSession)
        emit(BridgeEvent.ChatUnreadChanged(message.chat, count))
        emit(BridgeEvent.ChatMessageReceived(message))
        val kind = when {
            message.chat !is ChatId.Channel -> NotificationKind.PRIVATE_MESSAGE
            message.mentionsMe -> NotificationKind.MENTION
            else -> return
        }
        if (openChat == message.chat) return
        val preview = message.content.blocks.filterIsInstance<ContentBlock.Paragraph>()
            .joinToString(" ") { block -> block.spans.joinToString("") { it.text } }
        emit(BridgeEvent.ChatNotification(ChatNotification(message.chat, kind, message.senderName, preview, message.id)))
    }

    /** Only conversations with people who have a certificate are stored and listed. */
    private fun listConversation(message: ChatMessage, peerName: String?, peerSession: UInt?) {
        val chat = message.chat as? ChatId.Person ?: return
        val known = conversations.firstOrNull { it.chat == chat }
        // Their session while they are on the server, as the bridge looks it up.
        val online = certificates.entries.firstOrNull { it.value == chat.certHash }?.key
        conversations.removeAll { it.chat == message.chat }
        conversations.add(
            0,
            Conversation(
                chat = message.chat,
                peerName = peerName ?: known?.peerName.orEmpty(),
                peerSession = peerSession ?: online ?: known?.peerSession,
                last = message,
                unread = unread[message.chat] ?: 0u,
            ),
        )
    }

    /** Somebody the private message rule holds back wrote or asked for a chat. */
    fun receiveRequest(request: MessageRequest) {
        incomingRequests += request
        emitRequests()
    }

    private fun emitRequests() =
        emit(BridgeEvent.MessageRequestsChanged(MessageRequests(incomingRequests.toList(), sentRequests.toList())))

    override suspend fun chatImage(chat: ChatId, messageId: String, imageId: String): ImageData =
        chatImages[messageId to imageId] ?: error("no image $imageId in message $messageId")

    override suspend fun loadRemoteImage(url: String, loadMedia: Boolean): ImageData? =
        remoteImages[url].takeIf { loadMedia }

    override fun mentionCandidates(query: String, limit: UInt): List<MentionCandidate> =
        mentionNames.filter { it.name.contains(query, ignoreCase = true) }.take(limit.toInt())

    override suspend fun chatLimits(): ChatLimits = limits

    override fun chatWith(session: UInt): ChatId {
        if (people[session] == null) throw BridgeException.NotFound("unknown person")
        return certificates[session]?.let { ChatId.Person(it) } ?: ChatId.Session(session)
    }

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
        val request = incomingRequests.firstOrNull { it.id == id } ?: throw BridgeException.NotFound("unknown request")
        incomingRequests.remove(request)
        emitRequests()
        // The held messages arrive as messages of the conversation.
        request.messages.forEach(::receive)
    }

    override suspend fun declineMessageRequest(id: ULong) {
        if (!incomingRequests.removeAll { it.id == id }) throw BridgeException.NotFound("unknown request")
        emitRequests()
    }

    override suspend fun sendChatRequest(session: UInt, note: String?) {
        chatRequestCalls += session to note
        chatRequestFailure?.let { throw it }
        val name = server.people.firstOrNull { it.session == session }?.name.orEmpty()
        sentRequests += SentChatRequest(session, name, accepted = false)
        emitRequests()
    }

    override suspend fun privateMessageSettings(): PrivateMessageSettings = privateMessageSettings

    override suspend fun setPrivateMessageSettings(settings: PrivateMessageSettings) {
        privateMessageSettings = settings
    }

    override suspend fun blockPerson(session: UInt) {
        blockFailure?.let { throw it }
        val name = server.people.firstOrNull { it.session == session }?.name.orEmpty()
        blocked += BlockedPerson(
            id = nextBlockId++,
            name = name,
            serverLabel = "",
            blockedAtMs = 0,
            certHash = certificates[session],
            session = session,
        )
        emit(BridgeEvent.BlockedPeopleChanged(blocked.toList()))
        change(session) { it.copy(blocked = true) }
    }

    // As the engine: the person stays blocked while another entry still covers them.
    override suspend fun unblockPerson(id: Long) {
        val lifted = blocked.filter { it.id == id }
        blocked.removeAll(lifted)
        emit(BridgeEvent.BlockedPeopleChanged(blocked.toList()))
        lifted.mapNotNull { it.session }.distinct().forEach { session ->
            if (blocked.none { it.session == session }) change(session) { it.copy(blocked = false) }
        }
    }

    override suspend fun blockedPeople(): List<BlockedPerson> = blocked.toList()

    // ----- identities -----

    /** The identities on the phone; the certificates themselves are not part of the fake. */
    val identities = CopyOnWriteArrayList(identities)
    private var nextIdentityId = (identities.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun identities(): List<Identity> = identities.toList()

    override suspend fun createIdentity(name: String, label: String?): Identity = addIdentity(name, label)

    override suspend fun ensureDefaultIdentity(name: String): Identity =
        identities.firstOrNull { it.isDefault } ?: addIdentity(name, label = null)

    /** The file's bytes stand for the certificate: the same file twice is the same identity. */
    override suspend fun importIdentity(data: ByteArray, password: String, label: String?): Identity {
        // As the bridge: a wrong password and a file without an identity are the same failure.
        if (password != filePassword) throw BridgeException.InvalidInput("wrong password or no identity")
        val fingerprint = data.joinToString("") { "%02x".format(it) }
        if (identities.any { it.fingerprint == fingerprint }) throw BridgeException.DuplicateIdentity("already there")
        return addIdentity(name = label ?: IMPORTED_NAME, label = label, fingerprint = fingerprint)
    }

    /** The password every identity file of the fake has; empty for none. */
    @Volatile
    var filePassword = ""

    override suspend fun exportIdentity(identityId: Long, password: String): ByteArray {
        exportError?.let { throw it }
        return identity(identityId).fingerprint.encodeToByteArray()
    }

    /** When set, exporting fails with it. */
    @Volatile
    var exportError: BridgeException? = null

    override suspend fun renameIdentity(identityId: Long, label: String): Identity {
        val renamed = identity(identityId).copy(label = label)
        identities.replaceAll { if (it.id == identityId) renamed else it }
        emitIdentities()
        return renamed
    }

    override suspend fun setDefaultIdentity(identityId: Long) {
        identity(identityId)
        identities.replaceAll { it.copy(isDefault = it.id == identityId) }
        emitIdentities()
    }

    override suspend fun deleteIdentity(identityId: Long) {
        val deleted = identity(identityId)
        if (identities.size == 1) throw BridgeException.LastIdentity("the only identity")
        identities.remove(deleted)
        // There is always a default one.
        if (deleted.isDefault) identities[0] = identities[0].copy(isDefault = true)
        emitIdentities()
    }

    private fun addIdentity(name: String, label: String?, fingerprint: String = "fingerprint-$nextIdentityId"): Identity {
        val identity = Identity(
            id = nextIdentityId++,
            label = label ?: name,
            commonName = name,
            fingerprint = fingerprint,
            createdMs = 0,
            isDefault = identities.isEmpty(),
            serverIds = emptyList(),
        )
        identities += identity
        emitIdentities()
        return identity
    }

    private fun identity(id: Long): Identity =
        identities.firstOrNull { it.id == id } ?: throw BridgeException.NotFound("no identity $id")

    private fun emitIdentities() = emit(BridgeEvent.IdentitiesChanged(identities.toList()))

    /** The scan the next "Scan from desktop app" gets; without one the engine has no transfer. */
    @Volatile
    var transfer: TransferScan? = null

    override fun newTransfer(): TransferScan =
        transfer ?: throw BridgeException.Internal("the fake engine has no transfer")

    // ----- phone settings -----

    @Volatile
    var mobileSettings = FAKE_MOBILE_SETTINGS

    /** When set, saving the phone settings fails with it. */
    @Volatile
    var mobileSettingsFailure: BridgeException? = null

    override fun mobileSettings(): MobileSettings = mobileSettings

    override suspend fun setMobileSettings(settings: MobileSettings) {
        mobileSettingsFailure?.let { throw it }
        mobileSettings = settings
        emit(BridgeEvent.MobileSettingsChanged(settings))
    }

    // ----- shared settings -----

    var appSettings = AppSettings(
        language = null,
        linkPreviews = true,
        historyMaxAgeDays = null,
        notifyPrivateMessages = true,
        notifyMentions = true,
    )

    override fun appSettings(): AppSettings = appSettings

    override suspend fun setAppSettings(settings: AppSettings) {
        appSettings = settings
        emit(BridgeEvent.AppSettingsChanged(settings))
    }

    // ----- themes -----

    var themeSettings = ThemeSettings(
        mode = ThemeMode.SYSTEM,
        appThemeId = FAKE_THEME.id,
        customThemes = emptyList(),
        serverThemes = emptyMap(),
    )

    override fun themeSettings(): ThemeSettings = themeSettings

    override fun builtInThemes(): List<Theme> = listOf(FAKE_THEME)

    override fun themeTokens(themeId: String?, variant: ThemeVariant): ThemeTokens =
        fakeTokens(themeId ?: themeSettings.appThemeId, variant)

    override suspend fun setThemeMode(mode: ThemeMode) {
        changeThemes(themeSettings.copy(mode = mode))
    }

    override suspend fun setAppTheme(themeId: String) {
        findTheme(themeId)
        changeThemes(themeSettings.copy(appThemeId = themeId))
    }

    /** Reads only [FAKE_THEME_CODE], as the theme named "Imported". */
    override fun themeCodePreview(text: String): ThemeCodePreview {
        if (FAKE_THEME_CODE !in text) throw ThemeCodeException.Invalid()
        return ThemeCodePreview(
            name = "Imported",
            dark = FAKE_THEME.dark,
            light = FAKE_THEME.light,
            createdWith = "0.8.0",
            madeWithNewer = false,
            importedId = themeSettings.customThemes.firstOrNull()?.id,
        )
    }

    override suspend fun importThemeCode(text: String): Theme {
        if (FAKE_THEME_CODE !in text) throw BridgeException.InvalidInput("this is not a valid theme code")
        themeSettings.customThemes.firstOrNull()?.let { return it }
        val theme = FAKE_THEME.copy(id = "custom:1", name = "Imported", builtIn = false)
        changeThemes(themeSettings.copy(customThemes = listOf(theme)))
        return theme
    }

    override fun themeShare(themeId: String): ThemeShare {
        val theme = findTheme(themeId)
        return ThemeShare(FAKE_THEME_CODE, "🎨 Mumcord theme \"${theme.name}\" – $FAKE_THEME_CODE")
    }

    override suspend fun deleteCustomTheme(themeId: String) {
        if (findTheme(themeId).builtIn) throw BridgeException.InvalidInput("built-in themes cannot be deleted")
        val appThemeId = if (themeSettings.appThemeId == themeId) FAKE_THEME.id else themeSettings.appThemeId
        changeThemes(
            themeSettings.copy(
                appThemeId = appThemeId,
                customThemes = themeSettings.customThemes.filter { it.id != themeId },
            ),
        )
    }

    override suspend fun setServerTheme(serverId: Long, themeId: String?) {
        themeId?.let(::findTheme)
        val serverThemes = if (themeId == null) {
            themeSettings.serverThemes - serverId
        } else {
            themeSettings.serverThemes + (serverId to themeId)
        }
        changeThemes(themeSettings.copy(serverThemes = serverThemes))
    }

    private fun findTheme(themeId: String): Theme =
        (listOf(FAKE_THEME) + themeSettings.customThemes).firstOrNull { it.id == themeId }
            ?: throw BridgeException.NotFound("unknown theme")

    private fun changeThemes(settings: ThemeSettings) {
        if (settings == themeSettings) return
        themeSettings = settings
        emit(BridgeEvent.ThemesChanged(settings))
    }

    // ----- profile, moderation, activity -----

    var profile = Profile(comment = null, commentText = "", commentImages = emptyList(), avatar = null)
    val moderationCalls = CopyOnWriteArrayList<String>()
    var activity: List<ActivityEntry> = emptyList()

    override suspend fun personProfile(session: UInt): Profile = profile

    override suspend fun setOwnComment(text: String) {
        profile = profile.copy(commentText = text)
        emit(BridgeEvent.ProfileChanged(server.selfSession))
    }

    override suspend fun setOwnAvatar(image: ByteArray?) {
        emit(BridgeEvent.ProfileChanged(server.selfSession))
    }

    /** Thrown by the next moderation command (move, mute, deafen, kick, ban and its target) instead of acting. */
    @Volatile
    var moderationFailure: BridgeException? = null

    /** What [banTarget] reports about a person. */
    @Volatile
    var banTargetAnswer = BanTarget(
        address = "192.0.2.7",
        onlineSecs = null,
        banListReadable = true,
        hasCertificate = true,
        canKick = true,
        canBan = true,
    )

    /** What the next bans produce, in order; a ban beyond them is saved and removes the person. */
    val banResults = CopyOnWriteArrayList<BanResult>()
    val kickReasons = CopyOnWriteArrayList<String?>()
    val banInputs = CopyOnWriteArrayList<BanInput>()

    private fun moderate() {
        moderationFailure?.let {
            moderationFailure = null
            throw it
        }
    }

    override suspend fun movePerson(session: UInt, channelId: UInt): Boolean {
        moderate()
        moderationCalls += "move $session $channelId"
        change(session) { it.copy(channelId = channelId) }
        return true
    }

    override suspend fun setServerMute(session: UInt, muted: Boolean): Boolean {
        moderate()
        moderationCalls += "mute $session $muted"
        change(session) { it.copy(serverMuted = muted) }
        return true
    }

    override suspend fun setServerDeafen(session: UInt, deafened: Boolean): Boolean {
        moderate()
        moderationCalls += "deafen $session $deafened"
        return true
    }

    override suspend fun kickPerson(session: UInt, reason: String?): Boolean {
        moderate()
        moderationCalls += "kick $session"
        kickReasons += reason
        return true
    }

    override suspend fun banTarget(session: UInt): BanTarget {
        moderate()
        return banTargetAnswer
    }

    override suspend fun banPerson(session: UInt, ban: BanInput): BanResult {
        moderate()
        moderationCalls += "ban $session"
        banInputs += ban
        return if (banResults.isEmpty()) BanResult.Saved(removed = true) else banResults.removeAt(0)
    }

    override fun activityLog(): List<ActivityEntry> = activity

    // ----- public servers -----

    /** The public server list as the directory would give it. */
    val publicServers = CopyOnWriteArrayList<PublicServer>()

    /** How often the list was downloaded: on the first call and on every refresh. */
    @Volatile var publicDownloads = 0
        private set

    /** When set, downloading the list fails with it; the list downloaded before stays, as in the bridge. */
    @Volatile var publicListFailure: BridgeException? = null

    /** When set, a download waits for it. */
    @Volatile var publicListGate: CompletableDeferred<Unit>? = null

    /** The list as last downloaded; null before the first download. */
    @Volatile private var downloaded: List<PublicServer>? = null

    /** What listed servers answer, by "host:port"; the others give no answer. */
    val publicPings = ConcurrentHashMap<String, ServerPing>()
    val publicPingRequests = CopyOnWriteArrayList<String>()

    override suspend fun publicServers(query: PublicServerQuery): PublicServerPage {
        val list = downloaded.takeIf { !query.refresh } ?: run {
            publicDownloads++
            publicListGate?.await()
            publicListFailure?.let { throw it }
            publicServers.toList().also { downloaded = it }
        }
        val matching = list.filter {
            it.name.contains(query.search, ignoreCase = true) &&
                (query.countryCode == null || it.countryCode == query.countryCode)
        }
        return PublicServerPage(
            servers = matching.drop(query.offset.toInt()).take(query.limit.toInt()),
            total = matching.size.toUInt(),
        )
    }

    override suspend fun pingPublicServer(host: String, port: UShort): ServerPing? {
        publicPingRequests += "$host:$port"
        return publicPings["$host:$port"]
    }

    private fun appendOwn(chat: ChatId, content: MessageContent): ChatMessage {
        val message = chatMessage(
            id = "own-${nextMessageId++}",
            chat = chat,
            own = true,
            content = content,
        )
        messages.getOrPut(chat) { CopyOnWriteArrayList() } += message
        listConversation(message, peerName = null, peerSession = null)
        return message
    }

    private fun finishConnecting() {
        emit(BridgeEvent.ConnectionChanged(ConnectionState.Connected))
        emit(BridgeEvent.ServerSynced(server))
    }
}
