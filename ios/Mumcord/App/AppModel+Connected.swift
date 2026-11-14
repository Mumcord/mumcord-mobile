// SPDX-License-Identifier: AGPL-3.0-only

/// What the user does on the connected screens: tabs, folding, search, joining, the sheets.
/// The state lives in the model, so folded channels and the tab survive a rotation; the end of
/// a connection starts it afresh.
extension AppModel {
    static let maxVolumePercent = 200

    func selectTab(_ tab: ConnectedTab) {
        connectedLocal.tab = tab
        syncChat()
    }

    func toggleCollapsed(_ channelId: UInt32) {
        if connectedLocal.collapsed.contains(channelId) {
            connectedLocal.collapsed.remove(channelId)
        } else {
            connectedLocal.collapsed.insert(channelId)
        }
        connectedLocal.menuChannelId = nil
    }

    /// Joins a channel; the own channel is left alone. A needed password is asked for by an event.
    func join(_ channelId: UInt32) {
        connectedLocal.menuChannelId = nil
        if store.state.server?.selfChannelId == channelId { return }
        Task { await store.joinChannel(channelId, password: nil) }
    }

    func openChannelMenu(_ channelId: UInt32) {
        connectedLocal.menuChannelId = channelId
    }

    func openSearch() {
        connectedLocal.searching = true
    }

    func closeSearch() {
        connectedLocal.searching = false
        connectedLocal.query = ""
    }

    func changeQuery(_ query: String) {
        connectedLocal.query = query
    }

    /// Opens the person sheet. The own entry has none: its controls only make sense for others.
    func openPerson(_ session: UInt32) {
        if store.state.server?.selfSession == session { return }
        connectedLocal.personSession = session
        connectedLocal.volumeDraft = nil
    }

    func changeVolume(_ percent: Int) {
        guard let session = connectedLocal.personSession else { return }
        let clamped = min(max(percent, 0), Self.maxVolumePercent)
        connectedLocal.volumeDraft = clamped
        Task { await store.setPersonVolume(session, percent: clamped) }
    }

    func setLocalMute(_ muted: Bool) {
        guard let session = connectedLocal.personSession else { return }
        Task { await store.setPersonLocalMute(session, muted: muted) }
    }

    func changeChannelPassword(_ password: String) {
        connectedLocal.password = password
    }

    /// Tries the typed channel password; the sheet stays until the server answers.
    func submitChannelPassword() {
        guard let prompt = store.state.channelPassword else { return }
        let password = connectedLocal.password
        if password.isEmpty { return }
        connectedLocal.password = ""
        Task { await store.joinChannel(prompt.channelId, password: password) }
    }

    /// Closes whichever sheet is open; an open password question is given up.
    func dismissSheet() {
        if store.state.channelPassword != nil {
            store.dismissChannelPassword()
            connectedLocal.password = ""
        } else {
            connectedLocal.personSession = nil
            connectedLocal.volumeDraft = nil
            connectedLocal.menuChannelId = nil
        }
    }

    func dismissDenied() {
        store.dismissDenied()
    }
}
