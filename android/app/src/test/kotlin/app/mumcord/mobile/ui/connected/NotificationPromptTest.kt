// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.ConnectionState
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.fold
import app.mumcord.mobile.session.mockupServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPromptTest {
    private class FakeGateway(var granted: Boolean = false, override var asked: Boolean = false) : NotificationGateway {
        override fun granted(): Boolean = granted
    }

    @Test
    fun theSheetComesAfterTheMicrophoneQuestionAndBeforeAnOpenPersonSheet() {
        val session = listOf(
            BridgeEvent.ConnectionChanged(ConnectionState.Connected),
            BridgeEvent.ServerSynced(mockupServer()),
        ).fold(SessionState()) { state, event -> state.fold(event) }
        val person = ConnectedLocal(personSession = MOCKUP_BEN)
        fun sheet(microphone: MicrophoneNotice, explain: Boolean) =
            connectedUiState(session, person, "Nordlicht", microphone, explain)!!.sheet

        assertEquals(ConnectedSheet.Microphone, sheet(MicrophoneNotice.Explain, explain = true))
        assertEquals(ConnectedSheet.Notifications, sheet(MicrophoneNotice.None, explain = true))
        assertTrue(sheet(MicrophoneNotice.None, explain = false) is ConnectedSheet.PersonSheet)
    }

    @Test
    fun theQuestionIsPutOnceAndNotNowIsRemembered() {
        val gateway = FakeGateway()
        var shown = 0
        val prompt = NotificationPrompt(gateway) { shown += 1 }
        assertTrue(prompt.explain.value)

        prompt.notNow()
        assertFalse(prompt.explain.value)
        assertTrue(gateway.asked)
        assertEquals(0, shown)
        assertFalse("Not asked again at the next start", NotificationPrompt(gateway) {}.explain.value)
    }

    @Test
    fun allowingShowsTheSessionsNotificationAndRefusingChangesNothing() {
        var shown = 0
        val allowed = NotificationPrompt(FakeGateway()) { shown += 1 }
        allowed.answer(granted = true)
        assertFalse(allowed.explain.value)
        assertEquals(1, shown)

        val refused = NotificationPrompt(FakeGateway()) { shown += 1 }
        refused.answer(granted = false)
        assertFalse(refused.explain.value)
        assertEquals(1, shown)
    }

    @Test
    fun nothingIsAskedWhereNotificationsAreAllowedAlready() {
        val gateway = FakeGateway(granted = true)
        val prompt = NotificationPrompt(gateway) {}
        assertFalse(prompt.explain.value)

        // Taken back in the system settings before the question was ever put.
        gateway.granted = false
        prompt.recheck()
        assertTrue(prompt.explain.value)
    }
}
