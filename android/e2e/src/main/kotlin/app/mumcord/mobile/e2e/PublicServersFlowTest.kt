// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.ui.servers.PUBLIC_SERVERS_REFRESH_FAILED_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVERS_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.servers.PUBLIC_SERVER_ROW_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_BROWSE_PUBLIC_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The public server browser in the real app with the real engine (AC-5.38), against a list of
 * the test's own ([LocalPublicList]) instead of the real list service: the entries with name,
 * address and country, search and the country filter, the ping of the local test server, a
 * refresh while the list cannot be reached, and adding the test server from the list and
 * connecting to it. Expects fresh app data and local network access granted.
 */
class PublicServersFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val publicList = LocalPublicList(requireServer)

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }

    @Test
    fun theLocalListIsShownFilteredAndPingedKeptWhenUnreachableAndTheTestServerIsAddedAndConnectedTo() {
        app.chooseTalkMode()

        // No server is saved yet: the button of the empty server list.
        compose.onNodeWithTag(SERVER_BROWSE_PUBLIC_TEST_TAG).performClick()
        app.awaitOrFail("the public server browser") { app.exists(PUBLIC_SERVERS_SCREEN_TEST_TAG) }
        app.awaitOrFail("the five entries of the local list") { rows().size == ENTRIES }
        val testServer = rows().single { LocalPublicList.TEST_SERVER_NAME in it }
        assertEquals(
            "name, address and country of the test server's entry",
            listOf(LocalPublicList.TEST_SERVER_NAME, "127.0.0.1:${publicList.relayPort} · Netherlands"),
            testServer.take(2),
        )
        // The name with markup in it is plain text.
        app.awaitOrFail("the name with markup as it is") { rows().any { it.firstOrNull() == LocalPublicList.MARKUP_NAME } }

        app.awaitOrFail("people online and latency of the test server") {
            rows().single { LocalPublicList.TEST_SERVER_NAME in it }.any { PING.matches(it) }
        }
        app.awaitOrFail("no answer from the closed ports") { rows().count { "No answer" in it } == ENTRIES - 1 }

        compose.onNodeWithContentDescription(SEARCH).performTextInput("harbor")
        app.awaitOrFail("the search narrowing the list") { names() == listOf("Quiet Harbor") }
        compose.onNodeWithContentDescription(SEARCH).performTextClearance()
        compose.onNodeWithText("Germany").performClick()
        app.awaitOrFail("the country filter narrowing the list") { names() == listOf("Berlin Gaming", "Quiet Harbor") }
        compose.onNodeWithText("Any country").performClick()
        app.awaitOrFail("the whole list again") { rows().size == ENTRIES }

        publicList.takeDown()
        compose.onNodeWithText("Refresh").performClick()
        app.awaitOrFail("the calm notice of the failed refresh") { app.exists(PUBLIC_SERVERS_REFRESH_FAILED_TEST_TAG) }
        assertEquals("the last list stays", ENTRIES, rows().size)

        compose.onNodeWithContentDescription("Add ${LocalPublicList.TEST_SERVER_NAME} to your servers").performClick()
        app.awaitOrFail("Connect on the added entry") {
            compose.onAllNodes(hasContentDescription("Connect to ${LocalPublicList.TEST_SERVER_NAME}"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Connect to ${LocalPublicList.TEST_SERVER_NAME}").performClick()
        app.trustCertificate()
        app.awaitChannels()
        app.disconnect()
    }

    /** The texts of every row of the browser, top to bottom: name, address and country, ping. */
    private fun rows(): List<List<String>> =
        compose.onAllNodesWithTag(PUBLIC_SERVER_ROW_TEST_TAG).fetchSemanticsNodes().map { row ->
            row.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    private fun names(): List<String> = rows().mapNotNull { it.firstOrNull() }

    private companion object {
        const val ENTRIES = 5
        const val SEARCH = "Search by name, address or country"

        /** "12 / 50 · 34 ms" */
        val PING = Regex("""\d+ / \d+ · \d+ ms""")
    }
}
