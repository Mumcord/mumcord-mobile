// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ChannelPasswordState
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_MUSIC
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the connected screens show about a microphone that may not be used (FR-5.1). */
class MicrophoneNoticeTest {
    private val session = listOf(
        BridgeEvent.ConnectionChanged(ConnectionState.Connected),
        BridgeEvent.ServerSynced(mockupServer()),
    ).fold(SessionState()) { state, event -> state.fold(event) }

    @Test
    fun nothingIsShownWhileTheMicrophoneIsNotMissing() {
        assertEquals(MicrophoneNotice.None, microphoneNotice(missing = false, declined = true, promptBlocked = true))
    }

    @Test
    fun theFirstTimeItIsExplainedAndAfterANoOnlyTheNoticeStays() {
        assertEquals(MicrophoneNotice.Explain, microphoneNotice(missing = true, declined = false, promptBlocked = false))
        assertEquals(MicrophoneNotice.Denied, microphoneNotice(missing = true, declined = true, promptBlocked = false))
        assertEquals(MicrophoneNotice.Blocked, microphoneNotice(missing = true, declined = true, promptBlocked = true))
    }

    @Test
    fun onlyASecondRefusalBlocksTheSystemsPrompt() {
        assertFalse(MicrophoneAnswer(granted = true, refusedBefore = false, refusedAfter = false).blocksPrompt)
        assertFalse(MicrophoneAnswer(granted = false, refusedBefore = false, refusedAfter = true).blocksPrompt)
        // Closed without an answer.
        assertFalse(MicrophoneAnswer(granted = false, refusedBefore = false, refusedAfter = false).blocksPrompt)
        assertTrue(MicrophoneAnswer(granted = false, refusedBefore = true, refusedAfter = false).blocksPrompt)
    }

    @Test
    fun theExplanationIsASheetAndTheRefusalABanner() {
        val explain = connectedUiState(session, ConnectedLocal(), "Nordlicht", MicrophoneNotice.Explain)!!
        assertEquals(ConnectedSheet.Microphone, explain.sheet)
        assertEquals(MicrophoneNotice.None, explain.microphone)

        val denied = connectedUiState(session, ConnectedLocal(), "Nordlicht", MicrophoneNotice.Denied)!!
        assertEquals(null, denied.sheet)
        assertEquals(MicrophoneNotice.Denied, denied.microphone)
    }

    @Test
    fun onlyOneSheetAtATimeTheServersPasswordQuestionFirst() {
        val asked = session.fold(BridgeEvent.ChannelPasswordChanged(MOCKUP_MUSIC, ChannelPasswordState.REQUIRED))
        val password = connectedUiState(asked, ConnectedLocal(), "Nordlicht", MicrophoneNotice.Explain)!!.sheet
        assertTrue(password is ConnectedSheet.ChannelPassword)

        val overPerson =
            connectedUiState(session, ConnectedLocal(personSession = MOCKUP_JONAS), "Nordlicht", MicrophoneNotice.Explain)!!
        assertEquals(ConnectedSheet.Microphone, overPerson.sheet)
    }
}
