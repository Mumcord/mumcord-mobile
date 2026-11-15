// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.ui.camera.CameraSheet
import app.mumcord.mobile.ui.camera.record
import app.mumcord.mobile.ui.camera.rememberCameraSystem
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.chat.ChatOverlays
import app.mumcord.mobile.ui.chat.ChatUiState
import app.mumcord.mobile.ui.chat.rememberChatClock
import app.mumcord.mobile.ui.chat.rememberChatImageSystem
import app.mumcord.mobile.ui.connect.CertificateSheet
import app.mumcord.mobile.ui.connect.ConfirmSwitchSheet
import app.mumcord.mobile.ui.connect.LocalNetworkSheet
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connect.PasswordSheet
import app.mumcord.mobile.ui.connect.SessionEndedSheet
import app.mumcord.mobile.ui.connect.rememberLocalNetworkSystem
import app.mumcord.mobile.ui.connected.ChatEnvironment
import app.mumcord.mobile.ui.connected.ConnectedSheetHost
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.MicrophoneNotice
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.MoreUiState
import app.mumcord.mobile.ui.connected.NotificationSystem
import app.mumcord.mobile.ui.connected.SettingsSheetHost
import app.mumcord.mobile.ui.connected.rememberMicrophoneSystem
import app.mumcord.mobile.ui.connected.rememberNotificationSystem
import app.mumcord.mobile.ui.identities.CameraNotice
import app.mumcord.mobile.ui.identities.IdentitiesScreen
import app.mumcord.mobile.ui.identities.IdentitySetupScreen
import app.mumcord.mobile.ui.identities.IdentitySheetHost
import app.mumcord.mobile.ui.identities.IdentitySystem
import app.mumcord.mobile.ui.identities.ScanScreen
import app.mumcord.mobile.ui.identities.rememberIdentitySystem
import app.mumcord.mobile.ui.link.InvalidLinkSheet
import app.mumcord.mobile.ui.link.LinkConfirmSheet
import app.mumcord.mobile.ui.messages.MessagesSheetHost
import app.mumcord.mobile.ui.messages.MessagesUiState
import app.mumcord.mobile.ui.profile.AvatarSourceSheet
import app.mumcord.mobile.ui.profile.rememberAvatarImageSystem
import app.mumcord.mobile.ui.servers.PublicServersScreen
import app.mumcord.mobile.ui.servers.RemoveServerSheet
import app.mumcord.mobile.ui.servers.ServerFormScreen
import app.mumcord.mobile.ui.servers.ServerListScreen
import app.mumcord.mobile.ui.servers.ServerThemeSheet
import app.mumcord.mobile.ui.setup.TalkModeChoiceScreen
import app.mumcord.mobile.ui.themes.ThemesSheetHost
import app.mumcord.mobile.ui.themes.shareThemeText
import app.mumcord.mobile.update.AvailableUpdate

/** What a photo from the camera is for; the camera question is the same for both. */
enum class PhotoFor { Chat, Avatar }

/** Connects the screens to the view model; everything below it only gets values and callbacks. */
@Composable
fun AppRoute(
    viewModel: AppViewModel,
    localNetwork: LocalNetworkSystem = rememberLocalNetworkSystem(viewModel::answerLocalNetworkPrompt),
    microphone: MicrophoneSystem = rememberMicrophoneSystem(viewModel::answerMicrophonePrompt),
    notifications: NotificationSystem = rememberNotificationSystem(viewModel::answerNotificationPrompt),
    chatImages: ChatImageSystem? = null,
    chatImageLoader: ChatImageLoader = (LocalContext.current.applicationContext as MumcordApplication).chatImageLoader,
    identitySystem: IdentitySystem? = null,
) {
    val cameraGateway = (LocalContext.current.applicationContext as MumcordApplication).cameraGateway
    // The camera question of "take a photo" is open, for the chat or for the profile picture;
    // the scanner has its own state.
    var photoQuestion by rememberSaveable { mutableStateOf<PhotoFor?>(null) }
    val takePhoto = remember { mutableStateOf<(PhotoFor) -> Unit>({}) }
    val camera = rememberCameraSystem { answer ->
        val photoFor = photoQuestion
        if (photoFor != null) {
            photoQuestion = null
            cameraGateway.record(answer)
            if (answer.granted) takePhoto.value(photoFor)
        } else {
            viewModel.identities.answerCamera(answer)
        }
    }
    val images = chatImages ?: rememberChatImageSystem(
        onImage = { viewModel.activeChat.sendImage(it.bytes, it.mime) },
        onUnreadable = { viewModel.activeChat.imageUnreadable() },
        cameraGranted = cameraGateway::granted,
        onCameraNeeded = { photoQuestion = PhotoFor.Chat },
    )
    val avatarImages = rememberAvatarImageSystem(
        profile = viewModel.more.profile,
        cameraGranted = cameraGateway::granted,
        onCameraNeeded = { photoQuestion = PhotoFor.Avatar },
    )
    takePhoto.value = { photoFor ->
        when (photoFor) {
            PhotoFor.Chat -> images.takePhoto()
            PhotoFor.Avatar -> avatarImages.takePhoto()
        }
    }
    val identities = identitySystem ?: rememberIdentitySystem(viewModel.identities, camera)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chat by viewModel.chatState.collectAsStateWithLifecycle()
    val chatBadge by viewModel.chatBadge.collectAsStateWithLifecycle()
    val messages by viewModel.messagesState.collectAsStateWithLifecycle()
    val conversation by viewModel.conversationState.collectAsStateWithLifecycle()
    val more by viewModel.moreState.collectAsStateWithLifecycle()
    val updateNotice by viewModel.updateNotice.collectAsStateWithLifecycle()
    val chatEnvironment = ChatEnvironment(chatImageLoader, images, rememberChatClock())
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPings()
        viewModel.recheckMicrophone()
        viewModel.identities.recheckCamera()
        viewModel.checkForUpdate()
        onPauseOrDispose { }
    }
    // A finished backup goes to the share sheet once.
    // A theme to share goes to the share sheet once.
    val themesLocal by viewModel.more.themes.local.collectAsStateWithLifecycle()
    val themeShare = themesLocal.shareText
    val context = LocalContext.current
    LaunchedEffect(themeShare) {
        if (themeShare != null) {
            shareThemeText(context, themeShare)
            viewModel.more.themes.shared()
        }
    }
    val exported = uiState.identities.exported
    LaunchedEffect(exported) {
        if (exported != null) {
            identities.share(exported)
            viewModel.identities.exportShared()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AppContent(
            uiState,
            viewModel,
            localNetwork,
            microphone,
            chat,
            chatBadge,
            chatEnvironment,
            identities,
            notifications,
            messages = messages,
            conversation = conversation,
            more = more,
            updateNotice = updateNotice,
            avatarImages = avatarImages,
        )
        photoQuestion?.let { photoFor ->
            CameraSheet(
                reason = stringResource(
                    if (photoFor == PhotoFor.Avatar) R.string.camera_reason_avatar else R.string.camera_reason_photo,
                ),
                blocked = cameraGateway.promptBlocked,
                onContinue = camera.ask,
                onOpenSettings = {
                    photoQuestion = null
                    camera.openSettings()
                },
                onDismiss = { photoQuestion = null },
                topInsets = WindowInsets.statusBars,
                bottomInsets = WindowInsets.navigationBars,
            )
        }
    }
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
    chat: ChatUiState?,
    chatBadge: Int,
    chatEnvironment: ChatEnvironment,
    identitySystem: IdentitySystem,
    notifications: NotificationSystem = NotificationSystem(ask = {}),
    messages: MessagesUiState? = null,
    conversation: ChatUiState? = null,
    more: MoreUiState? = null,
    updateNotice: AvailableUpdate? = null,
    avatarImages: ChatImageSystem = ChatImageSystem(pickPhoto = {}, takePhoto = {}),
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    val session = uiState.session
    val prompt = session.certificatePrompt
    // Questions wait until the first-start setup is done.
    val overlay = uiState.overlay.takeIf { uiState.setupDone }
    val identityScreens = uiState.identities
    // The camera explanation of the scanner, and the sheets of the identity list.
    val scanExplain = identityScreens.scan?.camera == CameraNotice.Explain
    val identitySheet = identityScreens.sheet.takeIf { identityScreens.scan == null }
    // The sheets of the connected screens wait behind an identity screen that covers them.
    val covered = uiState.screen == AppScreen.Identities || uiState.screen == AppScreen.Scan
    val connectedSheet = uiState.connected?.sheet.takeIf { !covered }
    val messagesSheet = messages?.sheet.takeIf { uiState.connected != null && !covered }
    val settingsSheet = more?.settings?.takeIf { it.sheet != null && uiState.connected?.tab == ConnectedTab.More && !covered }
    val serverThemeSheet = uiState.form?.takeIf { it.themeSheet && uiState.screen == AppScreen.ServerForm }
    val onMoreTab = uiState.connected?.tab == ConnectedTab.More && !covered
    val profileSheet = more?.profileEdit?.takeIf { it.edit.sheet != null && onMoreTab }
    val themesSheet = more?.themes?.takeIf { it.sheet != null && onMoreTab }
    // A chat's sheets and its full-screen picture belong to the tab that shows the chat.
    val shownChat = when (uiState.connected?.tab) {
        ConnectedTab.Chat -> chat
        ConnectedTab.Messages -> conversation.takeIf { messages?.open != null }
        else -> null
    }.takeIf { !covered }
    val chatOverlay = shownChat?.takeIf { it.sheet != null || it.viewer != null || it.urlToOpen != null }
    val chatOverlayActions = if (uiState.connected?.tab == ConnectedTab.Messages) viewModel.conversation else viewModel.chat
    val sheetOpen = prompt != null || overlay != null || scanExplain || identitySheet != null ||
        connectedSheet != null || messagesSheet != null || settingsSheet != null ||
        profileSheet != null || themesSheet != null || serverThemeSheet != null ||
        chatOverlay?.sheet != null || chatOverlay?.viewer != null
    Box(Modifier.fillMaxSize()) {
        // While a sheet is open, the screen behind it is not offered to screen readers.
        Box(if (sheetOpen) Modifier.clearAndSetSemantics { } else Modifier) {
            when (uiState.screen) {
                AppScreen.Starting -> MumcordScreen { }
                AppScreen.IdentitySetup -> IdentitySetupScreen(
                    unreadableFile = identityScreens.unreadableFile,
                    onScan = viewModel.identities::openScan,
                    onImport = identitySystem.pickFile,
                    onCreate = viewModel.identities::openCreate,
                    topInsets = topInsets,
                    bottomInsets = bottomInsets,
                )
                AppScreen.Identities -> {
                    BackHandler(onBack = viewModel.identities::close)
                    IdentitiesScreen(
                        state = identityScreens,
                        onBack = viewModel.identities::close,
                        onDetails = viewModel.identities::openDetails,
                        onScan = viewModel.identities::openScan,
                        onImport = identitySystem.pickFile,
                        onCreate = viewModel.identities::openCreate,
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
                AppScreen.Scan -> identityScreens.scan?.let { scan ->
                    BackHandler(onBack = viewModel.identities::closeScan)
                    ScanScreen(
                        state = scan,
                        viewfinder = identitySystem.viewfinder,
                        onClose = viewModel.identities::closeScan,
                        onAllowCamera = identitySystem.askCamera,
                        onOpenSettings = identitySystem.openCameraSettings,
                        onCode = viewModel.identities::typeCode,
                        onImport = viewModel.identities::importTransfer,
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
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
                        onScan = viewModel.identities::openScan,
                        onBrowsePublic = viewModel::openPublicServers,
                        onToggleEditing = viewModel::toggleEditing,
                        onEdit = viewModel::openEditServer,
                        onRemove = viewModel::requestRemove,
                        onRetry = viewModel::retry,
                        onOpenSettings = localNetwork.openSettings,
                        onDismissError = viewModel::dismissError,
                        update = updateNotice,
                        onDismissUpdate = viewModel.more.about::dismissUpdate,
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
                        themes = session.builtInThemes + session.themes?.customThemes.orEmpty(),
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
                AppScreen.PublicServers -> {
                    BackHandler(onBack = viewModel::closePublicServers)
                    // No pings while the app is not on screen (FR-5.43).
                    LifecycleStartEffect(viewModel) {
                        viewModel.publicServers.resumePings()
                        onStopOrDispose { viewModel.publicServers.pausePings() }
                    }
                    val publicServers by viewModel.publicServersState.collectAsStateWithLifecycle()
                    PublicServersScreen(
                        state = publicServers,
                        actions = viewModel.publicServerActions,
                        topInsets = topInsets,
                        bottomInsets = bottomInsets,
                    )
                }
                AppScreen.Channels -> uiState.connected?.let { connected ->
                    // All follow the same session, so they are there together.
                    if (messages == null || more == null) return@let
                    ConnectedShell(
                        state = connected,
                        actions = viewModel.connected,
                        onDisconnect = viewModel::disconnect,
                        onMicrophone = if (connected.microphone == MicrophoneNotice.Blocked) {
                            microphone.openSettings
                        } else {
                            microphone.ask
                        },
                        chat = chat,
                        chatBadge = chatBadge,
                        chatActions = viewModel.chat,
                        chatEnvironment = chatEnvironment,
                        messages = messages,
                        messagesActions = viewModel.messages,
                        conversation = conversation,
                        conversationActions = viewModel.conversation,
                        more = more,
                        moreActions = viewModel.more,
                        onNotificationSettings = notifications.openSettings,
                        onIdentities = viewModel.identities::open,
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
            scanExplain -> CameraSheet(
                reason = stringResource(R.string.camera_reason_scan),
                blocked = false,
                onContinue = identitySystem.askCamera,
                onOpenSettings = identitySystem.openCameraSettings,
                onDismiss = viewModel.identities::cameraNotNow,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            identitySheet != null -> IdentitySheetHost(identitySheet, viewModel.identities, topInsets, bottomInsets)
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
                alone = overlay.alone,
                onReconnect = viewModel::reconnectAfterSessionEnded,
                onDismiss = viewModel::dismissOverlay,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            overlay is Overlay.InvalidLink -> InvalidLinkSheet(viewModel::dismissOverlay, topInsets, bottomInsets)
            connectedSheet != null && messages != null -> ConnectedSheetHost(
                messages = messages,
                messagesActions = viewModel.messages,
                sheet = connectedSheet,
                actions = viewModel.connected,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
                onMicrophoneContinue = microphone.ask,
                onMicrophoneNotNow = viewModel::microphoneNotNow,
                onNotificationsContinue = notifications.ask,
                onNotificationsNotNow = viewModel::notificationsNotNow,
            )
            messagesSheet != null -> MessagesSheetHost(messagesSheet, viewModel.messages, topInsets, bottomInsets)
            settingsSheet != null -> SettingsSheetHost(settingsSheet, viewModel.more, topInsets, bottomInsets)
            profileSheet != null -> AvatarSourceSheet(
                hasAvatar = profileSheet.hasAvatar,
                onLibrary = avatarImages.pickPhoto,
                onCamera = avatarImages.takePhoto,
                onRemove = viewModel.more.profile::removeAvatar,
                onDismiss = viewModel.more.profile::closeSheet,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            themesSheet != null -> ThemesSheetHost(themesSheet, viewModel.more.themes, topInsets, bottomInsets)
            serverThemeSheet != null -> ServerThemeSheet(
                form = serverThemeSheet,
                themes = session.builtInThemes + session.themes?.customThemes.orEmpty(),
                onChange = viewModel::changeForm,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
            chatOverlay != null -> ChatOverlays(
                state = chatOverlay,
                actions = chatOverlayActions,
                loader = chatEnvironment.loader,
                images = chatEnvironment.images,
                topInsets = topInsets,
                bottomInsets = bottomInsets,
            )
        }
    }
}
