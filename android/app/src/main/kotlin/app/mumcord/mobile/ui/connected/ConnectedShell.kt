// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.components.MumcordTab
import app.mumcord.mobile.design.components.MumcordTabBar
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.about.AboutScreen
import app.mumcord.mobile.ui.about.LicencesScreen
import app.mumcord.mobile.ui.crash.CrashClock
import app.mumcord.mobile.ui.crash.CrashReportsScreen
import app.mumcord.mobile.ui.chat.ChatClock
import app.mumcord.mobile.ui.chat.ChatController
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.chat.ChatScreen
import app.mumcord.mobile.ui.chat.ChatUiState
import app.mumcord.mobile.ui.messages.BlockedPeopleScreen
import app.mumcord.mobile.ui.messages.MessagesController
import app.mumcord.mobile.ui.messages.MessagesScreen
import app.mumcord.mobile.ui.messages.MessagesUiState
import app.mumcord.mobile.ui.profile.EditProfileScreen
import app.mumcord.mobile.ui.themes.ThemesScreen

const val DENIED_BANNER_TEST_TAG = "denied-banner"

/**
 * The frame of the app while connected: the selected tab's screen, the banners, the whisper
 * bar, the voice bar and the bottom tab bar; or the full voice view, which covers all of it.
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
) {
    // What is open inside the Messages or More tab closes first, then the tab.
    val insideTab = messages.canGoBack && when (state.tab) {
        ConnectedTab.Messages -> !messages.blockedOpen
        ConnectedTab.More -> messages.blockedOpen
        else -> false
    }
    val morePage = more.page.takeIf { state.tab == ConnectedTab.More } ?: MorePage.Main
    BackHandler(enabled = state.searching || state.tab != ConnectedTab.Channels) {
        when {
            morePage != MorePage.Main -> moreActions.back()
            insideTab -> messagesActions.back()
            state.tab != ConnectedTab.Channels -> actions.selectTab(ConnectedTab.Channels)
            else -> actions.closeSearch()
        }
    }
    // Hold to talk and hold to whisper work only while the app is on screen (FR-5.21).
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { actions.releaseHolds() }
    val voice = state.voice
    if (voice != null && voice.open) {
        VoiceScreen(voice, state, actions, onDisconnect, onMicrophone, topInsets, bottomInsets)
        return
    }
    MumcordScreen {
        Box(Modifier.weight(1f)) {
            when (state.tab) {
                ConnectedTab.Channels -> ChannelsScreen(state, actions, topInsets)
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
        ConnectedBanners(state, actions::dismissDenied, onMicrophone)
        if (voice != null) {
            voice.whisper?.let { WhisperBar(it, actions::setWhisperHeld, actions::closeWhisper) }
            VoiceBar(voice, actions, onDisconnect, onMicrophone)
        }
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
                MumcordTab(
                    MumcordIcons.Messages,
                    stringResource(R.string.tab_messages),
                    badge = messages.badge.takeIf { it > 0 }?.let(::badgeText),
                    contentDescription = messages.badge.takeIf { it > 0 }
                        ?.let { pluralStringResource(R.plurals.messages_tab_unread, it, it) },
                ),
                MumcordTab(MumcordIcons.More, stringResource(R.string.tab_more)),
            ),
            selectedIndex = state.tab.ordinal,
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
