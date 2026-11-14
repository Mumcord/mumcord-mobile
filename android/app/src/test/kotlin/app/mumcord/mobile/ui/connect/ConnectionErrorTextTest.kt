// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import app.mumcord.mobile.R
import app.mumcord.mobile.session.ConnectionError
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionErrorTextTest {
    @Test
    fun theErrorsOfTheConnectFlowHaveTheirOwnSentence() {
        assertEquals(R.string.error_server_unreachable, ConnectionError.ServerUnreachable.messageRes())
        assertEquals(R.string.error_wrong_password, ConnectionError.WrongPassword.messageRes())
        assertEquals(R.string.error_name_in_use, ConnectionError.NameInUse.messageRes())
        assertEquals(R.string.error_certificate_rejected, ConnectionError.CertificateRejected.messageRes())
        assertEquals(R.string.error_certificate_changed, ConnectionError.CertificateChanged.messageRes())
        assertEquals(R.string.error_banned, ConnectionError.Banned.messageRes())
    }

    @Test
    fun everyErrorHasASentenceOfItsOwn() {
        val messages = ConnectionError.entries.map { it.messageRes() }

        assertEquals(ConnectionError.entries.size, messages.toSet().size)
    }
}
