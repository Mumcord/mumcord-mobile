// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Connects the screens to the model; everything below it only gets values and closures.
struct AppRoot: View {
    let model: AppModel
    var theme: MumcordThemeMode = .system
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        AppContent(uiState: model.uiState, model: model)
            .mumcordTheme(theme)
            .onOpenURL { model.openLink($0.absoluteString) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    model.refreshPings()
                    model.recheckMicrophone()
                } else {
                    // Hold to talk and hold to whisper work only while the app is on screen
                    // (FR-5.21).
                    model.releaseHolds()
                }
            }
            // A chat behind the lock screen or another app is not being read.
            .onChange(of: scenePhase, initial: true) { _, phase in
                model.chat.setResumed(phase == .active)
            }
    }
}

/// The screen that follows from the state, and over it at most one sheet: the certificate
/// question first (the connection waits for it), otherwise the open question of `Overlay`.
struct AppContent: View {
    let uiState: AppUiState
    let model: AppModel

    var body: some View {
        let prompt = uiState.session.certificatePrompt
        // Questions wait until the first-start setup is done.
        let overlay = uiState.setupDone ? uiState.overlay : nil
        let connectedSheet = uiState.connected?.sheet
        let sheetOpen = prompt != nil || overlay != nil || connectedSheet != nil
        ZStack {
            screen
                // While a sheet is open, the screen behind it is not offered to screen readers.
                .accessibilityHidden(sheetOpen)
            if let prompt {
                CertificateSheet(
                    prompt: prompt,
                    onTrust: { model.answerCertificatePrompt(fingerprint: prompt.fingerprint, trust: true) },
                    onReject: { model.answerCertificatePrompt(fingerprint: prompt.fingerprint, trust: false) }
                )
            } else if let overlay {
                sheet(for: overlay)
            } else if let connectedSheet {
                ConnectedSheetHost(sheet: connectedSheet, actions: ConnectedActions(model: model))
            } else if uiState.connected != nil {
                ChatOverlayHost(model: model)
            }
        }
    }

    @ViewBuilder private var screen: some View {
        switch uiState.screen {
        case .talkModeChoice:
            TalkModeChoiceScreen(
                selected: uiState.talkModeChoice,
                saving: uiState.setupSaving,
                error: uiState.setupError,
                onSelect: model.selectTalkMode,
                onContinue: model.confirmTalkMode
            )
        case .serverList:
            ServerListScreen(
                rows: uiState.serverRows,
                loaded: uiState.session.serversLoaded,
                editing: uiState.editing,
                failure: uiState.connectFailure,
                onConnect: model.connect,
                onCancelConnect: model.disconnect,
                onAdd: model.openAddServer,
                onToggleEditing: model.toggleEditing,
                onEdit: model.openEditServer,
                onRemove: model.requestRemove,
                onRetry: model.retry,
                onDismissError: model.dismissError,
                onOpenSettings: model.openSettings
            )
        case .serverForm:
            if let form = uiState.form {
                ServerFormScreen(
                    form: form,
                    onChange: model.changeForm,
                    onSave: model.saveForm,
                    onBack: model.closeForm
                )
            }
        case .channels:
            if let connected = uiState.connected {
                ConnectedShell(
                    state: connected,
                    actions: ConnectedActions(model: model),
                    onDisconnect: model.disconnect,
                    chatBadge: model.chatBadge
                ) {
                    ChatTab(model: model)
                }
            }
        }
    }

    @ViewBuilder
    private func sheet(for overlay: Overlay) -> some View {
        switch overlay {
        case .confirmSwitch(_, let currentName, let targetName):
            ConfirmSwitchSheet(
                currentName: currentName,
                targetName: targetName,
                onConfirm: model.confirmSwitch,
                onDismiss: model.dismissOverlay
            )
        case .confirmRemove(let server):
            RemoveServerSheet(
                serverName: server.name,
                onConfirm: model.confirmRemove,
                onDismiss: model.dismissOverlay
            )
        case .enterPassword(_, let serverName, let password):
            PasswordSheet(
                serverName: serverName,
                password: password,
                onPasswordChange: model.changePassword,
                onConnect: model.submitPassword,
                onDismiss: model.dismissOverlay
            )
        case .localNetwork(_, let serverName):
            LocalNetworkSheet(
                serverName: serverName,
                onContinue: model.continueLocalNetwork,
                onDismiss: model.dismissOverlay
            )
        case .link(let state):
            LinkConfirmSheet(
                state: state,
                onUsernameChange: model.changeLinkUsername,
                onConnect: model.confirmLink,
                onDismiss: model.dismissOverlay
            )
        case .invalidLink:
            InvalidLinkSheet(onDismiss: model.dismissOverlay)
        }
    }
}
