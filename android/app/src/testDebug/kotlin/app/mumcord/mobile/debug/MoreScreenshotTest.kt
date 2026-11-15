// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.ActivityEntry
import app.mumcord.mobile.bridge.ActivityKind
import app.mumcord.mobile.bridge.AppSettings
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeSettings
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.call.AudioOutput
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.platform.call.OutputState
import app.mumcord.mobile.session.FAKE_MOBILE_SETTINGS
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_BUILT_IN_THEMES
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.MOCKUP_MIRA
import app.mumcord.mobile.session.MOCKUP_MUSIC
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.ui.about.AboutLocal
import app.mumcord.mobile.ui.about.Licences
import app.mumcord.mobile.ui.about.noticeBlocks
import app.mumcord.mobile.ui.connected.ActivityFilter
import app.mumcord.mobile.ui.connected.ConnectedController
import app.mumcord.mobile.ui.connected.ConnectedLocal
import app.mumcord.mobile.ui.connected.ConnectedShell
import app.mumcord.mobile.ui.connected.ConnectedTab
import app.mumcord.mobile.ui.connected.MoreExtras
import app.mumcord.mobile.ui.connected.MoreLocal
import app.mumcord.mobile.ui.connected.MorePage
import app.mumcord.mobile.ui.connected.SettingsSheet
import app.mumcord.mobile.ui.connected.SettingsSheetHost
import app.mumcord.mobile.ui.connected.connectedUiState
import app.mumcord.mobile.ui.profile.OwnProfile
import app.mumcord.mobile.update.AvailableUpdate
import app.mumcord.mobile.update.ReleaseVersion
import java.time.ZoneOffset
import app.mumcord.mobile.bridge.CrashKind
import app.mumcord.mobile.crash.crashReport
import app.mumcord.mobile.ui.crash.CrashClock
import app.mumcord.mobile.ui.crash.CrashDelete
import app.mumcord.mobile.ui.crash.CrashList
import app.mumcord.mobile.ui.crash.CrashLocal
import app.mumcord.mobile.ui.crash.CrashSheetHost
import app.mumcord.mobile.ui.crash.CrashUpload
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
 * Draws the More tab (mockup `15-more`), the activity log (mockup `10-activity-log`) and the
 * settings (mockup `16-settings`) in both themes at the size of the mockups, with the mockups'
 * people. The empty log, the full length of the settings and their sheets have no mockup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class MoreScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // Nothing is tapped in a screenshot, so the engine is never asked for.
    private val repository = SessionRepository({ error("no engine in screenshots") }, scope, Dispatchers.Unconfined, log = {})
    private val actions = ConnectedController(repository, scope)
    private val chatActions = screenshotChatController(repository, scope)
    private val messages = ScreenshotMessages(repository, actions, scope)
    private val more = ScreenshotMore(repository, actions, scope)

    private val appSettings = AppSettings(
        language = null,
        linkPreviews = true,
        historyMaxAgeDays = null,
        notifyPrivateMessages = true,
        notifyMentions = true,
    )

    // A phone with an earpiece, so that the default output can be chosen.
    private val output = OutputState(
        available = listOf(
            AudioOutput("speaker", OutputKind.Speaker, ""),
            AudioOutput("earpiece", OutputKind.Earpiece, ""),
        ),
        current = AudioOutput("speaker", OutputKind.Speaker, ""),
    )

    private fun at(daysBefore: Long, hour: Int, minute: Int): Long =
        MOCKUP_DAY.minusDays(daysBefore).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private var nextId = 0

    private fun entry(kind: ActivityKind, session: UInt, name: String, channel: Pair<UInt, String>?, atMs: Long) =
        BridgeEvent.ActivityAdded(
            ActivityEntry(
                id = (++nextId).toULong(),
                kind = kind,
                session = session,
                name = name,
                isSelf = false,
                channelId = channel?.first,
                channelName = channel?.second,
                atMs = atMs,
            ),
        )

    private fun session(withActivity: Boolean = true): SessionState {
        nextId = 0
        // The entries of the mockup, oldest first; a kick is reported as leaving for now.
        val activity = if (withActivity) {
            listOf(
                entry(ActivityKind.STREAM_STARTED, MOCKUP_MIRA, "Mira", MOCKUP_MUSIC to "Music", at(1, 20, 10)),
                entry(ActivityKind.JOINED, MOCKUP_JONAS, "Jonas", MOCKUP_LOBBY to "Lobby", at(1, 21, 40)),
                entry(ActivityKind.LEFT, MOCKUP_JONAS, "Jonas", null, at(1, 21, 45)),
                entry(ActivityKind.LEFT, MOCKUP_BEN, "Ben", null, at(0, 17, 32)),
                entry(ActivityKind.JOINED, MOCKUP_ANNA, "Anna", MOCKUP_LOBBY to "Lobby", at(0, 18, 40)),
                entry(ActivityKind.MOVED, MOCKUP_JONAS, "Jonas", MOCKUP_GAMING to "Gaming", at(0, 18, 55)),
                entry(ActivityKind.STREAM_STARTED, MOCKUP_MIRA, "Mira", MOCKUP_GAMING to "Gaming", at(0, 19, 2)),
            )
        } else {
            emptyList()
        }
        return (
            listOf(
                BridgeEvent.ConnectionChanged(ConnectionState.Connected),
                BridgeEvent.ServerSynced(mockupVoiceServer()),
                BridgeEvent.MobileSettingsChanged(FAKE_MOBILE_SETTINGS.copy(talkModeChosen = true)),
                BridgeEvent.AppSettingsChanged(appSettings),
                BridgeEvent.ThemesChanged(
                    ThemeSettings(ThemeMode.SYSTEM, MOCKUP_BUILT_IN_THEMES[0].id, emptyList(), emptyMap()),
                ),
            ) + activity
            ).fold(SessionState(talkMode = TalkMode.VOICE_ACTIVITY, builtInThemes = MOCKUP_BUILT_IN_THEMES)) { state, event ->
            state.fold(event)
        }
    }

    // The user's own profile as the mockup shows it.
    private val profile = OwnProfile(name = "Lea", comment = "Playing late, always up for a chat", avatar = null)

    @After
    fun tearDown() = scope.cancel()

    private fun capture(
        name: String,
        dark: Boolean,
        local: MoreLocal,
        session: SessionState = session(),
        about: AboutLocal = AboutLocal(SCREENSHOT_APP_INFO),
        crashes: CrashLocal = CrashLocal(),
        languageChoice: Boolean = false,
    ) =
        composeRule.captureScreenshot("$name-${if (dark) "dark" else "light"}") {
            val state = checkNotNull(connectedUiState(session, ConnectedLocal(tab = ConnectedTab.More), "Nordlicht"))
            val moreState = more.state(session, local, output, extras = MoreExtras(profile = profile, about = about, crashes = crashes), languageChoice = languageChoice)
            MumcordTheme(darkTheme = dark) {
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
                    moreState.settings?.takeIf { it.sheet != null }?.let {
                        SettingsSheetHost(it, more.actions, top, bottom)
                    }
                    CrashSheetHost(crashes, more.actions.crashes, top, bottom, CrashClock(screenshotChatEnvironment().clock))
                }
            }
        }

    private val settings = MoreLocal(page = MorePage.Settings, noiseSuppression = true)

    // Three entries are new since the log was last looked at.
    private fun more(dark: Boolean) = capture("android-more", dark, MoreLocal(seenActivityId = 4u))

    private fun activity(dark: Boolean) = capture("android-activity", dark, MoreLocal(page = MorePage.Activity))

    private fun activityStreams(dark: Boolean) =
        capture("android-activity-streams", dark, MoreLocal(page = MorePage.Activity, filter = ActivityFilter.Streams))

    private fun activityEmpty(dark: Boolean) =
        capture("android-activity-empty", dark, MoreLocal(page = MorePage.Activity), session(withActivity = false))

    private fun settings(dark: Boolean) = capture("android-settings", dark, settings)

    private fun settingsFull(dark: Boolean) = capture("android-settings-full", dark, settings)

    // The language row and its choices exist from Android 13 on.
    private fun languageRow(dark: Boolean) = capture("android-settings-language", dark, settings, languageChoice = true)

    private fun languageSheet(dark: Boolean) =
        capture("android-settings-language-sheet", dark, settings.copy(sheet = SettingsSheet.Language), languageChoice = true)

    // The longest of the shipped languages on the whole settings screen (FR-5.82).
    private fun settingsGerman(dark: Boolean) = capture("android-lang-de-settings", dark, settings, languageChoice = true)

    @Config(qualifiers = "w390dp-h1500dp-xhdpi")
    @Test
    fun languageRowDark() = languageRow(dark = true)

    @Config(qualifiers = "w390dp-h1500dp-xhdpi")
    @Test
    fun languageRowLight() = languageRow(dark = false)

    @Test fun languageSheetDark() = languageSheet(dark = true)

    @Test fun languageSheetLight() = languageSheet(dark = false)

    @Config(qualifiers = "de-w390dp-h1500dp-xhdpi")
    @Test
    fun settingsGermanDark() = settingsGerman(dark = true)

    @Config(qualifiers = "de-w390dp-h1500dp-xhdpi")
    @Test
    fun settingsGermanLight() = settingsGerman(dark = false)

    private fun historySheet(dark: Boolean) =
        capture("android-settings-history-sheet", dark, settings.copy(sheet = SettingsSheet.HistoryAge))

    // A newer release is offered at the top (FR-5.101).
    private fun about(dark: Boolean) = capture(
        "android-about",
        dark,
        MoreLocal(page = MorePage.About),
        about = AboutLocal(SCREENSHOT_APP_INFO, update = AvailableUpdate(ReleaseVersion(0, 9, 0), hasApk = true)),
    )

    // The start of the notices as the release build bundles them.
    private fun licences(dark: Boolean) = capture(
        "android-licences",
        dark,
        MoreLocal(page = MorePage.Licences),
        about = AboutLocal(SCREENSHOT_APP_INFO, licences = Licences.Loaded(noticeBlocks(SAMPLE_NOTICES))),
    )

    // No mockup: the saved crash reports under About (FR-5.103), one of them already sent.
    private val crashList = CrashLocal(
        reports = CrashList.Loaded(
            listOf(
                crashReport("crash-1777900000.txt", createdMs = 1_777_900_000_000, cause = "attempt to subtract with overflow"),
                crashReport(
                    "crash-1777300000.txt",
                    createdMs = 1_777_300_000_000,
                    kind = CrashKind.Signal("SIGSEGV"),
                    cause = "Mumcord was stopped by SIGSEGV",
                    version = "0.7.1",
                    uploaded = true,
                ),
            ),
        ),
    )

    private fun crashReports(dark: Boolean) =
        capture("android-crash-reports", dark, MoreLocal(page = MorePage.CrashReports), crashes = crashList)

    // No mockup: the upload sheet with exactly what goes out and the user's comment.
    private fun crashUpload(dark: Boolean) {
        val report = (crashList.reports as CrashList.Loaded).reports.first()
        val comment = "I was listening in Raid when the app closed."
        val upload = CrashUpload(report, comment = comment, preview = SAMPLE_CRASH_REPORT + "\ncomment:\n$comment\n", previewFor = comment)
        capture("android-crash-upload", dark, MoreLocal(page = MorePage.CrashReports), crashes = crashList.copy(upload = upload))
    }

    // No mockup: the question before a report is deleted.
    private fun crashDelete(dark: Boolean) {
        val report = (crashList.reports as CrashList.Loaded).reports.first()
        capture("android-crash-delete", dark, MoreLocal(page = MorePage.CrashReports), crashes = crashList.copy(confirm = CrashDelete.One(report)))
    }

    @Test fun crashReportsDark() = crashReports(dark = true)

    @Test fun crashReportsLight() = crashReports(dark = false)

    @Test fun crashUploadDark() = crashUpload(dark = true)

    @Test fun crashUploadLight() = crashUpload(dark = false)

    @Test fun crashDeleteDark() = crashDelete(dark = true)

    @Test fun crashDeleteLight() = crashDelete(dark = false)

    @Test fun aboutDark() = about(dark = true)

    @Test fun aboutLight() = about(dark = false)

    @Test fun licencesDark() = licences(dark = true)

    @Test fun licencesLight() = licences(dark = false)

    @Test fun moreDark() = more(dark = true)

    @Test fun moreLight() = more(dark = false)

    @Test fun activityDark() = activity(dark = true)

    @Test fun activityLight() = activity(dark = false)

    @Test fun activityStreamsDark() = activityStreams(dark = true)

    @Test fun activityStreamsLight() = activityStreams(dark = false)

    @Test fun activityEmptyDark() = activityEmpty(dark = true)

    @Test fun activityEmptyLight() = activityEmpty(dark = false)

    @Test fun settingsDark() = settings(dark = true)

    @Test fun settingsLight() = settings(dark = false)

    // Tall enough for every section at once.
    @Config(qualifiers = "w390dp-h1500dp-xhdpi")
    @Test
    fun settingsFullDark() = settingsFull(dark = true)

    @Config(qualifiers = "w390dp-h1500dp-xhdpi")
    @Test
    fun settingsFullLight() = settingsFull(dark = false)

    @Test fun historySheetDark() = historySheet(dark = true)

    @Test fun historySheetLight() = historySheet(dark = false)
}

private val SAMPLE_NOTICES = """
Mumcord Mobile 0.8.0 for Android - Third-party notices
================================================================================

Mumcord Mobile itself, the Mumcord engine and the bridge are licensed under the
GNU AGPL v3 only (see LICENSE). The app includes the third-party material below.

--------------------------------------------------------------------------------
Instrument Sans (font, design/fonts/instrument-sans)
--------------------------------------------------------------------------------

License: SIL Open Font License, Version 1.1 (OFL-1.1)
Source: https://github.com/Instrument/instrument-sans
Copyright 2022 The Instrument Sans Project Authors

--------------------------------------------------------------------------------
Bricolage Grotesque (font, design/fonts/bricolage-grotesque)
--------------------------------------------------------------------------------

License: SIL Open Font License, Version 1.1 (OFL-1.1)
Source: https://github.com/ateliertriay/bricolage
Copyright 2022 The Bricolage Grotesque Project Authors
""".trimIndent()

/** A report as the engine writes it on a phone, shortened. */
private val SAMPLE_CRASH_REPORT = """
    Mumcord crash report
    version: 0.8.0
    time: 1777900000 (2026-05-04 13:06:40 UTC)
    kind: panic
    thread: mumcord-engine
    cause: attempt to subtract with overflow

    running:
    voice: connected, push-to-talk

    system:
    os: android 16
    cpu: aarch64
    memory: 7.6 GiB

    backtrace:
       0: mumcord_engine::audio::jitter::JitterBuffer::pop
       1: mumcord_engine::audio::mixer::Mixer::mix
""".trimIndent()
