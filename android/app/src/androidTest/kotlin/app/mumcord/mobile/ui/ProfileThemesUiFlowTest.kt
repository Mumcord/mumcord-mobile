// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mumcord.mobile.bridge.ImageData
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.session.FAKE_THEME
import app.mumcord.mobile.session.FAKE_THEME_CODE
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.InMemoryMessageNotificationSettings
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_EDIT_PROFILE_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_THEMES_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.profile.AVATAR_REMOVE_TEST_TAG
import app.mumcord.mobile.ui.profile.PROFILE_COMMENT_TEST_TAG
import app.mumcord.mobile.ui.profile.PROFILE_PHOTO_TEST_TAG
import app.mumcord.mobile.ui.profile.PROFILE_SAVE_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.themes.AppThemeHost
import app.mumcord.mobile.ui.themes.THEMES_APPLY_TEST_TAG
import app.mumcord.mobile.ui.themes.THEMES_CODE_FIELD_TEST_TAG
import app.mumcord.mobile.ui.themes.THEMES_PASTE_TEST_TAG
import app.mumcord.mobile.ui.themes.THEMES_PREVIEW_TEST_TAG
import app.mumcord.mobile.ui.themes.themeModeTestTag
import app.mumcord.mobile.ui.themes.themeTileTestTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Edit profile and the themes screen (FR-5.80, FR-5.81) on a device against a fake engine. */
class ProfileThemesUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resizeForTheKeyboard() = compose.resizeForTheKeyboardLikeTheApp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @After
    fun tearDown() = scope.cancel()

    /** Starts the app in the theme the engine says, connects to Nordlicht and opens More. */
    private fun connect(prepare: (FakeEngine) -> Unit = {}) {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        runBlocking { repository.loadServers() }
        prepare(engine)
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            messageSettings = InMemoryMessageNotificationSettings(),
        )
        compose.setContent {
            val theme by viewModel.appTheme.collectAsStateWithLifecycle()
            AppThemeHost(theme) {
                AppRoute(
                    viewModel,
                    LocalNetworkSystem(ask = {}, openSettings = {}),
                    MicrophoneSystem(ask = {}, openSettings = {}),
                )
            }
        }
        compose.connectToNordlicht()
        await { shown(VOICE_BAR_TEST_TAG) }
        compose.onNodeWithText("More").performClick()
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    @Test
    fun theCommentIsEditedAndThePictureRemoved() {
        connect { engine ->
            engine.profile = engine.profile.copy(
                commentText = "Playing late",
                avatar = ImageData(byteArrayOf(1, 2), "image/png", 1u, 1u, animated = false),
            )
            engine.ownAvatar = byteArrayOf(1, 2)
        }
        await { shown(MORE_EDIT_PROFILE_TEST_TAG) }
        compose.onNodeWithText("Playing late").assertIsDisplayed()
        compose.onNodeWithTag(MORE_EDIT_PROFILE_TEST_TAG).performClick()
        await { shown(PROFILE_SAVE_TEST_TAG) }
        compose.onNodeWithText("Your name is set per server").assertIsDisplayed()
        compose.onNodeWithTag(PROFILE_SAVE_TEST_TAG).assertIsNotEnabled()

        compose.onNode(field(PROFILE_COMMENT_TEST_TAG)).performTextReplacement("Up for a chat")
        compose.onNodeWithTag(PROFILE_PHOTO_TEST_TAG).press()
        await { shown(AVATAR_REMOVE_TEST_TAG) }
        compose.onNodeWithTag(AVATAR_REMOVE_TEST_TAG).performClick()
        compose.onNodeWithTag(PROFILE_SAVE_TEST_TAG).assertIsEnabled().performClick()

        await { shown(MORE_EDIT_PROFILE_TEST_TAG) }
        assertEquals("Up for a chat", engine.profile.commentText)
        assertNull(engine.ownAvatar)
        await { compose.onAllNodesWithText("Up for a chat").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theAppearanceIsChosenAndAPastedThemeApplied() {
        connect()
        await { shown(MORE_THEMES_TEST_TAG) }
        compose.onNodeWithTag(MORE_THEMES_TEST_TAG).performClick()
        await { shown(themeModeTestTag(ThemeMode.DARK)) }
        compose.onNodeWithTag(themeTileTestTag(FAKE_THEME.id)).assertIsSelected()

        compose.onNodeWithTag(themeModeTestTag(ThemeMode.DARK)).performClick()
        await { engine.themeSettings.mode == ThemeMode.DARK }
        compose.onNodeWithTag(themeModeTestTag(ThemeMode.DARK)).assertIsSelected()

        compose.onNodeWithTag(THEMES_PASTE_TEST_TAG).performScrollTo().performClick()
        await { shown(THEMES_CODE_FIELD_TEST_TAG) }
        compose.onNode(field(THEMES_CODE_FIELD_TEST_TAG)).performTextInput("not a theme")
        // The field carries the error for screen readers; the text under it is not read twice.
        val invalid = SemanticsMatcher.expectValue(SemanticsProperties.Error, "This isn't a valid Mumcord theme.")
        await { compose.onAllNodes(invalid).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(field(THEMES_CODE_FIELD_TEST_TAG)).performTextReplacement("🎨 Mumcord theme \"Imported\" – $FAKE_THEME_CODE")
        await { shown(THEMES_PREVIEW_TEST_TAG) }
        compose.onNodeWithTag(THEMES_APPLY_TEST_TAG).performScrollTo().press()

        await { engine.themeSettings.appThemeId == "custom:1" }
        await { shown(themeTileTestTag("custom:1")) }
        compose.onNodeWithTag(themeTileTestTag("custom:1")).performScrollTo().assertIsSelected()
    }

    /** The editable text inside the text field tagged [tag] (the tag is on the field's frame). */
    private fun field(tag: String) = hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
