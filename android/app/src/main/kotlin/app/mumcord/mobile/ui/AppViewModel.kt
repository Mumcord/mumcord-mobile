// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.crash.BridgeCrashSource
import app.mumcord.mobile.crash.CrashSource
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.platform.call.SessionEndedNotice
import app.mumcord.mobile.session.AppLanguages
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.IdleAccess
import app.mumcord.mobile.session.InMemoryMessageNotificationSettings
import app.mumcord.mobile.session.MessageNotificationSettings
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.OutputAccess
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.setServerTheme
import app.mumcord.mobile.session.themeTokens
import app.mumcord.mobile.ui.about.AboutController
import app.mumcord.mobile.ui.about.NoticesSource
import app.mumcord.mobile.ui.camera.CameraGateway
import app.mumcord.mobile.ui.chat.ChatController
import app.mumcord.mobile.ui.chat.ChatUiState
import app.mumcord.mobile.ui.chat.chatTabBadge
import app.mumcord.mobile.ui.chat.chatUiState
import app.mumcord.mobile.ui.chat.conversationUiState
import app.mumcord.mobile.ui.connect.LocalNetworkAnswer
import app.mumcord.mobile.ui.connect.LocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkStep
import app.mumcord.mobile.ui.connect.check
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.MicrophoneAccess
import app.mumcord.mobile.ui.connected.MicrophoneAnswer
import app.mumcord.mobile.ui.connected.MicrophoneNotice
import app.mumcord.mobile.ui.connected.MoreController
import app.mumcord.mobile.ui.connected.MoreUiState
import app.mumcord.mobile.ui.connected.NotificationAccess
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.connected.moreUiState
import app.mumcord.mobile.ui.crash.CrashController
import app.mumcord.mobile.ui.crash.CrashLocal
import app.mumcord.mobile.ui.identities.IdentitiesController
import app.mumcord.mobile.ui.identities.IdentitiesLocal
import app.mumcord.mobile.ui.identities.identitiesUiState
import app.mumcord.mobile.ui.link.linkConfirmState
import app.mumcord.mobile.ui.messages.MessagesController
import app.mumcord.mobile.ui.messages.MessagesUiState
import app.mumcord.mobile.ui.messages.messagesUiState
import app.mumcord.mobile.ui.servers.LocalNetworkHint
import app.mumcord.mobile.ui.servers.PublicServersActions
import app.mumcord.mobile.ui.servers.PublicServersController
import app.mumcord.mobile.ui.servers.PublicServersUiState
import app.mumcord.mobile.ui.servers.ServerFormState
import app.mumcord.mobile.ui.servers.publicServersUiState
import app.mumcord.mobile.ui.servers.toPingState
import app.mumcord.mobile.ui.setup.PreferencesSetupStore
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.themes.AppTheme
import app.mumcord.mobile.ui.themes.shownThemeId
import app.mumcord.mobile.update.AppInfo
import app.mumcord.mobile.update.AvailableUpdate
import app.mumcord.mobile.update.UpdateChecker
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import app.mumcord.mobile.ui.streams.StreamVideo
import app.mumcord.mobile.ui.streams.StreamsController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state of the app. Its own part is everything before the connected screens: first-start setup, the server list,
 * the add and edit form, connecting with its questions and errors, and opened links.
 */
class AppViewModel(
    private val repository: SessionRepository,
    private val setupStore: SetupStore,
    private val localNetwork: LocalNetworkGateway,
    private val microphone: MicrophoneAccess,
    private val notifications: NotificationAccess = NotificationAccess.Never,
    sessionEnded: SessionEndedNotice? = null,
    messageSettings: MessageNotificationSettings = InMemoryMessageNotificationSettings(),
    private val outputs: OutputAccess = OutputAccess.None,
    private val idle: IdleAccess = IdleAccess.Never,
    camera: CameraGateway = CameraGateway.Never,
    appInfo: AppInfo = AppInfo(appVersion = "0.1.0", engineVersion = "", debuggable = true),
    updates: UpdateChecker? = null,
    notices: NoticesSource = NoticesSource { "" },
    /** Where the notices are read; the tests read them on their own dispatcher. */
    io: CoroutineDispatcher = Dispatchers.IO,
    crashSource: CrashSource = CrashSource.None,
    streamsAutoplay: Flow<Boolean> = flowOf(true),
    /** Where the pictures of watched streams come from. */
    val streamVideo: StreamVideo = StreamVideo.None,
    languages: AppLanguages = AppLanguages.PhoneOnly,
) : ViewModel() {
    private val local = MutableStateFlow(
        AppUiState(
            setupDone = setupStore.firstStartDone,
            overlay = sessionEnded?.let { Overlay.SessionEnded(it.serverId, it.serverName) },
        ),
    )


    init {
        // The app disconnected by itself because the user was alone: the next look at the app
        // says so, where the server list is.
        viewModelScope.launch {
            repository.state.map { it.showsServer }.distinctUntilChanged().drop(1).collect { shown ->
                if (!shown) refreshPings()
            }
        }
        viewModelScope.launch {
            idle.notice.collect { notice ->
                if (notice != null) {
                    local.update { it.copy(overlay = Overlay.SessionEnded(notice.serverId, notice.serverName, alone = true)) }
                }
            }
        }
    }

    /** The connected screens' own state and actions. */
    val connected = ConnectedController(repository, viewModelScope, outputs)

    /** Watching screen shares and cameras. */
    val streams = StreamsController(repository, viewModelScope, streamsAutoplay, streamVideo)

    /** The identity screens and the scan of a transfer from the desktop app. */
    val identities = IdentitiesController(repository, viewModelScope, camera)

    /** The public server browser (FR-5.9). */
    val publicServers = PublicServersController(repository, viewModelScope)

    /** Its own flow: pings arriving for the browser do not rebuild the other screens. */
    val publicServersState: StateFlow<PublicServersUiState> =
        combine(publicServers.local, repository.state.map { it.servers }.distinctUntilChanged(), ::publicServersUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                publicServersUiState(publicServers.local.value, repository.state.value.servers),
            )

    val publicServerActions = object : PublicServersActions {
        override fun back() = closePublicServers()
        override fun refresh() = publicServers.refresh()
        override fun search(text: String) = publicServers.search(text)
        override fun selectCountry(code: String?) = publicServers.selectCountry(code)
        override fun showing(keys: List<String>) = publicServers.showing(keys)
        override fun add(key: String) = publicServers.add(key)

        // The normal flow takes over on the server list, where its questions and errors are.
        override fun connect(serverId: Long) {
            closePublicServers()
            this@AppViewModel.connect(serverId)
        }
    }

    /** What the phone says about the session, beside the session itself. */
    private data class Phone(
        val microphone: MicrophoneNotice,
        val explainNotifications: Boolean,
        val output: OutputState,
        val askOutput: Boolean,
    )

    private val phone = combine(microphone.notice, notifications.explain, outputs.state, outputs.asking, ::Phone)
    private val phoneNow: Phone
        get() = Phone(microphone.notice.value, notifications.explain.value, outputs.state.value, outputs.asking.value)

    val uiState: StateFlow<AppUiState> =
        combine(local, repository.state, connected.local, phone, identities.local, ::merge)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                merge(local.value, repository.state.value, connected.local.value, phoneNow, identities.local.value),
            )

    private fun merge(
        ui: AppUiState,
        session: SessionState,
        connectedLocal: ConnectedLocal,
        phone: Phone,
        identitiesLocal: IdentitiesLocal,
    ): AppUiState {
        val merged = ui.copy(session = session, identities = identitiesUiState(session, identitiesLocal))
        return merged.copy(
            connected = connectedUiState(
                session,
                connectedLocal,
                merged.activeServerName.orEmpty(),
                phone.microphone,
                phone.explainNotifications,
                phone.output,
                phone.askOutput,
            ),
        )
    }

    /** The Chat tab: the chat of the channel the user is in, while that tab is the one on screen. */
    val chat = ChatController(
        chats = repository.chat,
        openChat = repository.state.map { session ->
            session.server?.takeIf { session.showsServer }?.selfChannelId?.let { ChatId.Channel(it) }
        },
        shown = connected.local.map { it.tab == ConnectedTab.Chat && !it.voiceOpen },
        scope = viewModelScope,
    )

    /** Its own flow: a busy chat does not rebuild the channel list, and the other way round. */
    val chatState: StateFlow<ChatUiState?> =
        combine(repository.state, chat.local, uiState.map { it.activeServerName.orEmpty() }.distinctUntilChanged()) {
                session, chatLocal, serverName ->
            chatUiState(session, chatLocal, serverName, ZoneId.systemDefault())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** The number on the Chat tab. */
    val chatBadge: StateFlow<Int> =
        combine(repository.chat.unread, repository.state, connected.local) { unread, session, connectedLocal ->
            chatTabBadge(unread, session, chatTabOpen = connectedLocal.tab == ConnectedTab.Chat)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    /** The Messages tab, message requests, chat requests and blocking. */
    val messages = MessagesController(repository, connected, viewModelScope)

    val messagesState: StateFlow<MessagesUiState?> = combine(
        combine(repository.state, messages.local, ::Pair),
        repository.chat.conversations,
        combine(repository.chat.requests, repository.chat.blocked, ::Pair),
        repository.chat.unread,
        notifications.granted,
    ) { (session, messagesLocal), conversations, (requests, blocked), unread, granted ->
        messagesUiState(session, messagesLocal, conversations, requests, blocked, unread, granted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), currentMessagesState())

    private fun currentMessagesState(): MessagesUiState? = messagesUiState(
        repository.state.value,
        messages.local.value,
        repository.chat.conversations.value,
        repository.chat.requests.value,
        repository.chat.blocked.value,
        repository.chat.unread.value,
        notifications.granted.value,
    )

    /** Crash reports (FR-5.103): the notice on the server list, the list under About and the upload sheet. */
    val crashes = CrashController(crashSource, viewModelScope, io, log = { message -> Log.w(LOG_TAG, message) })

    val crashState: StateFlow<CrashLocal> = crashes.local

    /** The More tab: the activity log and the settings. */
    val more = MoreController(
        repository,
        connected,
        messageSettings,
        viewModelScope,
        AboutController(appInfo, updates, repository, notices, viewModelScope, io = io),
        crashes,
        languages,
    )

    /** "Version X is available" on the server list (FR-5.101); null when there is nothing to offer. */
    val updateNotice: StateFlow<AvailableUpdate?> = more.about.update

    /** The app came to the front: the update check runs when one is due. */
    fun checkForUpdate() = more.about.checkIfDue()

    /** Its own flow: a new activity entry does not rebuild the other tabs. */
    val moreState: StateFlow<MoreUiState?> =
        combine(
            repository.state,
            combine(more.local, more.extras, ::Pair),
            outputs.state,
            messageSettings.senderOnly,
            uiState.map { it.activeServerName.orEmpty() }.distinctUntilChanged(),
        ) { session, (moreLocal, extras), output, senderOnly, serverName ->
            moreUiState(session, moreLocal, serverName, output, senderOnly, extras, more.languageChoice)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            moreUiState(
                repository.state.value,
                more.local.value,
                uiState.value.activeServerName.orEmpty(),
                outputs.state.value,
                messageSettings.senderOnly.value,
            ),
        )

    /**
     * The colours of the shown theme in both versions (FR-5.81): the shown server's own theme
     * while connected to one that has it, otherwise the app theme. Null until the themes were
     * read; the app then draws in its own Mumcord colours.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val appTheme: StateFlow<AppTheme?> =
        repository.state
            // Any change of the themes reads the colours again: a theme of the same id may have
            // been replaced. That is rare and cheap.
            .map { state -> state.themes?.let { it to state.activeServerId?.takeIf { state.showsServer } } }
            .distinctUntilChanged()
            .mapLatest { shown ->
                val (settings, shownServer) = shown ?: return@mapLatest null
                when (val tokens = repository.themeTokens(shownThemeId(settings, shownServer))) {
                    is Outcome.Ok -> AppTheme(settings.mode, tokens.value.first, tokens.value.second)
                    is Outcome.Failed -> null
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The open private conversation: the same chat screen as the Chat tab, for one person. */
    val conversation = ChatController(
        chats = repository.chat,
        openChat = messages.local.map { it.open?.chat },
        shown = combine(connected.local, messages.local) { connectedLocal, messagesLocal ->
            connectedLocal.tab == ConnectedTab.Messages && !connectedLocal.voiceOpen && messagesLocal.open != null
        },
        scope = viewModelScope,
        keepDraft = false,
    )

    val conversationState: StateFlow<ChatUiState?> =
        combine(
            repository.state,
            conversation.local,
            messagesState,
            uiState.map { it.activeServerName.orEmpty() }.distinctUntilChanged(),
        ) { session, chatLocal, messagesUi, serverName ->
            val open = messagesUi?.open
            conversationUiState(
                session = session,
                local = chatLocal,
                chat = open?.chat,
                peerName = open?.name.orEmpty(),
                peerOnline = messagesUi?.openOnline == true,
                serverName = serverName,
                zone = ZoneId.systemDefault(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** The chat controller of the tab on screen; pictures the user picks go to it. */
    val activeChat: ChatController
        get() = if (connected.local.value.tab == ConnectedTab.Messages) conversation else chat

    /** A message notification was tapped: a mention opens the Chat tab, a private message its conversation. */
    fun openChat(chat: ChatId) {
        if (!repository.state.value.showsServer) return
        if (chat is ChatId.Channel) {
            connected.showTab(ConnectedTab.Chat)
        } else {
            connected.showTab(ConnectedTab.Messages)
            messages.openConversation(chat)
        }
    }

    /** The last connect, so "Try again" and the password retry keep a link's channel. */
    private var lastTarget: ConnectTarget? = null

    /** Looking at the address before a connection starts; a newer connect replaces it. */
    private var addressCheck: Job? = null

    init {
        viewModelScope.launch { repository.loadServers() }
        viewModelScope.launch {
            repository.state.map { it.servers }.distinctUntilChanged().collect(::ping)
        }
    }

    // ----- first-start setup -----

    fun selectTalkMode(mode: TalkMode) = local.update { it.copy(talkModeChoice = mode) }

    fun confirmTalkMode() {
        val mode = local.value.talkModeChoice ?: return
        if (local.value.setupSaving) return
        local.update { it.copy(setupSaving = true, setupError = null) }
        viewModelScope.launch {
            when (val saved = repository.setTalkMode(mode)) {
                is Outcome.Ok -> {
                    setupStore.markFirstStartDone()
                    local.update { it.copy(setupDone = true, setupSaving = false) }
                }
                is Outcome.Failed -> local.update { it.copy(setupSaving = false, setupError = saved.error) }
            }
        }
    }

    // ----- server list -----

    /** Asks the servers again for people online and latency; called when the list comes back. */
    fun refreshPings() {
        // While a server is shown nobody sees the list; it is asked again when it comes back.
        if (repository.state.value.showsServer) return
        ping(repository.state.value.servers)
    }

    private fun ping(servers: List<SavedServer>) {
        local.update { ui -> ui.copy(pings = ui.pings.filterKeys { id -> servers.any { it.id == id } }) }
        servers.forEach { server ->
            viewModelScope.launch {
                // The engine reuses a recent answer, so asking again is cheap.
                val state = repository.pingServer(server.id, refresh = false).toPingState()
                local.update { it.copy(pings = it.pings + (server.id to state)) }
            }
        }
    }

    fun toggleEditing() = local.update { it.copy(editing = !it.editing) }

    fun openAddServer() = local.update { it.copy(form = ServerFormState()) }

    fun openEditServer(serverId: Long) {
        val server = server(serverId) ?: return
        val themeId = repository.state.value.themes?.serverThemes?.get(serverId)
        local.update { it.copy(form = ServerFormState.edit(server, themeId)) }
    }

    fun changeForm(change: (ServerFormState) -> ServerFormState) =
        local.update { it.copy(form = it.form?.let(change)) }

    fun closeForm() = local.update { it.copy(form = null) }

    /** Opening the browser is what loads the public list: nothing is requested before (FR-5.9). */
    fun openPublicServers() {
        local.update { it.copy(browsingPublic = true, editing = false) }
        publicServers.open()
    }

    fun closePublicServers() {
        publicServers.close()
        local.update { it.copy(browsingPublic = false) }
    }

    fun saveForm() {
        val form = local.value.form ?: return
        val input = form.toInput()
        if (input == null) {
            changeForm { it.copy(showErrors = true) }
            return
        }
        if (form.saving) return
        changeForm { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val saved = if (form.serverId == null) {
                repository.addServer(input)
            } else {
                repository.updateServer(form.serverId, input)
            }
            // The server's theme is its own setting, saved once the server itself is.
            val themed = if (saved is Outcome.Ok && form.serverId != null && form.themeChanged) {
                repository.setServerTheme(form.serverId, form.themeId)
            } else {
                saved
            }
            when (themed) {
                is Outcome.Ok -> closeForm()
                is Outcome.Failed -> changeForm { it.copy(saving = false, saveError = themed.error) }
            }
        }
    }

    fun requestRemove(serverId: Long) {
        val server = server(serverId) ?: return
        local.update { it.copy(overlay = Overlay.ConfirmRemove(server)) }
    }

    fun confirmRemove() {
        val overlay = local.value.overlay as? Overlay.ConfirmRemove ?: return
        dismissOverlay()
        viewModelScope.launch {
            // A failure means the entry is already gone, which is what was asked for; the list
            // shows what the engine reports.
            repository.removeServer(overlay.server.id)
        }
    }

    fun dismissOverlay() {
        if ((local.value.overlay as? Overlay.SessionEnded)?.alone == true) idle.dismiss()
        local.update { it.copy(overlay = null) }
    }

    // ----- connecting -----

    fun connect(serverId: Long) = request(ConnectTarget(serverId))

    /** One server at a time: while a connection is alive, connecting elsewhere asks first. */
    private fun request(target: ConnectTarget) {
        val session = repository.state.value
        val targetName = server(target.serverId)?.name ?: return
        if (session.isBusy) {
            val currentName = session.servers.firstOrNull { it.id == session.activeServerId }?.name.orEmpty()
            local.update { it.copy(overlay = Overlay.ConfirmSwitch(target, currentName, targetName)) }
        } else {
            start(target)
        }
    }

    fun confirmSwitch() {
        val overlay = local.value.overlay as? Overlay.ConfirmSwitch ?: return
        dismissOverlay()
        viewModelScope.launch {
            repository.disconnect()
            // The engine allows a new connection only once the old one has ended.
            repository.state.first { !it.isBusy }
            start(overlay.target)
        }
    }

    /**
     * Connects, unless the server is on the local network and the app may not reach it yet
     * (Android 17 and later): then the reason is explained first, or the refusal is shown.
     * [suspected] marks a retry of a private address that was out of reach without that access.
     */
    private fun start(target: ConnectTarget, suspected: Boolean = false) {
        val server = server(target.serverId) ?: return
        lastTarget = target
        addressCheck?.cancel()
        local.update {
            it.copy(
                overlay = null,
                editing = false,
                checkingServerId = target.serverId,
                localNetworkDeniedServerId = null,
                localNetworkSuspectServerId = null,
            )
        }
        addressCheck = viewModelScope.launch {
            val check = localNetwork.check(server.address, suspected)
            when (check.step) {
                LocalNetworkStep.Connect -> {
                    local.update {
                        it.copy(
                            checkingServerId = null,
                            localNetworkSuspectServerId = target.serverId.takeIf { check.suspect },
                        )
                    }
                    repository.connectServer(target.serverId, target.password, target.channelPath)
                }
                LocalNetworkStep.Explain -> {
                    // An error of an earlier attempt would otherwise stay behind the question.
                    repository.dismissFailure()
                    local.update {
                        it.copy(checkingServerId = null, overlay = Overlay.LocalNetwork(target, server.name))
                    }
                }
                LocalNetworkStep.Denied -> {
                    repository.dismissFailure()
                    local.update { it.copy(checkingServerId = null, localNetworkDeniedServerId = target.serverId) }
                }
            }
        }
    }

    /** The answer to the system's question for local network access: connect, or say why not. */
    fun answerLocalNetworkPrompt(answer: LocalNetworkAnswer) {
        val overlay = local.value.overlay as? Overlay.LocalNetwork ?: return
        if (answer.granted) {
            start(overlay.target)
        } else {
            localNetwork.promptBlocked = answer.blocksPrompt
            local.update { it.copy(overlay = null, localNetworkDeniedServerId = overlay.target.serverId) }
        }
    }

    // ----- microphone and notifications -----

    /** The explanation was put away: the user keeps listening and a notice says so. */
    fun microphoneNotNow() = microphone.notNow()

    fun answerMicrophonePrompt(answer: MicrophoneAnswer) = microphone.answer(answer)

    /** Back on screen: the permission may have changed in the system settings. */
    fun recheckMicrophone() = microphone.recheck()

    /** The explanation was put away: the session goes on without its notification. */
    fun notificationsNotNow() = notifications.notNow()

    fun answerNotificationPrompt(granted: Boolean) = notifications.answer(granted)

    /** Connects again to the server of the session that the system cut off. */
    fun reconnectAfterSessionEnded() {
        val overlay = local.value.overlay as? Overlay.SessionEnded ?: return
        dismissOverlay()
        connect(overlay.serverId)
    }

    /** Ends the connection, also while it is still being made. */
    fun disconnect() {
        addressCheck?.cancel()
        local.update { it.copy(checkingServerId = null) }
        viewModelScope.launch { repository.disconnect() }
    }

    fun answerCertificatePrompt(fingerprint: String, trust: Boolean) {
        viewModelScope.launch { repository.answerCertificatePrompt(fingerprint, trust) }
    }

    /**
     * The action of a shown error: connects again, asks for the password after a wrong one, or
     * reads the server list again when that was what failed.
     */
    fun retry() {
        val deniedServerId = local.value.localNetworkDeniedServerId
        if (deniedServerId != null) {
            // Checks again: access may have been allowed in the system settings meanwhile.
            start(lastTarget?.takeIf { it.serverId == deniedServerId } ?: ConnectTarget(deniedServerId))
            return
        }
        val session = repository.state.value
        val failed = session.phase as? ConnectionPhase.Failed ?: return
        val serverId = session.activeServerId
        val serverName = serverId?.let(::server)?.name
        if (serverId == null || serverName == null) {
            repository.dismissFailure()
            viewModelScope.launch { repository.loadServers() }
            return
        }
        val target = lastTarget?.takeIf { it.serverId == serverId } ?: ConnectTarget(serverId)
        if (failed.error == ConnectionError.WrongPassword) {
            local.update { it.copy(overlay = Overlay.EnterPassword(target.copy(password = null), serverName)) }
        } else {
            start(
                target.copy(password = null),
                suspected = local.value.copy(session = session).failure?.localNetwork == LocalNetworkHint.Suspected,
            )
        }
    }

    fun changePassword(password: String) = local.update {
        val overlay = it.overlay as? Overlay.EnterPassword ?: return@update it
        it.copy(overlay = overlay.copy(password = password))
    }

    fun submitPassword() {
        val overlay = local.value.overlay as? Overlay.EnterPassword ?: return
        if (overlay.password.isEmpty()) return
        start(overlay.target.copy(password = overlay.password))
    }

    fun dismissError() {
        local.update { it.copy(localNetworkDeniedServerId = null) }
        repository.dismissFailure()
    }

    // ----- mumble:// links -----

    /** Shows the target of an opened link. Nothing is saved or connected before the user confirms. */
    fun openLink(url: String) {
        viewModelScope.launch {
            // A saved entry of the same server is reused, so the list must be known first.
            repository.loadServers()
            val overlay = when (val link = repository.parseLink(url)) {
                is Outcome.Ok -> Overlay.Link(linkConfirmState(link.value, repository.state.value.servers))
                is Outcome.Failed -> Overlay.InvalidLink
            }
            local.update { it.copy(overlay = overlay) }
        }
    }

    fun changeLinkUsername(username: String) = local.update {
        val overlay = it.overlay as? Overlay.Link ?: return@update it
        it.copy(overlay = Overlay.Link(overlay.state.copy(typedUsername = username)))
    }

    fun confirmLink() {
        val state = (local.value.overlay as? Overlay.Link)?.state ?: return
        if (!state.canConnect) return
        val saved = state.savedServer
        if (saved != null) {
            request(ConnectTarget(saved.id, channelPath = state.link.channelPath))
            return
        }
        local.update { it.copy(overlay = Overlay.Link(state.copy(working = true, error = null))) }
        viewModelScope.launch {
            when (val added = repository.addServer(state.toInput())) {
                is Outcome.Ok -> {
                    // The list event may still be on its way; the row is needed to name the server.
                    repository.state.first { session -> session.servers.any { it.id == added.value.id } }
                    request(ConnectTarget(added.value.id, channelPath = state.link.channelPath))
                }
                is Outcome.Failed ->
                    local.update { it.copy(overlay = Overlay.Link(state.copy(working = false, error = added.error))) }
            }
        }
    }

    private fun server(serverId: Long): SavedServer? =
        repository.state.value.servers.firstOrNull { it.id == serverId }

    companion object {
        /** Keeps the state alive across a rotation without restarting the collection. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private const val LOG_TAG = "Mumcord"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY]) as MumcordApplication
                AppViewModel(
                    application.sessionRepository,
                    PreferencesSetupStore(application),
                    application.localNetworkGateway,
                    application.voiceAudio,
                    application.voiceCall.notifications,
                    application.voiceCall.takeSessionEndedNotice(),
                    application.messageNotificationSettings,
                    application.voiceOutput,
                    application.idleDisconnect,
                    application.cameraGateway,
                    application.appInfo,
                    application.updateChecker,
                    NoticesSource.assets(application.assets),
                    crashSource = BridgeCrashSource(application.sessionRepository),
                    streamsAutoplay = application.streamsAutoplay,
                    streamVideo = application.streamVideo,
                    languages = AppLanguages.of(application),
                ).also {
                    // Start with the first screen and then follow the session by themselves.
                    application.networkSession
                    application.messageNotifications
                }
            }
        }
    }
}
