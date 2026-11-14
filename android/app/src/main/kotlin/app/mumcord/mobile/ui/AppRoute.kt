// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mumcord.mobile.ui.connect.CertificateSheet
import app.mumcord.mobile.ui.connect.ConfirmSwitchSheet
import app.mumcord.mobile.ui.connect.LocalNetworkSheet
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connect.PasswordSheet
import app.mumcord.mobile.ui.connect.SessionEndedSheet
import app.mumcord.mobile.ui.connect.rememberLocalNetworkSystem
import app.mumcord.mobile.ui.link.InvalidLinkSheet
import app.mumcord.mobile.ui.link.LinkConfirmSheet
import app.mumcord.mobile.ui.servers.RemoveServerSheet
import app.mumcord.mobile.ui.servers.ServerFormScreen
import app.mumcord.mobile.ui.servers.ServerListScreen
import app.mumcord.mobile.ui.setup.TalkModeChoiceScreen
import app.mumcord.mobile.ui.connected.ConnectedSheetHost
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.MicrophoneNotice
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.NotificationSystem
import app.mumcord.mobile.ui.connected.rememberNotificationSystem
import app.mumcord.mobile.ui.connected.rememberMicrophoneSystem

/** Connects the screens to the view model; everything below it only gets values and callbacks. */
@Composable
fun AppRoute(
    viewModel: AppViewModel,
    localNetwork: LocalNetworkSystem = rememberLocalNetworkSystem(viewModel::answerLocalNetworkPrompt),
    microphone: MicrophoneSystem = rememberMicrophoneSystem(viewModel::answerMicrophonePrompt),
    notifications: NotificationSystem = rememberNotificationSystem(viewModel::answerNotificationPrompt),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPings()
        viewModel.recheckMicrophone()
        onPauseOrDispose { }
    }
    AppContent(uiState, viewModel, localNetwork, microphone, notifications)
}

/**
 * The screen that follows from the state, and over it at most one sheet: the certificate
 * question first (the connection waits for it), then the open question of [Overlay], then the
 * sheet of the connected screens (among them the microphone explanation).
 */
@Composable
fun AppContent(
    uiState: AppUiState,
    viewModel: AppViewModel,
    localNetwork: LocalNetworkSystem,
    microphone: MicrophoneSystem,
    notifications: NotificationSystem = NotificationSystem(ask = {}),
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    val session = uiState.session
    val prompt = session.certificatePrompt
    // Questions wait until the first-start setup is done.
    val overlay = uiState.overlay.takeIf { uiState.setupDone }
    val connectedSheet = uiState.connected?.sheet
    val sheetOpen = prompt != null || overlay != null || connectedSheet != null
    Box(Modifier.fillMaxSize()) {
        // While a sheet is open, the screen behind it is not offered to screen readers.
        Box(if (sheetOpen) Modifier.clearAndSetSemantics { } else Modifier) {
            when (uiState.screen) {
                AppScreen.TalkModeChoice -> TalkModeChoiceScreen(
                    selected = uiState.talkModeChoice,
                    saving = uiState.setupSaving,
                    error = uiState.setupError,
                    onSelect = viewModel::selectTalkMode,
                    onContinue = viewModel::confirmTalkMode,
                    topInsets = topInsets,
                    bottomInsets = bottomInsets,
                )
                AppScreen.ServerList -> {
                    BackHandler(enabled = uiState.editing, onBack = viewModel::toggleEditing)
                    ServerListScreen(
                        rows = uiState.serverRows,
                        loaded = session.serversLoaded,
                        editing = uiState.editing,
                        failure = uiState.failure,
                        onConnect = viewModel::connect,
                        onCancelConnect = viewModel::disconnect,
                        onAdd = viewModel::openAddServer,
                        onToggleEditing = viewModel::toggleEditing,
                        onEdit = viewModel::openEditServer,
                        onRemove = viewModel::requestRemove,
                        onRetry = viewModel::retry,
                        onOpenSettings = localNetwork.openSettings,
                        onDismissError = viewModel::dismissError,
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
                AppScreen.ServerForm -> uiState.form?.let { form ->
                    BackHandler(onBack = viewModel::closeForm)
                    ServerFormScreen(
                        form = form,
                        onChange = viewModel::changeForm,
                        onSave = viewModel::saveForm,
                        onBack = viewModel::closeForm,
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
                AppScreen.Channels -> uiState.connected?.let { connected ->
                    ConnectedShell(
                        state = connected,
                        actions = viewModel.connected,
                        onDisconnect = viewModel::disconnect,
                        onMicrophone = if (connected.microphone == MicrophoneNotice.Blocked) {
                            microphone.openSettings
                        } else {
                            microphone.ask
                        },
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
            }
        }
        when {
            prompt != null -> CertificateSheet(
                prompt = prompt,
                onTrust = { viewModel.answerCertificatePrompt(prompt.fingerprint, true) },
                onReject = { viewModel.answerCertificatePrompt(prompt.fingerprint, false) },
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.ConfirmSwitch -> ConfirmSwitchSheet(
                currentName = overlay.currentName,
                targetName = overlay.targetName,
                onConfirm = viewModel::confirmSwitch,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.ConfirmRemove -> RemoveServerSheet(
                serverName = overlay.server.name,
                onConfirm = viewModel::confirmRemove,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.EnterPassword -> PasswordSheet(
                serverName = overlay.serverName,
                password = overlay.password,
                onPasswordChange = viewModel::changePassword,
                onConnect = viewModel::submitPassword,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.Link -> LinkConfirmSheet(
                state = overlay.state,
                onUsernameChange = viewModel::changeLinkUsername,
                onConnect = viewModel::confirmLink,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.LocalNetwork -> LocalNetworkSheet(
                serverName = overlay.serverName,
                onContinue = localNetwork.ask,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.SessionEnded -> SessionEndedSheet(
                serverName = overlay.serverName,
                onReconnect = viewModel::reconnectAfterSessionEnded,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.InvalidLink -> InvalidLinkSheet(viewModel::dismissOverlay, topInsets, bottomInsets)
            connectedSheet != null -> ConnectedSheetHost(
                sheet = connectedSheet,
                actions = viewModel.connected,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
                onMicrophoneContinue = microphone.ask,
                onMicrophoneNotNow = viewModel::microphoneNotNow,
                onNotificationsContinue = notifications.ask,
                onNotificationsNotNow = viewModel::notificationsNotNow,
            )
        }
    }
}
