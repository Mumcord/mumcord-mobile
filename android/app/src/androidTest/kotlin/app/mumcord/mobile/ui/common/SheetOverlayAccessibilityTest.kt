// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.common

import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.ui.FlowTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A bottom sheet is modal for screen readers (AC-5.33): while it is open, the dimmed screen
 * behind it is not in the accessibility tree, the screen reader's focus moves into the sheet and
 * comes back to the control that opened it, and a dismissible sheet closes with the "dismiss"
 * action and with Back. The test talks to the accessibility node provider the way TalkBack does.
 */
class SheetOverlayAccessibilityTest {
    @get:Rule
    val compose: FlowTestRule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)

    private lateinit var provider: AccessibilityNodeProvider

    private fun showScreen(dismissible: Boolean) {
        // Connecting the test's own accessibility service turns accessibility on in the app, as
        // TalkBack does; Compose ignores that service, so it is told to serve it anyway below.
        InstrumentationRegistry.getInstrumentation().uiAutomation
        val accessibility = compose.activity.getSystemService(AccessibilityManager::class.java)
        compose.waitUntil(TIMEOUT_MILLIS) { accessibility.isEnabled }
        compose.setContent {
            MumcordTheme {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        BasicText(OPENER, Modifier.clickable { open = true })
                        BasicText(BEHIND)
                    }
                    if (open) {
                        SheetOverlay(
                            title = TITLE,
                            onDismiss = if (dismissible) ({ open = false }) else null,
                            topInsets = WindowInsets(0),
                            bottomInsets = WindowInsets(0),
                        ) {
                            BasicText(INSIDE)
                        }
                    }
                }
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().root as ViewRootForTest
        compose.runOnUiThread {
            root.forceAccessibilityForTesting(true)
            provider = root.view.accessibilityNodeProvider
        }
    }

    private fun id(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().id

    private fun perform(node: Int, action: Int): Boolean {
        val done = compose.runOnUiThread { provider.performAction(node, action, null) }
        compose.waitForIdle()
        return done
    }

    /** Whether an accessibility service can find [node]: pruned nodes come back empty. */
    private fun reachable(node: Int) = compose.runOnUiThread { provider.createAccessibilityNodeInfo(node)?.className != null }

    private fun accessibilityFocus(): String? = compose.runOnUiThread {
        provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text?.toString()
    }

    /** Puts the screen reader on the opener and double-taps it, as TalkBack does. */
    private fun openWithScreenReader(): Pair<Int, Int> {
        val opener = id(OPENER)
        val behind = id(BEHIND)
        assertTrue(perform(opener, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(perform(opener, AccessibilityNodeInfo.ACTION_CLICK))
        compose.onNodeWithText(INSIDE).fetchSemanticsNode()
        return opener to behind
    }

    @Test
    fun theScreenBehindAnOpenSheetIsHiddenAndFocusMovesInAndBack() {
        showScreen(dismissible = true)
        val (opener, behind) = openWithScreenReader()

        assertFalse("the opener behind the sheet is reachable", reachable(opener))
        assertFalse("the text behind the sheet is reachable", reachable(behind))
        assertTrue(reachable(id(INSIDE)))
        assertEquals(TITLE, accessibilityFocus())

        val pane = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode()
        assertTrue(SemanticsActions.Dismiss in pane.config)
        assertTrue(perform(pane.id, AccessibilityNodeInfo.ACTION_DISMISS))

        compose.onNodeWithText(INSIDE).assertDoesNotExist()
        assertTrue(reachable(opener))
        assertTrue(reachable(behind))
        assertEquals(OPENER, accessibilityFocus())
    }

    @Test
    fun backClosesADismissibleSheetAndReturnsTheFocus() {
        showScreen(dismissible = true)
        openWithScreenReader()

        Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithText(INSIDE).assertDoesNotExist()
        assertEquals(OPENER, accessibilityFocus())
    }

    @Test
    fun aSheetThatMustBeDecidedHasNoDismissActionAndStaysOnBack() {
        showScreen(dismissible = false)
        val (_, behind) = openWithScreenReader()

        val pane = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode()
        assertFalse(SemanticsActions.Dismiss in pane.config)
        Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithText(INSIDE).fetchSemanticsNode()
        assertFalse(reachable(behind))
    }

    private companion object {
        const val OPENER = "Open the sheet"
        const val BEHIND = "Text behind the sheet"
        const val TITLE = "Sheet title"
        const val INSIDE = "Text in the sheet"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
