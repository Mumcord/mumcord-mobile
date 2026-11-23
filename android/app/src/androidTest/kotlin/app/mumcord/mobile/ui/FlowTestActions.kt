// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction

/**
 * Presses a button as a screen reader does, wherever it is at that moment.
 *
 * Typing brings up the keyboard, which lifts the sheets and the chat's input bar by its height
 * while it slides in. A tap aims at where the button is when the tap is prepared; on the slow CI
 * emulator the layout moves before the tap arrives, and the tap lands on the backdrop, which
 * closes the sheet, or on nothing at all. Use it for every button pressed while the keyboard may
 * still be moving.
 */
fun SemanticsNodeInteraction.press(): SemanticsNodeInteraction =
    assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

/**
 * Taps "Connect to Nordlicht" on the server list once it is there. The app reads the saved
 * servers from the engine off the main thread after the first frame; Compose's idle check does
 * not wait for that, and on the slow CI emulator the first frames show no server yet.
 */
fun ComposeTestRule.connectToNordlicht() {
    waitUntil(SERVER_LIST_TIMEOUT_MILLIS) {
        onAllNodesWithContentDescription(CONNECT_TO_NORDLICHT).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithContentDescription(CONNECT_TO_NORDLICHT).performClick()
}

private const val CONNECT_TO_NORDLICHT = "Connect to Nordlicht"
private const val SERVER_LIST_TIMEOUT_MILLIS = 10_000L
