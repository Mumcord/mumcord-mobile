// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.link

import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.session.savedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkConfirmStateTest {
    private val servers = listOf(savedServer(1, "Nordlicht", username = "Anna"))

    @Test
    fun aLinkToASavedServerReusesItsEntryAndItsUserName() {
        val link = MumbleLink("NORDLICHT.example", 64738u, "Someone", listOf("Lobby"))

        val state = linkConfirmState(link, servers)

        assertEquals(servers.single(), state.savedServer)
        assertEquals("Anna", state.username)
        assertFalse(state.willAddServer)
        assertFalse(state.asksForUsername)
        assertTrue(state.canConnect)
    }

    @Test
    fun theSameAddressOnAnotherPortIsAnotherServer() {
        val state = linkConfirmState(MumbleLink("nordlicht.example", 40000u, "Ben", emptyList()), servers)

        assertNull(state.savedServer)
        assertTrue(state.willAddServer)
        assertEquals("Ben", state.username)
        assertEquals(
            ServerInput("", "nordlicht.example", 40000u, "Ben", PasswordInput.Keep, null),
            state.toInput(),
        )
    }

    @Test
    fun aLinkWithoutUserNameAsksForOneBeforeItCanConnect() {
        val state = linkConfirmState(MumbleLink("new.example", 64738u, null, emptyList()), servers)

        assertTrue(state.asksForUsername)
        assertFalse(state.canConnect)
        assertTrue(state.copy(typedUsername = " Ben ").canConnect)
        assertEquals("Ben", state.copy(typedUsername = " Ben ").toInput().username)
    }

    @Test
    fun nothingCanBeConfirmedTwiceWhileItIsWorking() {
        val state = linkConfirmState(MumbleLink("new.example", 64738u, "Ben", emptyList()), servers)

        assertFalse(state.copy(working = true).canConnect)
    }
}
