// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.session.FAKE_THEME
import app.mumcord.mobile.session.FAKE_THEME_CODE
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.InMemoryMessageNotificationSettings
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.ThemeCodeRead
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.themes.ThemesSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Edit profile, the themes screen and the theme the app is drawn in, through the view model (FR-5.80, FR-5.81). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileThemesUiTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.connected(): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            messageSettings = InMemoryMessageNotificationSettings(),
        )
        backgroundScope.launch { viewModel.uiState.collect() }
        backgroundScope.launch { viewModel.moreState.collect() }
        runCurrent()
        viewModel.connect(1)
        runCurrent()
        viewModel.connected.selectTab(ConnectedTab.More)
        return viewModel
    }

    private val AppViewModel.ui get() = moreState.value!!

    @Test
    fun theMoreTabShowsTheOwnProfileAndTheAppTheme() = runTest(dispatcher) {
        val viewModel = connected()
        engine.profile = engine.profile.copy(commentText = "Playing late\nAsk me")
        engine.setOwnComment("Playing late\nAsk me")
        runCurrent()

        val profile = viewModel.ui.profile!!
        assertEquals("Lea", profile.name)
        assertEquals("Playing late", profile.commentLine)
        assertEquals(FAKE_THEME.name, viewModel.ui.themeName)
    }

    @Test
    fun aChangedCommentAndANewPictureAreSavedAndTheEditorCloses() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.more.openProfile()
        runCurrent()
        val edit = viewModel.ui.profileEdit!!
        assertEquals("The server's limit", 5000u, edit.edit.limit)
        assertFalse("Nothing to save yet", edit.edit.canSave)

        viewModel.more.profile.changeComment("Up for a chat")
        viewModel.more.profile.pickAvatar(byteArrayOf(7, 8))
        viewModel.more.profile.save()
        runCurrent()

        assertEquals("Up for a chat", engine.profile.commentText)
        assertArrayEquals(byteArrayOf(7, 8), engine.ownAvatar)
        assertEquals(MorePage.Main, viewModel.ui.page)
        assertEquals("Up for a chat", viewModel.ui.profile!!.comment)
    }

    @Test
    fun anUnchangedCommentIsNotSentAgainWhenOnlyThePictureIsRemoved() = runTest(dispatcher) {
        val viewModel = connected()
        engine.setOwnAvatar(byteArrayOf(1))
        engine.profile = engine.profile.copy(commentText = "Kept")
        engine.setOwnComment("Kept")
        runCurrent()
        viewModel.more.openProfile()
        runCurrent()

        viewModel.more.profile.removeAvatar()
        runCurrent()
        assertNull(viewModel.ui.profileEdit!!.avatar)
        engine.profile = engine.profile.copy(commentText = "Changed elsewhere")
        viewModel.more.profile.save()
        runCurrent()

        assertNull(engine.ownAvatar)
        assertEquals("The comment was not sent", "Changed elsewhere", engine.profile.commentText)
    }

    @Test
    fun aCommentOverTheLimitCannotBeSaved() = runTest(dispatcher) {
        val viewModel = connected()
        engine.limits = engine.limits.copy(text = 5u)
        viewModel.more.openProfile()
        runCurrent()
        viewModel.more.profile.changeComment("too long")
        viewModel.more.profile.save()
        runCurrent()

        assertTrue(viewModel.ui.profileEdit!!.edit.tooLong)
        assertEquals("", engine.profile.commentText)
    }

    @Test
    fun theAppearanceAndTheAppThemeAreSavedAndDrawn() = runTest(dispatcher) {
        val viewModel = connected()
        backgroundScope.launch { viewModel.appTheme.collect() }
        viewModel.more.openThemes()
        runCurrent()
        assertEquals(ThemeMode.SYSTEM, viewModel.ui.themes!!.mode)

        viewModel.more.themes.setMode(ThemeMode.LIGHT)
        runCurrent()
        assertEquals(ThemeMode.LIGHT, viewModel.ui.themes!!.mode)
        assertEquals(ThemeMode.LIGHT, viewModel.appTheme.value!!.mode)
        assertEquals(FAKE_THEME.id, viewModel.appTheme.value!!.light.themeId)
    }

    @Test
    fun aPastedCodeIsPreviewedThenImportedAndApplied() = runTest(dispatcher) {
        val viewModel = connected()
        backgroundScope.launch { viewModel.appTheme.collect() }
        viewModel.more.openThemes()
        val themes = viewModel.more.themes
        themes.openPaste()
        themes.changePasteText("hello")
        runCurrent()
        assertEquals(ThemeCodeRead.Invalid, (viewModel.ui.themes!!.sheet as ThemesSheet.Paste).read)

        themes.changePasteText("🎨 Mumcord theme \"Imported\" – $FAKE_THEME_CODE")
        runCurrent()
        val read = (viewModel.ui.themes!!.sheet as ThemesSheet.Paste).read
        assertTrue(read is ThemeCodeRead.Found)

        themes.applyPasted()
        runCurrent()
        val state = viewModel.ui.themes!!
        assertNull("The sheet closes", state.sheet)
        assertEquals(listOf("custom:1"), state.custom.map { it.id })
        assertEquals("custom:1", state.appThemeId)
        assertEquals("custom:1", viewModel.appTheme.value!!.dark.themeId)
    }

    @Test
    fun aThemeIsSharedAndAnOwnOneDeleted() = runTest(dispatcher) {
        val viewModel = connected()
        engine.importThemeCode(FAKE_THEME_CODE)
        runCurrent()
        viewModel.more.openThemes()
        val themes = viewModel.more.themes

        themes.share(FAKE_THEME.id)
        runCurrent()
        assertTrue(themes.local.value.shareText!!.endsWith(FAKE_THEME_CODE))
        themes.shared()
        assertNull(themes.local.value.shareText)

        themes.requestDelete("custom:1")
        themes.confirmDelete("custom:1")
        runCurrent()
        assertTrue(viewModel.ui.themes!!.custom.isEmpty())
    }

    @Test
    fun aServersOwnThemeIsShownOnlyWhileThatServerIsShown() = runTest(dispatcher) {
        val viewModel = connected()
        backgroundScope.launch { viewModel.appTheme.collect() }
        engine.importThemeCode(FAKE_THEME_CODE)
        engine.setServerTheme(1, "custom:1")
        runCurrent()
        assertEquals("custom:1", viewModel.appTheme.value!!.dark.themeId)
        viewModel.more.openThemes()
        runCurrent()
        assertEquals("custom:1", viewModel.ui.themes!!.serverTheme!!.id)

        viewModel.disconnect()
        runCurrent()
        assertEquals("The app theme again", FAKE_THEME.id, viewModel.appTheme.value!!.dark.themeId)
    }

    @Test
    fun theEditServerFormSavesTheServersTheme() = runTest(dispatcher) {
        val viewModel = connected()
        viewModel.disconnect()
        runCurrent()
        viewModel.openEditServer(1)
        runCurrent()
        assertNull(viewModel.uiState.value.form!!.themeId)

        viewModel.changeForm { it.copy(themeId = FAKE_THEME.id) }
        viewModel.saveForm()
        runCurrent()

        assertEquals(mapOf(1L to FAKE_THEME.id), engine.themeSettings.serverThemes)
        assertNull("The form closed", viewModel.uiState.value.form)
    }

}
