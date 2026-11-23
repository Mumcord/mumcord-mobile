// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.connect.CERTIFICATE_TRUST_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_IDENTITIES_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_CARD_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_CREATE_TEST_TAG
import app.mumcord.mobile.ui.setup.TALK_MODE_CONTINUE_TEST_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The identity of the phone (FR-5.60, FR-5.61, AC-5.1) in the real app with the real engine and
 * the phone's secure storage: the first start asks for an identity before anything else, the
 * one created there is what the test server sees, and it is the same on the next connect. The
 * second half of the proof is e2e/identity-watch.py, which compares the certificate the server
 * announces for the phone on both connects (the scenario "identity" of e2e/run.sh). Expects
 * fresh app data.
 */
class IdentityFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Test
    fun theIdentityCreatedAtTheFirstStartIsUsedOnEveryConnectAndListedUnderMore() {
        // AC-5.1: the identity step comes first, the talk mode after it.
        app.awaitOrFail("the identity step of the first start") { app.exists(IDENTITY_CREATE_TEST_TAG) }
        assertFalse("The talk mode was asked for before the identity", app.exists(TALK_MODE_CONTINUE_TEST_TAG))
        app.chooseTalkMode()
        app.addServer()

        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        // More → Identities lists it as the default one, used by the server just added.
        compose.onNodeWithText(TAB_MORE).performClick()
        app.awaitOrFail("the More tab") { app.exists(MORE_IDENTITIES_TEST_TAG) }
        compose.onNodeWithTag(MORE_IDENTITIES_TEST_TAG).performClick()
        app.awaitOrFail("the identity list") { app.exists(IDENTITY_CARD_TEST_TAG) }
        val card = "${AppDriver.USER_NAME} (default), Used on 1 server"
        assertTrue(
            "The created identity is not listed as the default one of one server",
            compose.onAllNodes(hasContentDescription(card, substring = true)).fetchSemanticsNodes().size == 1,
        )
        compose.onNodeWithContentDescription(BACK).performClick()
        app.awaitOrFail("the More tab after the identity list") { app.exists(MORE_IDENTITIES_TEST_TAG) }

        app.disconnect()

        // The second connect uses the same certificate; the second person checks that.
        app.tapConnect()
        app.awaitOrFail("the channel list on the second connect") {
            app.exists(CERTIFICATE_TRUST_TEST_TAG) || app.connected()
        }
        app.assertSeededChannels()
        app.disconnect()
    }

    private companion object {
        const val TAB_MORE = "More"
        const val BACK = "Back"
    }
}
