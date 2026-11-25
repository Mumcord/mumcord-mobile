// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import Observation

/// What the user does on the More tab and on the server list's More screen: the profile, the
/// themes, the activity log and the settings. Every setting is saved at once; a failure is shown
/// like a refusal. The start and the end of a connection start it afresh.
///
/// An object of its own, apart from `AppModel`, like the Messages tab: only the More tab's own
/// views read it.
@MainActor
@Observable
final class MoreController {
    private(set) var local = MoreLocal()
    /// Notifications name the sender and leave the message out.
    private(set) var senderOnly: Bool
    /// The user's own profile and its editor (FR-5.80).
    let profile: ProfileController
    /// The themes screen (FR-5.81).
    let themes: ThemesController

    /// Switches the talk mode as the voice view does, where a held talk button is let go. Set
    /// by the model that owns the voice view.
    @ObservationIgnored var switchTalkMode: (TalkMode) -> Void = { _ in }
    /// Opens the app's page in the system settings, where iOS offers its language. Set by the
    /// app; nothing in the tests of this class.
    @ObservationIgnored var openLanguageSettings: () -> Void = {}

    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let messageSettings: MessageNotificationSettings
    @ObservationIgnored private let languages: AppLanguages
    /// The last change of a setting; the next one waits for it, so that a switch flipped twice
    /// quickly ends as it was left. (The store keeps two changes from saving over each other,
    /// but not in the order they were made.)
    @ObservationIgnored private var save: Task<Void, Never>?

    init(store: SessionStore, messageSettings: MessageNotificationSettings, languages: AppLanguages = .phoneOnly) {
        self.store = store
        self.messageSettings = messageSettings
        self.languages = languages
        senderOnly = messageSettings.senderOnly
        profile = ProfileController(store: store)
        themes = ThemesController(store: store)
        let states = store.states()
        Task { [weak self] in
            // Only the start and the end of a connection start the screens afresh: the server
            // list's More screen changes settings without one, and each change is a new state.
            var shown = false
            for await state in states {
                guard let self else { return }
                guard state.showsServer != shown else { continue }
                shown = state.showsServer
                if self.local != MoreLocal() { self.local = MoreLocal() }
                self.themes.reset()
            }
        }
        profile.onClosed = { [weak self] in self?.back() }
        // The system settings can change the app's language; iOS then starts the app afresh, so
        // the shared setting follows it once the saved settings are read at the start.
        let settingsStates = store.states()
        Task { [weak self] in
            for await state in settingsStates {
                guard let settings = state.appSettings else { continue }
                self?.syncLanguage(saved: settings.language)
                return
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

    /// The More tab as a value; with `fromServerList` and no server shown, the server list's
    /// More screen. Nil while neither is shown.
    func uiState(
        session: SessionState,
        serverName: String,
        output: OutputState,
        fromServerList: Bool = false
    ) -> MoreUiState? {
        moreUiState(
            session: session,
            local: local,
            serverName: serverName,
            output: output,
            senderOnly: senderOnly,
            extras: MoreExtras(profile: profile.own, profileEdit: profile.edit, themes: themes.local),
            fromServerList: fromServerList
        )
    }

    /// A sheet of the profile or the themes screen is open over the screen.
    var sheetOpen: Bool {
        switch local.page {
        case .editProfile: profile.edit?.sheet != nil
        case .themes: themes.local.sheet != nil
        case .main, .activity, .settings: false
        }
    }

    // MARK: Profile and themes

    func openProfile() {
        profile.open()
        local.page = .editProfile
    }

    func openThemes() {
        local.page = .themes
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
        switch local.page {
        case .themes: themes.reset()
        case .editProfile: profile.discard()
        case .main, .activity, .settings: break
        }
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

    // MARK: Language

    /// Saves the system's language for the app as the shared setting where they differ.
    func syncLanguage(saved: String?) {
        let system = languages.current()
        if saved != system { changeApp { $0.language = system } }
    }
}
