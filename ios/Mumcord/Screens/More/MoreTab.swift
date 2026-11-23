// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The More tab: connects its screens to the model. It reads the tab's own state, so a new
/// activity entry redraws this view and not the channel list.
struct MoreTab: View {
    let model: AppModel
    let onDisconnect: () -> Void
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
                case .main:
                    MoreBlockedHost(model: model) {
                        MoreScreen(
                            state: state,
                            actions: model.more,
                            onBlocked: model.messages.openBlocked,
                            onIdentities: model.identities.open,
                            onDisconnect: onDisconnect
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

/// The sheet of a setting when one is open, else the sheets of messages and chat, over the
/// whole screen. There is never more than one.
struct MoreOverlayHost: View {
    let model: AppModel

    var body: some View {
        if let settings = model.moreUiState?.settings, settings.sheet != nil {
            SettingsSheetHost(state: settings, actions: model.more)
        } else {
            MessagesOverlayHost(model: model)
        }
    }
}
