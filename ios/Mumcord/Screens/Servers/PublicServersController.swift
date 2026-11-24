// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// The public server browser (FR-5.9): the list is loaded only when the user opens it, searched
/// and filtered on the phone, and only the rows on screen are pinged.
///
/// An object of its own, apart from `AppModel`, like the Messages tab: only the browser reads
/// it, so arriving pings do not redraw the other screens.
@MainActor
@Observable
final class PublicServersController {
    private(set) var local = PublicServersLocal()
    /// The saved servers, for "Connect" on the rows already in the server list.
    private var saved: [SavedServer]

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private var load: Task<Void, Never>?
    @ObservationIgnored private var shownKeys: [String] = []
    @ObservationIgnored private lazy var pings = PublicPingScheduler(
        ping: { [store] server in PublicPing(await store.pingPublicServer(host: server.host, port: server.port)) },
        onResult: { [weak self] key, ping in self?.local.pings[key] = ping }
    )

    init(store: SessionStore) {
        self.store = store
        saved = store.state.servers
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                // Only a change of the list is taken, so the browser is not drawn again for other news.
                if state.servers != self.saved { self.saved = state.servers }
            }
        }
    }

    /// The browser as the screen shows it.
    var uiState: PublicServersUiState {
        publicServersUiState(local, saved: saved)
    }

    /// The browser was opened: the list is loaded the first time, then the one loaded before is
    /// shown.
    func open() {
        if local.servers == nil { startLoad(refresh: false) }
        pings.start()
    }

    /// The browser was closed: no more pings, and the next opening starts without search and
    /// filter.
    func close() {
        pings.stop()
        shownKeys = []
        local.search = ""
        local.country = nil
        local.refreshFailed = false
        local.addFailed = false
    }

    /// The app left the screen while the browser is open.
    func pausePings() {
        pings.stop()
    }

    /// The app is on screen again with the browser open: the rows on screen are the ones before.
    func resumePings() {
        pings.start()
        setVisible(shownKeys)
    }

    /// "Refresh", and "Try again" after a failed first load.
    func refresh() {
        startLoad(refresh: local.servers != nil)
    }

    func search(_ text: String) {
        local.search = text
    }

    func selectCountry(_ code: String?) {
        local.country = code
    }

    /// The rows on screen, by `PublicServer.key`, top to bottom.
    func showing(_ keys: [String]) {
        // The list reports its rows whenever it moves; the same rows change nothing.
        guard keys != shownKeys else { return }
        shownKeys = keys
        setVisible(keys)
    }

    private func setVisible(_ keys: [String]) {
        let byKey = Dictionary((local.servers ?? []).map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        pings.setVisible(keys.compactMap { byKey[$0] })
    }

    /// Saves the listed server to the server list, with the name of the default identity as the
    /// user name, as the desktop app does. Connecting then follows the normal flow.
    func add(_ key: String) {
        guard let server = local.servers?.first(where: { $0.key == key }), !local.adding.contains(key) else { return }
        local.adding.insert(key)
        local.addFailed = false
        let input = input(server)
        Task {
            let added = await store.addServer(input)
            local.adding.remove(key)
            if case .failed = added { local.addFailed = true }
        }
    }

    private func input(_ server: PublicServer) -> ServerInput {
        let identity = store.state.identities?.first { $0.isDefault }
        let name = identity.map { $0.commonName.trimmingCharacters(in: .whitespaces).isEmpty ? $0.label : $0.commonName }
        return ServerInput(
            name: server.name,
            address: server.host,
            port: server.port,
            username: name ?? "",
            password: .keep,
            identityId: nil
        )
    }

    private func startLoad(refresh: Bool) {
        if load != nil { return }
        local.loading = true
        local.loadFailed = false
        local.refreshFailed = false
        load = Task {
            let loaded = await store.publicServers(refresh: refresh)
            load = nil
            switch loaded {
            case .ok(let servers):
                local.servers = servers
                local.loading = false
                local.pings = [:]
                // The rows on screen are taken from the new list, then all asked again.
                setVisible(shownKeys)
                pings.reset()
            case .failed:
                // The last list stays; only a first load without any list is a failure screen.
                local.loading = false
                local.loadFailed = local.servers == nil
                local.refreshFailed = local.servers != nil
            }
        }
    }
}
