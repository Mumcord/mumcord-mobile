// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.session.savedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFormStateTest {
    private val filled = ServerFormState(address = "nordlicht.example", username = "Me")

    @Test
    fun aNewFormStartsWithTheStandardPortAndIsNotValid() {
        val form = ServerFormState()

        assertEquals("64738", form.port)
        assertFalse(form.isEdit)
        assertFalse(form.isValid)
        assertTrue(form.addressMissing)
        assertTrue(form.usernameMissing)
        assertNull(form.toInput())
    }

    @Test
    fun portsOutsideTheValidRangeAreRejected() {
        assertNull(filled.copy(port = "").portNumber)
        assertNull(filled.copy(port = "0").portNumber)
        assertNull(filled.copy(port = "65536").portNumber)
        assertNull(filled.copy(port = "12a").portNumber)
        assertNull(filled.copy(port = "-1").portNumber)
        assertEquals(65535u.toUShort(), filled.copy(port = " 65535 ").portNumber)
        assertFalse(filled.copy(port = "0").isValid)
    }

    @Test
    fun addressAndUserNameAreRequiredAndTheNameIsNot() {
        assertFalse(filled.copy(address = " ").isValid)
        assertFalse(filled.copy(username = " ").isValid)
        assertTrue(filled.copy(name = "").isValid)
    }

    @Test
    fun theInputIsTrimmedAndANewServerWithoutPasswordKeepsNone() {
        val form = ServerFormState(name = " Nordlicht ", address = " nordlicht.example ", port = " 40000 ", username = " Me ")

        assertEquals(
            ServerInput("Nordlicht", "nordlicht.example", 40000u, "Me", PasswordInput.Keep, null),
            form.toInput(),
        )
    }

    @Test
    fun aTypedPasswordIsSetExactlyAsTyped() {
        assertEquals(PasswordInput.Set(" pass word "), filled.copy(password = " pass word ").toInput()?.password)
    }

    @Test
    fun editingKeepsTheSavedPasswordUnlessItIsReplacedOrRemoved() {
        val form = ServerFormState.edit(savedServer(7, "Nordlicht", hasPassword = true))

        assertTrue(form.isEdit)
        assertTrue(form.hasSavedPassword)
        // The saved password itself is never part of the form.
        assertEquals("", form.password)
        assertEquals(PasswordInput.Keep, form.toInput()?.password)
        assertEquals(PasswordInput.Clear, form.copy(removeSavedPassword = true).toInput()?.password)
        assertEquals(
            PasswordInput.Set("new"),
            form.copy(removeSavedPassword = true, password = "new").toInput()?.password,
        )
    }

    @Test
    fun editingStartsFromTheSavedEntry() {
        val form = ServerFormState.edit(savedServer(7, "Nordlicht", address = "n.example", port = 1234u, username = "Anna"))

        assertEquals(
            ServerFormState(serverId = 7, name = "Nordlicht", address = "n.example", port = "1234", username = "Anna"),
            form,
        )
    }
}
