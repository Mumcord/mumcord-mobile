// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connect.CERTIFICATE_REJECT_TEST_TAG
import app.mumcord.mobile.ui.connect.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.servers.CONNECT_RETRY_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * The test server's certificate changes (AC-5.3): the phone trusts the server on the first
 * connect, then e2e/run.sh restarts the local test server with the test environment's second
 * certificate when this test asks for it in the device log ("testenv cert B"). The next connect
 * must show the strong "Server identity changed" warning; refusing it leaves the phone
 * disconnected with an error, and only trusting the new certificate connects. e2e/run.sh puts
 * the first certificate back after the scenario, also when it failed. Expects fresh app data
 * with local network access granted.
 */
class CertificateFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone = PhoneSystem()

    @Test
    fun aChangedCertificateWarnsStronglyAndNothingConnectsWithoutConsent() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()
        app.disconnect()

        switchTheServersCertificate()

        app.tapConnect()
        app.awaitOrFail("the warning about the changed certificate") { changedWarningShown() }
        assertFalse("Connected although the certificate changed", app.connected())
        compose.onNodeWithTag(CERTIFICATE_REJECT_TEST_TAG).performClick()
        app.await("the error after refusing the new certificate") { app.connectError() != null }
        assertFalse("Connected after the new certificate was refused", app.connected())

        // Trying again asks again; only the user's consent connects.
        compose.onNodeWithTag(CONNECT_RETRY_TEST_TAG).performClick()
        app.await("the warning on the second try") { changedWarningShown() }
        compose.onNodeWithTag(CERTIFICATE_TRUST_TEST_TAG).performClick()
        app.awaitChannels("the channel list after trusting the new certificate")

        app.disconnect()
    }

    private fun changedWarningShown(): Boolean =
        app.exists(CERTIFICATE_REJECT_TEST_TAG) &&
            compose.onAllNodes(hasText(CHANGED_TITLE)).fetchSemanticsNodes().isNotEmpty()

    /** Asks e2e/run.sh for the second certificate and waits until the server presents it. */
    private fun switchTheServersCertificate() {
        phone.shell("rm -f $SWITCHED_MARK")
        Log.i(LOG_TAG, "testenv cert B")
        val deadline = System.nanoTime() + SWITCH_TIMEOUT_MILLIS * 1_000_000
        while (phone.shell("ls $SWITCHED_MARK 2>/dev/null").isBlank()) {
            check(System.nanoTime() < deadline) {
                "the test server was not restarted with its second certificate within ${SWITCH_TIMEOUT_MILLIS / 1000} s (see e2e/build/testenv-cert.log)"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val LOG_TAG = "MumcordE2E"
        const val CHANGED_TITLE = "Server identity changed"
        /** e2e/run.sh creates this file once the restarted server accepts connections. */
        const val SWITCHED_MARK = "/data/local/tmp/mumcord-cert-switched"
        /** A restart of the server's container, which pulls nothing. */
        const val SWITCH_TIMEOUT_MILLIS = 120_000L
        const val POLL_MILLIS = 500L
    }
}
