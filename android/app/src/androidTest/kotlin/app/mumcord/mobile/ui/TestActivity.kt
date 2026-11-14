// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

/** The rule of a flow test: the app's screens in a bare activity. */
typealias FlowTestRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * Makes the test's bare activity resize for the keyboard, as the app's own activity does.
 * Left alone it moves its whole window up instead, on top of the room the screens make for the
 * keyboard themselves, and pushes what the test looks at or taps off the screen whenever the
 * keyboard comes up in time.
 */
fun FlowTestRule.resizeForTheKeyboardLikeTheApp() {
    activityRule.scenario.onActivity { activity ->
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
