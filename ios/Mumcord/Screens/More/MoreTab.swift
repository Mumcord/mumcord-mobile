// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The More tab: connects its screens to the model. It reads the tab's own state, so a new
/// activity entry redraws this view and not the channel list. With `onBack` it is the server
/// list's More screen, without a connection.
struct MoreTab: View {
    let model: AppModel
    let onDisconnect: () -> Void
    var onBack: (() -> Void)?
    @State private var clock = ChatClock()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if let state = model.moreUiState {
                switch state.page {
                case .activity:
                    ActivityScreen(state: state, actions: model.more, clock: clock)
                case .settings:
                    // Nil for the moment the settings are being read.
                    if let settings = state.settings {
                        SettingsScreen(state: settings, actions: model.more, inputLevel: model.inputLevel)
                    }
                case .editProfile:
                    // Nil for the moment the profile is being read.
                    if let edit = state.profileEdit {
                        EditProfileScreen(state: edit, actions: model.more.profile)
                    }
                case .themes:
                    if let themes = state.themes {
                        ThemesScreen(state: themes, actions: model.more.themes, onBack: model.more.back)
                    }
                case .main:
                    MoreBlockedHost(model: model) {
                        MoreScreen(
                            state: state,
                            actions: model.more,
                            onBlocked: model.messages.openBlocked,
                            onIdentities: model.identities.open,
                            onDisconnect: onDisconnect,
                            onBack: onBack
                        )
                    }
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // The day may have changed while the app was away.
            if phase == .active { clock = ChatClock() }
        }
    }
}

/// The sheet of a setting, of the profile or of the themes when one is open, else the sheets of
/// messages and chat, over the whole screen. There is never more than one. Also over the
/// server list's More screen, which has no messages or chat.
struct MoreOverlayHost: View {
    let model: AppModel

    var body: some View {
        let state = model.moreUiState
        let fromServerList = state?.connected == false
        let more = fromServerList || model.connectedLocal.tab == .more ? state : nil
        if let edit = more?.profileEdit {
            // Also without an open sheet: it holds the photo library and the camera.
            ProfileOverlays(state: edit, actions: model.more.profile)
        } else if let themes = more?.themes, themes.sheet != nil {
            ThemesSheetHost(state: themes, actions: model.more.themes)
        } else if let settings = state?.settings, settings.sheet != nil {
            SettingsSheetHost(state: settings, actions: model.more)
        } else if !fromServerList {
            MessagesOverlayHost(model: model)
        }
    }
}
