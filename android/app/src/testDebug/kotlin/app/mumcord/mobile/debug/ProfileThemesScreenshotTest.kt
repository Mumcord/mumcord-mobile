// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeCodePreview
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeSettings
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.session.FAKE_MOBILE_SETTINGS
import app.mumcord.mobile.session.MOCKUP_BUILT_IN_THEMES
import app.mumcord.mobile.session.OCEAN_DARK_TOKENS
import app.mumcord.mobile.session.OCEAN_LIGHT_TOKENS
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.ThemeCodeRead
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.MoreExtras
import app.mumcord.mobile.ui.connected.MoreLocal
import app.mumcord.mobile.ui.connected.MorePage
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.connected.moreUiState
import app.mumcord.mobile.ui.profile.AvatarSourceSheet
import app.mumcord.mobile.ui.profile.OwnProfile
import app.mumcord.mobile.ui.profile.ProfileEdit
import app.mumcord.mobile.ui.profile.ProfileSheet
import app.mumcord.mobile.ui.servers.ServerFormScreen
import app.mumcord.mobile.ui.servers.ServerFormState
import app.mumcord.mobile.ui.servers.ServerThemeSheet
import app.mumcord.mobile.ui.themes.AppTheme
import app.mumcord.mobile.ui.themes.AppThemeHost
import app.mumcord.mobile.ui.themes.ThemesLocal
import app.mumcord.mobile.ui.themes.ThemesSheet
import app.mumcord.mobile.ui.themes.ThemesSheetHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws edit profile (mockup `20-edit-profile`) and the themes screen (mockup `19-themes`) in
 * both themes, with the mockups' people, and three screens in the built-in theme "Deep Ocean"
 * with the colours the desktop app derives for it. The avatar sheet, the paste sheet, the
 * server's theme in the edit-server form and the screens in Deep Ocean have no mockup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ProfileThemesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val chatActions = screenshotChatController(repository, scope)
    private val messages = ScreenshotMessages(repository, actions, scope)
    private val more = ScreenshotMore(repository, actions, scope)

    private val comment = "Playing late, always up for a chat. Ask me about the new map!"
    private val profile = OwnProfile(name = "Lea", comment = comment, avatar = null)

    /** A theme the user pasted, to show "Your themes". */
    private val imported = Theme(
        id = "custom:1",
        name = "From the desktop",
        builtIn = false,
        dark = MOCKUP_BUILT_IN_THEMES[2].dark.copy(background = "#120d0c", accent = "#f3521d"),
        light = MOCKUP_BUILT_IN_THEMES[2].light.copy(accent = "#b4501f"),
    )

    private fun session(dark: Boolean): SessionState = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupVoiceServer()),
        BridgeEvent.MobileSettingsChanged(FAKE_MOBILE_SETTINGS.copy(talkModeChosen = true)),
        BridgeEvent.AppSettingsChanged(
            AppSettings(language = null, linkPreviews = true, historyMaxAgeDays = null, notifyPrivateMessages = true, notifyMentions = true),
        ),
        BridgeEvent.ThemesChanged(
            ThemeSettings(
                mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                appThemeId = MOCKUP_BUILT_IN_THEMES[0].id,
                customThemes = listOf(imported),
                serverThemes = emptyMap(),
            ),
        ),
    ).fold(SessionState(talkMode = TalkMode.VOICE_ACTIVITY, builtInThemes = MOCKUP_BUILT_IN_THEMES)) { state, event -> state.fold(event) }

    @After
    fun tearDown() = scope.cancel()

    private fun capture(
        name: String,
        dark: Boolean,
        local: MoreLocal,
        extras: MoreExtras = MoreExtras(profile = profile),
        tab: ConnectedTab = ConnectedTab.More,
        ocean: Boolean = false,
        overlay: @Composable () -> Unit = {},
    ) = composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
        val session = session(dark)
        val state = checkNotNull(connectedUiState(session, ConnectedLocal(tab = tab), "Nordlicht"))
        val moreState = checkNotNull(moreUiState(session, local, "Nordlicht", OutputState(), senderOnly = false, extras))
        Themed(dark, ocean) {
            Box(Modifier.fillMaxSize()) {
                ConnectedShell(
                    state,
                    actions,
                    onDisconnect = {},
                    onMicrophone = {},
                    chat = null,
                    chatBadge = 0,
                    chatActions = chatActions,
                    chatEnvironment = screenshotChatEnvironment(),
                    messages = messages.state(session),
                    messagesActions = messages.actions,
                    conversation = null,
                    conversationActions = messages.conversationActions,
                    more = moreState,
                    moreActions = more.actions,
                    onNotificationSettings = {},
                    onIdentities = {},
                    topInsets = top,
                    bottomInsets = bottom,
                )
                moreState.themes?.takeIf { it.sheet != null }?.let { ThemesSheetHost(it, more.actions.themes, top, bottom) }
                overlay()
            }
        }
    }

    /** The app's own colours, or Deep Ocean in the version the screenshot is for. */
    @Composable
    private fun Themed(dark: Boolean, ocean: Boolean, content: @Composable () -> Unit) {
        if (ocean) {
            val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            AppThemeHost(AppTheme(mode, OCEAN_DARK_TOKENS, OCEAN_LIGHT_TOKENS), systemDark = dark, content = content)
        } else {
            MumcordTheme(darkTheme = dark, content = content)
        }
    }

    private val editing = ProfileEdit(comment = comment, initialComment = comment, limit = 140u)

    private fun editProfile(dark: Boolean, ocean: Boolean = false, name: String = "android-profile-edit") = capture(
        name,
        dark,
        MoreLocal(page = MorePage.EditProfile),
        MoreExtras(profile = profile, profileEdit = editing),
        ocean = ocean,
    )

    private fun avatarSheet(dark: Boolean) = capture(
        "android-profile-avatar-sheet",
        dark,
        MoreLocal(page = MorePage.EditProfile),
        MoreExtras(profile = profile, profileEdit = editing.copy(sheet = ProfileSheet.Avatar)),
    ) {
        AvatarSourceSheet(hasAvatar = true, onLibrary = {}, onCamera = {}, onRemove = {}, onDismiss = {}, topInsets = top, bottomInsets = bottom)
    }

    private fun themes(dark: Boolean) = capture("android-themes", dark, MoreLocal(page = MorePage.Themes))

    private fun paste(dark: Boolean) = capture(
        "android-themes-paste",
        dark,
        MoreLocal(page = MorePage.Themes),
        MoreExtras(
            profile = profile,
            themes = ThemesLocal(
                sheet = ThemesSheet.Paste(
                    text = "🎨 Mumcord theme \"Deep Ocean\" – mct1.eyJ2IjoiMC45LjAiLCJuIjoiRGVlcCBPY2VhbiJ9",
                    read = ThemeCodeRead.Found(
                        ThemeCodePreview(
                            name = "Deep Ocean",
                            dark = MOCKUP_BUILT_IN_THEMES[1].dark,
                            light = MOCKUP_BUILT_IN_THEMES[1].light,
                            createdWith = "0.9.0",
                            madeWithNewer = false,
                            importedId = null,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun serverTheme(dark: Boolean) = composeRule.captureScreenshot("android-server-theme-${if (dark) "dark" else "light"}") {
        val form = ServerFormState(
            serverId = 1,
            name = "Nordlicht",
            address = "nordlicht.example",
            username = "Lea",
            themeId = MOCKUP_BUILT_IN_THEMES[1].id,
            themeSheet = true,
        )
        val all = MOCKUP_BUILT_IN_THEMES + imported
        MumcordTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize()) {
                ServerFormScreen(form = form, onChange = {}, onSave = {}, onBack = {}, themes = all, topInsets = top, bottomInsets = bottom)
                ServerThemeSheet(form = form, themes = all, onChange = {}, topInsets = top, bottomInsets = bottom)
            }
        }
    }

    private fun oceanChannels(dark: Boolean) =
        capture("android-themes-ocean-channels", dark, MoreLocal(), tab = ConnectedTab.Channels, ocean = true)

    private fun oceanMore(dark: Boolean) = capture("android-themes-ocean-more", dark, MoreLocal(), ocean = true)

    private fun oceanProfile(dark: Boolean) = editProfile(dark, ocean = true, name = "android-themes-ocean-profile")

    @Test fun editProfileDark() = editProfile(dark = true)

    @Test fun editProfileLight() = editProfile(dark = false)

    @Test fun avatarSheetDark() = avatarSheet(dark = true)

    @Test fun avatarSheetLight() = avatarSheet(dark = false)

    @Test fun themesDark() = themes(dark = true)

    @Test fun themesLight() = themes(dark = false)

    @Test fun pasteDark() = paste(dark = true)

    @Test fun pasteLight() = paste(dark = false)

    @Test fun serverThemeDark() = serverTheme(dark = true)

    @Test fun serverThemeLight() = serverTheme(dark = false)

    @Test fun oceanChannelsDark() = oceanChannels(dark = true)

    @Test fun oceanChannelsLight() = oceanChannels(dark = false)

    @Test fun oceanMoreDark() = oceanMore(dark = true)

    @Test fun oceanMoreLight() = oceanMore(dark = false)

    @Test fun oceanProfileDark() = oceanProfile(dark = true)

    @Test fun oceanProfileLight() = oceanProfile(dark = false)
}
