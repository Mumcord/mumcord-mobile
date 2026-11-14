// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.link.linkConfirmState
import app.mumcord.mobile.ui.servers.ServerFormState
import app.mumcord.mobile.ui.servers.toPingState
import app.mumcord.mobile.ui.setup.PreferencesSetupStore
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
) : ViewModel() {
    private val local = MutableStateFlow(AppUiState(setupDone = setupStore.firstStartDone))


    /** The connected screens' own state and actions. */
    val connected = ConnectedController(repository, viewModelScope)

    val uiState: StateFlow<AppUiState> =
        combine(local, repository.state, connected.local, ::merge)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                merge(local.value, repository.state.value, connected.local.value),
            )

    private fun merge(ui: AppUiState, session: SessionState, connectedLocal: ConnectedLocal): AppUiState {
        val merged = ui.copy(session = session)
        return merged.copy(connected = connectedUiState(session, connectedLocal, merged.activeServerName.orEmpty()))
    }

    /** The last connect, so "Try again" and the password retry keep a link's channel. */
    private var lastTarget: ConnectTarget? = null

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
    fun refreshPings() = ping(repository.state.value.servers)

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
        local.update { it.copy(form = ServerFormState.edit(server)) }
    }

    fun changeForm(change: (ServerFormState) -> ServerFormState) =
        local.update { it.copy(form = it.form?.let(change)) }

    fun closeForm() = local.update { it.copy(form = null) }

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
            when (saved) {
                is Outcome.Ok -> closeForm()
                is Outcome.Failed -> changeForm { it.copy(saving = false, saveError = saved.error) }
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

    fun dismissOverlay() = local.update { it.copy(overlay = null) }

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

    private fun start(target: ConnectTarget) {
        lastTarget = target
        local.update { it.copy(overlay = null, editing = false) }
        viewModelScope.launch {
            repository.connectServer(target.serverId, target.password, target.channelPath)
        }
    }

    /** Ends the connection, also while it is still being made. */
    fun disconnect() {
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
            start(target.copy(password = null))
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

    fun dismissError() = repository.dismissFailure()

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

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY]) as MumcordApplication
                AppViewModel(application.sessionRepository, PreferencesSetupStore(application))
            }
        }
    }
}
