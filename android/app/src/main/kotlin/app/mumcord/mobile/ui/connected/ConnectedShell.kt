// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordNavigationRail
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordTab
import app.mumcord.mobile.design.components.MumcordTabBar
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.about.AboutScreen
import app.mumcord.mobile.ui.about.LicencesScreen
import app.mumcord.mobile.ui.camerashare.CameraBar
import app.mumcord.mobile.ui.camerashare.CameraShareSheet
import app.mumcord.mobile.ui.camerashare.CameraShareUi
import app.mumcord.mobile.ui.chat.ChatClock
import app.mumcord.mobile.ui.chat.ChatController
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.chat.ChatScreen
import app.mumcord.mobile.ui.chat.ChatUiState
import app.mumcord.mobile.ui.crash.CrashClock
import app.mumcord.mobile.ui.crash.CrashReportsScreen
import app.mumcord.mobile.ui.messages.BlockedPeopleScreen
import app.mumcord.mobile.ui.messages.MessagesController
import app.mumcord.mobile.ui.messages.MessagesScreen
import app.mumcord.mobile.ui.messages.MessagesUiState
import app.mumcord.mobile.ui.profile.EditProfileScreen
import app.mumcord.mobile.ui.streams.DirectConnectionsSheet
import app.mumcord.mobile.ui.streams.MINI_WINDOW_MARGIN
import app.mumcord.mobile.ui.streams.StreamColumn
import app.mumcord.mobile.ui.streams.StreamFullScreen
import app.mumcord.mobile.ui.streams.StreamMiniWindow
import app.mumcord.mobile.ui.streams.StreamOrientation
import app.mumcord.mobile.ui.streams.StreamView
import app.mumcord.mobile.ui.streams.StreamsController
import app.mumcord.mobile.ui.streams.StreamsUiState
import app.mumcord.mobile.ui.themes.ThemesScreen

const val DENIED_BANNER_TEST_TAG = "denied-banner"
const val NAVIGATION_RAIL_TEST_TAG = "navigation-rail"
const val SIDE_COLUMN_TEST_TAG = "side-column"
const val MAIN_COLUMN_TEST_TAG = "main-column"

/** How far the mini window stays above the bottom of the tab's content: clear of the chat's input bar. */
private val MINI_WINDOW_BOTTOM = 72.dp

/** The left column of two columns (`.pane--side`): the channel tree and the voice bar. */
private val SIDE_COLUMN_WIDTH = 340.dp

/**
 * The frame of the app while connected. In one column: the selected tab's screen, the banners,
 * the whisper bar, the voice bar and the bottom tab bar. In two columns (tablets, see
 * [connectedLayout]): the navigation rail, the channel tree with the bars on the left and the
 * chat, an opened stream or the Messages and More screens on the right. The full voice view
 * covers all of it. The layout is decided from the space the app has, again on every rotation
 * and resize; what the user did lives in the controllers and stays.
 */
@Composable
fun ConnectedShell(
    state: ConnectedUiState,
    actions: ConnectedController,
    onDisconnect: () -> Unit,
    onMicrophone: () -> Unit,
    chat: ChatUiState?,
    chatBadge: Int,
    chatActions: ChatController,
    chatEnvironment: ChatEnvironment,
    messages: MessagesUiState,
    messagesActions: MessagesController,
    conversation: ChatUiState?,
    conversationActions: ChatController,
    more: MoreUiState,
    moreActions: MoreController,
    onNotificationSettings: () -> Unit,
    onIdentities: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    streams: StreamsUiState = StreamsUiState(),
    streamActions: StreamsController? = null,
    camera: CameraShareUi? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoColumns = connectedLayout(maxWidth, maxHeight) == ConnectedLayout.TwoColumns
        // The view model needs it too: in two columns the channel chat is on screen beside the channels.
        LaunchedEffect(actions, twoColumns) { actions.setTwoColumns(twoColumns) }
        ConnectedContent(
            state, actions, onDisconnect, onMicrophone, chat, chatBadge, chatActions, chatEnvironment, messages,
            messagesActions, conversation, conversationActions, more, moreActions, onNotificationSettings, onIdentities,
            topInsets, bottomInsets, streams, streamActions, camera, twoColumns,
        )
        val cameraSheet = camera?.state?.sheet
        if (camera != null && cameraSheet != null) CameraShareSheet(cameraSheet, camera, topInsets, bottomInsets)
        // The engine waits for this answer before a stream can use a direct connection.
        if (streamActions != null && streams.directConnectionsQuestion) {
            DirectConnectionsSheet(streamActions, topInsets, bottomInsets)
        }
    }
}

@Composable
private fun ConnectedContent(
    state: ConnectedUiState,
    actions: ConnectedController,
    onDisconnect: () -> Unit,
    onMicrophone: () -> Unit,
    chat: ChatUiState?,
    chatBadge: Int,
    chatActions: ChatController,
    chatEnvironment: ChatEnvironment,
    messages: MessagesUiState,
    messagesActions: MessagesController,
    conversation: ChatUiState?,
    conversationActions: ChatController,
    more: MoreUiState,
    moreActions: MoreController,
    onNotificationSettings: () -> Unit,
    onIdentities: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    streams: StreamsUiState,
    streamActions: StreamsController?,
    camera: CameraShareUi?,
    twoColumns: Boolean,
) {
    val opened = streams.opened.takeIf { streamActions != null }
    val streamView = streams.open?.view
    if (opened != null && streamActions != null && (streamView == StreamView.Whole || streamView == StreamView.Full && !twoColumns)) {
        // A tablet turns by itself; only the phone's full screen has the landscape button.
        if (!twoColumns) StreamOrientation(streams.landscape)
        StreamFullScreen(
            stream = opened,
            landscape = streams.landscape,
            mute = state.voice?.mute,
            onToggleMute = actions::toggleMute,
            onMicrophone = onMicrophone,
            actions = streamActions,
            topInsets = topInsets,
            bottomInsets = bottomInsets,
            onLeave = if (twoColumns) streamActions::expand else null,
        )
        return
    }
    val tab = if (twoColumns) state.tab.inTwoColumns() else state.tab
    // What is open inside the Messages or More tab closes first, then the tab.
    val insideTab = messages.canGoBack && when (tab) {
        ConnectedTab.Messages -> !messages.blockedOpen
        ConnectedTab.More -> messages.blockedOpen
        else -> false
    }
    val morePage = more.page.takeIf { tab == ConnectedTab.More } ?: MorePage.Main
    BackHandler(enabled = state.searching || tab != ConnectedTab.Channels) {
        when {
            morePage != MorePage.Main -> moreActions.back()
            insideTab -> messagesActions.back()
            tab != ConnectedTab.Channels -> actions.selectTab(ConnectedTab.Channels)
            else -> actions.closeSearch()
        }
    }
    // Hold to talk and hold to whisper work only while the app is on screen (FR-5.21).
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { actions.releaseHolds() }
    val voice = state.voice
    if (voice != null && voice.open) {
        VoiceScreen(voice, state, actions, onDisconnect, onMicrophone, topInsets, bottomInsets, camera)
        return
    }
    // One screen of a tab; in two columns the right column shows Chat for Channels.
    val tabScreen: @Composable (ConnectedTab) -> Unit = { shown ->
        when (shown) {
            ConnectedTab.Channels -> ChannelsScreen(state, actions, topInsets, streams, streamActions)
            // Null for the moment the user's own channel is not known yet.
            ConnectedTab.Chat -> if (chat != null) {
                ChatScreen(chat, chatActions, chatEnvironment.loader, chatEnvironment.clock, topInsets)
            }
            ConnectedTab.Messages -> when {
                messages.open == null ->
                    MessagesScreen(messages, messagesActions, chatEnvironment.clock, onNotificationSettings, topInsets)
                // Null for the moment the conversation is being opened.
                conversation != null -> ChatScreen(
                    conversation,
                    conversationActions,
                    chatEnvironment.loader,
                    chatEnvironment.clock,
                    topInsets,
                    onBack = messagesActions::back,
                )
            }
            ConnectedTab.More -> when {
                morePage == MorePage.Activity -> ActivityScreen(more, moreActions, chatEnvironment.clock, topInsets)
                // Null for the moment the voice settings are being read.
                morePage == MorePage.Settings -> more.settings?.let {
                    SettingsScreen(it, moreActions, actions.inputLevel, topInsets)
                }
                // Null for the moment the profile or the themes are being read.
                morePage == MorePage.EditProfile -> more.profileEdit?.let {
                    EditProfileScreen(it, moreActions.profile, topInsets)
                }
                morePage == MorePage.Themes -> more.themes?.let {
                    ThemesScreen(it, moreActions.themes, onBack = moreActions::back, topInsets = topInsets)
                }
                morePage == MorePage.About -> more.about?.let {
                    AboutScreen(
                        it,
                        moreActions::back,
                        moreActions::openLicences,
                        moreActions::openCrashReports,
                        moreActions.about::dismissUpdate,
                        topInsets,
                    )
                }
                morePage == MorePage.CrashReports -> more.crashReports?.let {
                    CrashReportsScreen(it, moreActions.crashes, moreActions::back, topInsets, CrashClock(chatEnvironment.clock))
                }
                morePage == MorePage.Licences -> more.licences?.let {
                    LicencesScreen(it, moreActions::back, moreActions.about::loadLicences, topInsets)
                }
                messages.blockedOpen -> BlockedPeopleScreen(messages, messagesActions, topInsets)
                else -> MoreScreen(more, moreActions, messagesActions::openBlocked, onIdentities, onDisconnect, topInsets)
            }
        }
    }
    val bars: @Composable () -> Unit = {
        ConnectedBanners(state, actions::dismissDenied, onMicrophone)
        if (voice != null) {
            voice.whisper?.let { WhisperBar(it, actions::setWhisperHeld, actions::closeWhisper) }
            camera?.let { CameraBar(it.state, it.actions) }
            VoiceBar(voice, actions, onDisconnect, onMicrophone)
        }
    }
    val messagesTab = MumcordTab(
        MumcordIcons.Messages,
        stringResource(R.string.tab_messages),
        badge = messages.badge.takeIf { it > 0 }?.let(::badgeText),
        contentDescription = messages.badge.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.messages_tab_unread, it, it) },
    )
    if (twoColumns) {
        // The opened stream fills the right column of Channels; elsewhere it floats as the mini window.
        val streamInColumn = opened != null && streamActions != null && streamView == StreamView.Full && tab == ConnectedTab.Channels
        MumcordScreen {
            Row(Modifier.weight(1f)) {
                MumcordNavigationRail(
                    tabs = listOf(
                        MumcordTab(MumcordIcons.List, stringResource(R.string.tab_channels)),
                        messagesTab,
                        MumcordTab(MumcordIcons.More, stringResource(R.string.tab_more)),
                    ),
                    selectedIndex = RAIL_TABS.indexOf(tab),
                    onSelect = { actions.selectTab(RAIL_TABS[it]) },
                    windowInsets = topInsets,
                    modifier = Modifier.testTag(NAVIGATION_RAIL_TEST_TAG),
                )
                val border = MumcordTheme.colors.border
                Column(
                    Modifier
                        .width(SIDE_COLUMN_WIDTH)
                        .fillMaxHeight()
                        .drawBehind {
                            val x = size.width - 0.5.dp.toPx()
                            drawLine(border, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }
                        .windowInsetsPadding(bottomInsets)
                        .testTag(SIDE_COLUMN_TEST_TAG),
                ) {
                    Box(Modifier.weight(1f)) {
                        ChannelsScreen(
                            state,
                            actions,
                            topInsets,
                            streams,
                            streamActions,
                            hiddenStream = opened?.key?.takeIf { streamInColumn },
                        )
                    }
                    bars()
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MumcordTheme.colors.bgChat)
                        .windowInsetsPadding(bottomInsets)
                        .testTag(MAIN_COLUMN_TEST_TAG),
                ) {
                    if (streamInColumn && opened != null && streamActions != null) {
                        StreamColumn(opened, streamActions, chat, chatActions, topInsets)
                    } else {
                        tabScreen(if (tab == ConnectedTab.Channels) ConnectedTab.Chat else tab)
                    }
                    if (opened != null && streamActions != null && !streamInColumn) {
                        StreamMiniWindow(
                            stream = opened,
                            actions = streamActions,
                            // Back in the right column of Channels, where the stream has its place.
                            onExpand = {
                                actions.selectTab(ConnectedTab.Channels)
                                streamActions.expand()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = MINI_WINDOW_MARGIN, bottom = MINI_WINDOW_BOTTOM),
                        )
                    }
                }
            }
        }
        return
    }
    MumcordScreen {
        Box(Modifier.weight(1f)) {
            tabScreen(tab)
            if (opened != null && streamActions != null) {
                StreamMiniWindow(
                    stream = opened,
                    actions = streamActions,
                    // Above the chat's input bar, which stays usable.
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = MINI_WINDOW_MARGIN, bottom = MINI_WINDOW_BOTTOM),
                )
            }
        }
        bars()
        MumcordTabBar(
            tabs = listOf(
                MumcordTab(MumcordIcons.List, stringResource(R.string.tab_channels)),
                MumcordTab(
                    MumcordIcons.Hash,
                    stringResource(R.string.tab_chat),
                    badge = chatBadge.takeIf { it > 0 }?.let(::badgeText),
                    contentDescription = chatBadge.takeIf { it > 0 }
                        ?.let { pluralStringResource(R.plurals.chat_tab_unread, it, it) },
                ),
                messagesTab,
                MumcordTab(MumcordIcons.More, stringResource(R.string.tab_more)),
            ),
            selectedIndex = tab.ordinal,
            onSelect = { actions.selectTab(ConnectedTab.entries[it]) },
            windowInsets = bottomInsets,
        )
    }
}

/** What the Chat tab needs from the phone besides its state. */
class ChatEnvironment(val loader: ChatImageLoader, val images: ChatImageSystem, val clock: ChatClock)

private const val MAX_BADGE = 99

/** An unread count as a badge shows it: the number, or "99+" beyond that. */
fun badgeText(count: Int): String = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString()

/**
 * A lost connection, the microphone that may not be used and the latest refusal by the server,
 * visible on every tab.
 */
@Composable
internal fun ConnectedBanners(state: ConnectedUiState, onDismissDenied: () -> Unit, onMicrophone: () -> Unit) {
    val reconnecting = state.reconnecting
    val denied = state.denied
    val microphone = state.microphone
    if (reconnecting == null && denied == null && microphone == MicrophoneNotice.None) return
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = Modifier.padding(bottom = MumcordSpace.s2),
    ) {
        if (reconnecting != null) {
            MumcordBanner(
                text = stringResource(R.string.channels_reconnecting, reconnecting.attempt, reconnecting.maxAttempts),
                tone = MumcordBannerTone.Warning,
                icon = MumcordIcons.Alert,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (microphone != MicrophoneNotice.None) {
            MicrophoneBanner(blocked = microphone == MicrophoneNotice.Blocked, onClick = onMicrophone)
        }
        if (denied != null) {
            MumcordBanner(
                title = denied.channelName
                    ?.let { stringResource(R.string.denied_title_channel, it) }
                    ?: stringResource(R.string.denied_title),
                // The server's own words are shown as plain characters.
                text = denied.detail.ifBlank { stringResource(R.string.denied_text) },
                tone = MumcordBannerTone.Danger,
                icon = MumcordIcons.Alert,
                modifier = Modifier
                    .testTag(DENIED_BANNER_TEST_TAG)
                    .clickable(onClickLabel = stringResource(R.string.denied_dismiss), onClick = onDismissDenied)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
