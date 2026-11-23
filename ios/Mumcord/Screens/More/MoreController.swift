// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// What the user does on the More tab: the activity log and the settings. Every setting is
/// saved at once; a failure is shown like a refusal. The end of a connection starts it afresh.
///
/// An object of its own, apart from `AppModel`, like the Messages tab: only the More tab's own
/// views read it.
@MainActor
@Observable
final class MoreController {
    private(set) var local = MoreLocal()
    /// Notifications name the sender and leave the message out.
    private(set) var senderOnly: Bool

    /// Switches the talk mode as the voice view does, where a held talk button is let go. Set
    /// by the model that owns the voice view.
    @ObservationIgnored var switchTalkMode: (TalkMode) -> Void = { _ in }

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let messageSettings: MessageNotificationSettings
    /// The last change of a setting; the next one waits for it, so that a switch flipped twice
    /// quickly ends as it was left. (The store keeps two changes from saving over each other,
    /// but not in the order they were made.)
    @ObservationIgnored private var save: Task<Void, Never>?

    init(store: SessionStore, messageSettings: MessageNotificationSettings) {
        self.store = store
        self.messageSettings = messageSettings
        senderOnly = messageSettings.senderOnly
        let states = store.states()
        Task { [weak self] in
            for await state in states {
                guard let self else { return }
                if !state.showsServer, self.local != MoreLocal() { self.local = MoreLocal() }
            }
        }
        // The setting is read from the engine, which can take longer than drawing the switch.
        let senderOnlyStates = messageSettings.senderOnlyStates()
        Task { [weak self] in
            for await senderOnly in senderOnlyStates {
                self?.senderOnly = senderOnly
            }
        }
    }

    /// The More tab as a value, or nil while no server is shown.
    func uiState(session: SessionState, serverName: String, output: OutputState) -> MoreUiState? {
        moreUiState(session: session, local: local, serverName: serverName, output: output, senderOnly: senderOnly)
    }

    // MARK: Activity

    func openActivity() {
        local.page = .activity
        local.seenActivityId = newestActivity
    }

    func selectFilter(_ filter: ActivityFilter) {
        local.filter = filter
    }

    /// Back to the list of the More tab; what was in the log until now counts as seen.
    func back() {
        if local.page == .activity { local.seenActivityId = newestActivity }
        local.page = .main
        local.sheet = nil
    }

    private var newestActivity: UInt64? {
        store.state.activity.first?.id
    }

    // MARK: Settings

    /// Opens the settings and reads the voice settings, which have no event of their own.
    func openSettings() {
        local.page = .settings
        local.noiseSuppression = nil
        Task {
            guard let voice = await store.voiceSettings() else {
                store.reportFailure()
                return
            }
            // A switch flipped meanwhile is newer than what was read.
            if local.noiseSuppression == nil { local.noiseSuppression = voice.noiseSuppression }
        }
    }

    func openSheet(_ sheet: SettingsSheet) {
        local.sheet = sheet
    }

    func closeSheet() {
        local.sheet = nil
    }

    func changeMobile(_ change: @escaping @Sendable (inout MobileSettings) -> Void) {
        saveNext { store in await store.updateMobileSettings(change) }
    }

    func changeApp(_ change: @escaping @Sendable (inout AppSettings) -> Void) {
        saveNext { store in await store.updateAppSettings(change) }
    }

    private func saveNext(_ command: @escaping @Sendable (SessionStore) async -> Outcome<Void>) {
        let previous = save
        save = Task { [store] in
            await previous?.value
            if case .failed = await command(store) { store.reportFailure() }
        }
    }

    func setNoiseSuppression(_ on: Bool) {
        local.noiseSuppression = on
        Task {
            if case .failed = await store.setNoiseSuppression(on) {
                local.noiseSuppression = !on
                store.reportFailure()
            }
        }
    }

    func setSenderOnly(_ senderOnly: Bool) {
        messageSettings.setSenderOnly(senderOnly)
    }

    /// The same switch as in the voice view.
    func selectTalkMode(_ mode: TalkMode) {
        closeSheet()
        switchTalkMode(mode)
    }

    func selectDefaultOutput(_ output: DefaultOutput) {
        closeSheet()
        changeMobile { $0.defaultOutput = output }
    }

    func selectHistoryAge(_ days: UInt32?) {
        closeSheet()
        changeApp { $0.historyMaxAgeDays = days }
    }
}
