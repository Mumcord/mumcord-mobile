// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.mumcord.mobile.ui.connected.TALKING_ANNOUNCEMENT_GAP_MILLIS
import app.mumcord.mobile.ui.connected.TALKING_ANNOUNCEMENT_TEST_TAG
import app.mumcord.mobile.ui.connected.TalkingAnnouncements
import app.mumcord.mobile.ui.connected.VoiceTile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The screen reader hears who starts talking, politely and only while it is on. */
@RunWith(RobolectricTestRunner::class)
class TalkingAnnouncementsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun tile(session: UInt, name: String, talking: Boolean, isSelf: Boolean = false) =
        VoiceTile(session, name, isSelf, talking, muted = false)

    private val node get() = composeRule.onNodeWithTag(TALKING_ANNOUNCEMENT_TEST_TAG)

    @Test
    fun othersWhoStartTalkingAreAnnouncedPolitelyAndTheUserIsNot() {
        var tiles by mutableStateOf(listOf(tile(1u, "Anna", talking = false), tile(9u, "Lea", talking = true, isSelf = true)))
        composeRule.setContent { TalkingAnnouncements(tiles, screenReaderOn = true, clock = { 0L }) }
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        tiles = listOf(tile(1u, "Anna", talking = true), tile(2u, "Ben", talking = true), tile(9u, "Lea", talking = true, isSelf = true))
        node.assertContentDescriptionEquals("Anna and Ben are talking")

        // Emptied after the gap, so the same sentence later is read again.
        composeRule.mainClock.advanceTimeBy(TALKING_ANNOUNCEMENT_GAP_MILLIS + 100)
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun nothingRunsWithoutScreenReader() {
        composeRule.setContent { TalkingAnnouncements(listOf(tile(1u, "Anna", talking = true)), screenReaderOn = false) }
        node.assertDoesNotExist()
    }
}
