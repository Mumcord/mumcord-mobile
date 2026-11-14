// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// The Chat tab: connects the chat screen to the model. It reads the chat's own state, so an
/// arriving message redraws this view and nothing else.
struct ChatTab: View {
    let model: AppModel
    @State private var clock = ChatClock()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        Group {
            if let state = model.chatUiState(calendar: clock.calendar) {
                ChatScreen(state: state, actions: model.chat, loader: model.chatImages, clock: clock)
            } else {
                // The server has not said yet which channel the user is in.
                theme.colors.bgChat
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // The day may have changed while the app was away.
            if phase == .active { clock = ChatClock() }
        }
    }
}

/// The sheets of the chat and its full-screen picture, over the whole screen.
struct ChatOverlayHost: View {
    let model: AppModel
    @State private var calendar = Calendar.current

    var body: some View {
        if let state = model.chatUiState(calendar: calendar) {
            ChatOverlays(state: state, actions: model.chat, loader: model.chatImages)
        }
    }
}
