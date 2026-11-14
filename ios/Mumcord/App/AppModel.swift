// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// The state of the app and what the user can do with it. Views read `uiState` and call the
/// methods; none of them talks to the engine.
///
/// This file holds everything before the connected screens: first-start setup, the server list,
/// the add and edit form, connecting with its questions and errors, and opened links. The
/// connected screens' part is in `AppModel+Connected.swift`.
@MainActor
@Observable
final class AppModel {
    @ObservationIgnored let store: SessionStore
    @ObservationIgnored private let setupStore: SetupStore
    @ObservationIgnored let microphone: MicrophoneAccess
    @ObservationIgnored private let localNetwork: LocalNetworkGateway
    /// Opens the app's page in the system settings.
    @ObservationIgnored let openSettings: () -> Void
    /// The look at a server's address before its connection starts.
    @ObservationIgnored private var addressCheck: Task<Void, Never>?
    /// The microphone level, 0 to 100. An object of its own: it changes up to 20 times a
    /// second, and only the level meter reads it.
    @ObservationIgnored let inputLevel = InputLevel()
    /// The last connect, so "Try again" and the password retry keep a link's channel.
    @ObservationIgnored private var lastTarget: ConnectTarget?

    /// The app's own part of the state; `session` and `connected` in it are not used.
    private var local: AppUiState
    private var session = SessionState()
    var connectedLocal = ConnectedLocal()
    private var microphoneNotice = MicrophoneNotice.none

    init(
        store: SessionStore,
        setupStore: SetupStore,
        microphone: MicrophoneAccess,
        localNetwork: LocalNetworkGateway,
        openSettings: @escaping () -> Void
    ) {
        self.store = store
        self.setupStore = setupStore
        self.microphone = microphone
        self.localNetwork = localNetwork
        self.openSettings = openSettings
        local = AppUiState(setupDone: setupStore.firstStartDone)
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                self.apply(state)
            }
        }
        let notices = microphone.notices()
        Task { [weak self] in
            for await notice in notices {
                guard let self else { return }
                self.microphoneNotice = notice
            }
        }
        let levels = store.inputLevels()
        Task { [inputLevel] in
            for await percent in levels {
                inputLevel.percent = percent
            }
        }
        Task { await store.loadServers() }
    }

    var uiState: AppUiState {
        var merged = local
        merged.session = session
        merged.connected = connectedUiState(
            session: session,
            local: connectedLocal,
            serverName: merged.activeServerName ?? "",
            microphone: microphoneNotice
        )
        return merged
    }

    private func apply(_ state: SessionState) {
        let serversChanged = state.servers != session.servers
        let previousPhase = session.phase
        session = state
        if serversChanged { ping(state.servers) }
        if !state.showsServer {
            // The end of a connection starts the connected screens afresh.
            releaseHolds()
            if connectedLocal != ConnectedLocal() { connectedLocal = ConnectedLocal() }
        } else if let target = connectedLocal.whisperTarget, state.server?.whisperName(target) == nil {
            // The whisper bar goes away with the person or channel it is for.
            closeWhisper()
        }
        if state.phase != previousPhase { askSystemAboutLocalNetwork(state) }
    }

    /// After a server on a local or private address could not be reached: asks the system
    /// whether it refuses local network access, which is the only moment iOS tells. The error
    /// is on screen meanwhile; it only becomes more exact.
    private func askSystemAboutLocalNetwork(_ state: SessionState) {
        guard state.phase == .failed(.serverUnreachable),
              let serverId = state.activeServerId,
              serverId == local.localNetworkSuspectServerId,
              let server = server(serverId)
        else { return }
        Task {
            guard await localNetwork.accessRefused(host: server.address, port: server.port) else { return }
            // Only while that failure is still the one on screen.
            if session.phase == .failed(.serverUnreachable), session.activeServerId == serverId,
               local.localNetworkSuspectServerId == serverId {
                local.localNetworkDeniedServerId = serverId
            }
        }
    }

    // MARK: First-start setup

    func selectTalkMode(_ mode: TalkMode) {
        local.talkModeChoice = mode
    }

    func confirmTalkMode() {
        guard let mode = local.talkModeChoice, !local.setupSaving else { return }
        local.setupSaving = true
        local.setupError = nil
        Task {
            switch await store.setTalkMode(mode) {
            case .ok:
                setupStore.markFirstStartDone()
                local.setupDone = true
                local.setupSaving = false
            case .failed(let error):
                local.setupSaving = false
                local.setupError = error
            }
        }
    }

    // MARK: Server list

    /// Asks the servers again for people online and latency; called when the app comes back.
    func refreshPings() {
        ping(store.state.servers)
    }

    private func ping(_ servers: [SavedServer]) {
        local.pings = local.pings.filter { id, _ in servers.contains { $0.id == id } }
        for server in servers {
            Task {
                // The engine reuses a recent answer, so asking again is cheap.
                let state = PingState(await store.pingServer(server.id, refresh: false))
                local.pings[server.id] = state
            }
        }
    }

    func toggleEditing() {
        local.editing.toggle()
    }

    func openAddServer() {
        local.form = ServerFormState()
    }

    func openEditServer(_ serverId: Int64) {
        guard let server = server(serverId) else { return }
        local.form = .edit(server)
    }

    func changeForm(_ change: (inout ServerFormState) -> Void) {
        guard var form = local.form else { return }
        change(&form)
        local.form = form
    }

    func closeForm() {
        local.form = nil
    }

    func saveForm() {
        guard let form = local.form else { return }
        guard let input = form.toInput() else {
            changeForm { $0.showErrors = true }
            return
        }
        if form.saving { return }
        changeForm {
            $0.saving = true
            $0.saveError = nil
        }
        Task {
            let saved: Outcome<SavedServer>
            if let serverId = form.serverId {
                saved = await store.updateServer(serverId, input)
            } else {
                saved = await store.addServer(input)
            }
            switch saved {
            case .ok:
                closeForm()
            case .failed(let error):
                changeForm {
                    $0.saving = false
                    $0.saveError = error
                }
            }
        }
    }

    func requestRemove(_ serverId: Int64) {
        guard let server = server(serverId) else { return }
        local.overlay = .confirmRemove(server: server)
    }

    func confirmRemove() {
        guard case .confirmRemove(let server) = local.overlay else { return }
        dismissOverlay()
        Task {
            // A failure means the entry is already gone, which is what was asked for; the list
            // shows what the engine reports.
            _ = await store.removeServer(server.id)
        }
    }

    func dismissOverlay() {
        local.overlay = nil
    }

    // MARK: Connecting

    func connect(_ serverId: Int64) {
        request(ConnectTarget(serverId: serverId))
    }

    /// One server at a time: while a connection is alive, connecting elsewhere asks first.
    private func request(_ target: ConnectTarget) {
        let session = store.state
        guard let targetName = server(target.serverId)?.name else { return }
        if session.isBusy {
            let currentName = session.servers.first { $0.id == session.activeServerId }?.name ?? ""
            local.overlay = .confirmSwitch(target: target, currentName: currentName, targetName: targetName)
        } else {
            start(target)
        }
    }

    func confirmSwitch() {
        guard case .confirmSwitch(let target, _, _) = local.overlay else { return }
        dismissOverlay()
        Task {
            await store.disconnect()
            // The engine allows a new connection only once the old one has ended.
            _ = await store.first { !$0.isBusy }
            start(target)
        }
    }

    /// Connects, unless the server is on the local network and the user has not been told yet
    /// that iOS will ask for access to it: then the reason is explained first (FR-5.8).
    /// `explained` is true when that explanation has just been continued.
    private func start(_ target: ConnectTarget, explained: Bool = false) {
        guard let server = server(target.serverId) else { return }
        lastTarget = target
        addressCheck?.cancel()
        local.overlay = nil
        local.editing = false
        local.checkingServerId = target.serverId
        local.localNetworkSuspectServerId = nil
        local.localNetworkDeniedServerId = nil
        addressCheck = Task {
            let check = await localNetwork.check(host: server.address)
            if Task.isCancelled { return }
            local.checkingServerId = nil
            if check.step == .explain && !explained {
                // An error of an earlier attempt would otherwise stay behind the question.
                store.dismissFailure()
                local.overlay = .localNetwork(target: target, serverName: server.name)
                return
            }
            local.localNetworkSuspectServerId = check.suspect ? target.serverId : nil
            await store.connectServer(target.serverId, password: target.password, channelPath: target.channelPath)
        }
    }

    /// The explanation was read: connect, which makes iOS ask for local network access.
    func continueLocalNetwork() {
        guard case .localNetwork(let target, _) = local.overlay else { return }
        localNetwork.explained = true
        start(target, explained: true)
    }

    /// Ends the connection, also while it is still being made.
    func disconnect() {
        addressCheck?.cancel()
        local.checkingServerId = nil
        Task { await store.disconnect() }
    }

    func answerCertificatePrompt(fingerprint: String, trust: Bool) {
        Task { await store.answerCertificatePrompt(fingerprint: fingerprint, trust: trust) }
    }

    /// The action of a shown error: connects again, asks for the password after a wrong one, or
    /// reads the server list again when that was what failed.
    func retry() {
        let session = store.state
        guard case .failed(let error) = session.phase else { return }
        guard let serverId = session.activeServerId, let serverName = server(serverId)?.name else {
            store.dismissFailure()
            Task { await store.loadServers() }
            return
        }
        var target = lastTarget.flatMap { $0.serverId == serverId ? $0 : nil } ?? ConnectTarget(serverId: serverId)
        target.password = nil
        if error == .wrongPassword {
            local.overlay = .enterPassword(target: target, serverName: serverName, password: "")
        } else {
            start(target)
        }
    }

    func changePassword(_ password: String) {
        guard case .enterPassword(let target, let serverName, _) = local.overlay else { return }
        local.overlay = .enterPassword(target: target, serverName: serverName, password: password)
    }

    func submitPassword() {
        guard case .enterPassword(var target, _, let password) = local.overlay, !password.isEmpty else { return }
        target.password = password
        start(target)
    }

    func dismissError() {
        local.localNetworkSuspectServerId = nil
        local.localNetworkDeniedServerId = nil
        store.dismissFailure()
    }

    // MARK: Microphone

    /// The explanation was put away: the user keeps listening and a notice says so.
    func microphoneNotNow() {
        microphone.notNow()
    }

    /// What the explanation's button and the notice do: lets the system ask, or opens the
    /// system settings once the system no longer asks.
    func requestMicrophone() {
        if microphoneNotice == .blocked {
            openSettings()
        } else {
            microphone.ask()
        }
    }

    /// The app is on screen again: the permission may have changed in the system settings.
    func recheckMicrophone() {
        microphone.recheck()
    }

    // MARK: mumble:// links

    /// Shows the target of an opened link. Nothing is saved or connected before the user confirms.
    func openLink(_ url: String) {
        Task {
            // A saved entry of the same server is reused, so the list must be known first.
            await store.loadServers()
            switch store.parseLink(url) {
            case .ok(let link):
                local.overlay = .link(linkConfirmState(link: link, servers: store.state.servers))
            case .failed:
                local.overlay = .invalidLink
            }
        }
    }

    func changeLinkUsername(_ username: String) {
        guard case .link(var state) = local.overlay else { return }
        state.typedUsername = username
        local.overlay = .link(state)
    }

    func confirmLink() {
        guard case .link(var state) = local.overlay, state.canConnect else { return }
        if let saved = state.savedServer {
            request(ConnectTarget(serverId: saved.id, channelPath: state.link.channelPath))
            return
        }
        state.working = true
        state.error = nil
        local.overlay = .link(state)
        Task {
            switch await store.addServer(state.toInput()) {
            case .ok(let added):
                // The list event may still be on its way; the row is needed to name the server.
                _ = await store.first { session in session.servers.contains { $0.id == added.id } }
                request(ConnectTarget(serverId: added.id, channelPath: state.link.channelPath))
            case .failed(let error):
                state.working = false
                state.error = error
                local.overlay = .link(state)
            }
        }
    }

    private func server(_ serverId: Int64) -> SavedServer? {
        store.state.servers.first { $0.id == serverId }
    }
}
