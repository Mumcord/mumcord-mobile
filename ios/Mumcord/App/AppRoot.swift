// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Connects the screens to the model; everything below it only gets values and closures.
struct AppRoot: View {
    let model: AppModel
    /// The phone's appearance; a debug launch can force it (`-mumcordTheme`).
    var theme: MumcordThemeMode = .system
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        AppContent(uiState: model.uiState, model: model)
            // One column or two follows the window, again on every rotation and resize; what
            // the user did lives in the model and stays.
            .environment(\.twoColumns, model.twoColumns)
            .onConnectedLayoutChange(model.setTwoColumns)
            .environment(\.cameraGateway, model.camera)
            .environment(\.streamVideo, model.streams.video)
            // The colours switch at run time to the shown theme, on every screen (FR-5.81).
            .modifier(AppThemeHost(theme: model.appTheme.shown, phone: theme))
            .onOpenURL { model.openLink($0.absoluteString) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    model.refreshPings()
                    model.recheckMicrophone()
                    model.recheckNotifications()
                    model.identities.recheckCamera()
                    model.call?.onAppActive()
                } else {
                    // Hold to talk and hold to whisper work only while the app is on screen
                    // (FR-5.21).
                    model.releaseHolds()
                }
            }
            // A chat behind the lock screen or another app is not being read.
            .onChange(of: scenePhase, initial: true) { _, phase in
                model.chat.setResumed(phase == .active)
                model.conversation.setResumed(phase == .active)
                // Nothing is fetched or decoded for a stream nobody can see (FR-5.43).
                model.streams.setOnScreen(phase != .background)
                // The shared camera pauses while the app is in the background (FR-5.75).
                model.cameraShare?.camera.setOnScreen(phase != .background)
            }
            // No pings of public servers while the app is not on screen (FR-5.43).
            .onChange(of: scenePhase) { _, phase in
                guard model.uiState.browsingPublic else { return }
                if phase == .background {
                    model.publicServers.pausePings()
                } else {
                    model.publicServers.resumePings()
                }
            }
    }
}

/// The screen that follows from the state, and over it at most one sheet: the certificate
/// question first (the connection waits for it), otherwise the open question of `Overlay`.
struct AppContent: View {
    let uiState: AppUiState
    let model: AppModel
    @State private var pickingIdentityFile = false

    var body: some View {
        let prompt = uiState.session.certificatePrompt
        // Questions wait until the first-start setup is done.
        let overlay = uiState.setupDone ? uiState.overlay : nil
        let connectedSheet = uiState.connected?.sheet
        let messagesLocal = model.messages.local
        let messagesSheet = messagesLocal.blockSession != nil || messagesLocal.requestSession != nil
        let identitySheet = uiState.screen == .identities || uiState.screen == .identitySetup ? uiState.identities.sheet : nil
        let cameraQuestion = uiState.screen == .scan && uiState.identities.scan?.camera == .explain
        let settingsSheet = model.more.local.sheet != nil
        let moreSheet = (uiState.connected?.tab == .more || uiState.screen == .more) && model.more.sheetOpen
        let serverThemeSheet = uiState.screen == .serverForm ? uiState.form.flatMap { $0.themeSheet ? $0 : nil } : nil
        let allThemes = uiState.session.builtInThemes + (uiState.session.themes?.customThemes ?? [])
        let streams = model.streams.uiState
        // The engine waits for this answer before a stream can use a direct connection.
        let directQuestion = uiState.screen == .channels && uiState.connected != nil && streams.directConnectionsQuestion
        let connectedScreen = uiState.screen == .channels && uiState.connected != nil
        let camera = connectedScreen ? model.cameraShare : nil
        let cameraSheet = camera?.uiState.sheet
        let cameraPermission = camera?.question
        let sheetOpen = prompt != nil || overlay != nil || connectedSheet != nil || messagesSheet
            || settingsSheet || moreSheet || identitySheet != nil || cameraQuestion || directQuestion
            || cameraSheet != nil || cameraPermission != nil || serverThemeSheet != nil
        // The camera runs only while the user sees it: its preview is the sheet shown below (the
        // sheets before it in the chain take its place), or the "Camera on" bar is on screen.
        let previewShown = cameraSheet != nil && prompt == nil && overlay == nil && identitySheet == nil
            && !cameraQuestion && !directQuestion && cameraPermission == nil
        let cameraShown = previewShown || camera?.uiState.sharing != nil
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
            } else if let serverThemeSheet {
                ServerThemeSheet(form: serverThemeSheet, themes: allThemes, onChange: model.changeForm)
            } else if let identitySheet {
                IdentitySheetHost(sheet: identitySheet, actions: model.identities)
            } else if cameraQuestion {
                CameraSheet(
                    reason: Strings.cameraReasonScan,
                    blocked: false,
                    onContinue: model.identities.allowCamera,
                    onOpenSettings: model.openSettings,
                    onDismiss: model.identities.cameraNotNow
                )
            } else if directQuestion {
                DirectConnectionsSheet(onAnswer: model.streams.answerDirectConnections)
            } else if let camera, let cameraPermission {
                CameraSheet(
                    reason: Strings.cameraReasonShare,
                    blocked: cameraPermission == .denied,
                    onContinue: camera.allowCamera,
                    onOpenSettings: {
                        camera.dismissQuestion()
                        model.openSettings()
                    },
                    onDismiss: camera.dismissQuestion
                )
            } else if let camera, let cameraSheet {
                CameraShareSheet(sheet: cameraSheet, controller: camera)
            } else if let connectedSheet {
                ConnectedSheetHost(sheet: connectedSheet, actions: ConnectedActions(model: model), model: model)
            } else if uiState.connected != nil || uiState.screen == .more {
                MoreOverlayHost(model: model)
            }
        }
        .modifier(IdentityFiles(
            picking: $pickingIdentityFile,
            exported: uiState.identities.exported,
            identities: model.identities
        ))
        .onChange(of: cameraShown, initial: true) { _, shown in
            model.cameraShare?.camera.setVisible(shown)
        }
    }

    @ViewBuilder private var screen: some View {
        switch uiState.screen {
        case .starting:
            MumcordScreen { Color.clear }
        case .identitySetup:
            IdentitySetupScreen(
                unreadableFile: uiState.identities.unreadableFile,
                onScan: model.identities.openScan,
                onImport: { pickingIdentityFile = true },
                onCreate: model.identities.openCreate
            )
        case .identities:
            IdentitiesScreen(
                state: uiState.identities,
                onBack: model.identities.close,
                onDetails: model.identities.openDetails,
                onScan: model.identities.openScan,
                onImport: { pickingIdentityFile = true },
                onCreate: model.identities.openCreate
            )
        case .scan:
            if let scan = uiState.identities.scan {
                ScanScreen(
                    state: scan,
                    qrSource: model.qrSource,
                    onQrText: model.identities.onQrText,
                    onCameraFailed: model.identities.cameraFailed,
                    onClose: model.identities.closeScan,
                    onAllowCamera: model.identities.allowCamera,
                    onOpenSettings: model.openSettings,
                    onCode: model.identities.typeCode,
                    onImport: model.identities.importTransfer
                )
            }
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
                onOpenSettings: model.openSettings,
                onMore: model.openMore,
                onScan: model.identities.openScan,
                onBrowsePublic: model.openPublicServers
            )
        case .publicServers:
            PublicServersHost(model: model)
        case .more:
            // The connected shell draws the backdrop behind the More tab; here it is drawn alone.
            MumcordScreen {
                MoreTab(model: model, onDisconnect: model.disconnect, onBack: model.closeMore)
            }
        case .serverForm:
            if let form = uiState.form {
                ServerFormScreen(
                    form: form,
                    onChange: model.changeForm,
                    onSave: model.saveForm,
                    onBack: model.closeForm,
                    themes: uiState.session.builtInThemes + (uiState.session.themes?.customThemes ?? [])
                )
            }
        case .channels:
            if let connected = uiState.connected {
                ConnectedShell(
                    state: connected,
                    actions: ConnectedActions(model: model),
                    streams: model.streams.uiState,
                    streamActions: StreamActions(controller: model.streams),
                    onDisconnect: model.disconnect,
                    chatBadge: model.chatBadge,
                    twoColumns: model.twoColumns,
                    model: model
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
        case .sessionEnded(_, let serverName, let alone):
            SessionEndedSheet(
                serverName: serverName,
                alone: alone,
                onReconnect: model.reconnectAfterSessionEnded,
                onDismiss: model.dismissOverlay
            )
        }
    }
}

/// Reads the browser's state in a view of its own, so arriving pings redraw only the browser.
private struct PublicServersHost: View {
    let model: AppModel

    var body: some View {
        let controller = model.publicServers
        PublicServersScreen(
            state: controller.uiState,
            actions: PublicServersActions(
                back: model.closePublicServers,
                refresh: controller.refresh,
                search: controller.search,
                selectCountry: controller.selectCountry,
                showing: controller.showing,
                add: controller.add,
                connect: model.connectFromPublicServers
            )
        )
    }
}
