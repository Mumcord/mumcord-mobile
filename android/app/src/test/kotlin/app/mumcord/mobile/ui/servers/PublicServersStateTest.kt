// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.session.publicServer
import app.mumcord.mobile.session.savedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicServersStateTest {
    private val nordic = publicServer("Nordic Friends", "voice.example.org", "Sweden", "SE")
    private val berlin = publicServer("berlin Gaming", "mumble.example.de", port = 40000u)
    private val alpine = publicServer("Alpine Lounge", "alps.example.ch", "Switzerland", "CH")
    private val nowhere = publicServer("Somewhere", "somewhere.example", "", "")
    private val list = listOf(nordic, berlin, alpine, nowhere)

    @Test
    fun searchMatchesNameAddressAndCountryWhateverTheCase() {
        assertTrue(nordic.matches("FRIENDS", null))
        assertTrue(nordic.matches("voice.example", null))
        assertTrue(nordic.matches("swe", null))
        assertTrue(nordic.matches("  ", null))
        assertFalse(nordic.matches("berlin", null))
    }

    @Test
    fun theCountryFilterKeepsOnlyThatCountry() {
        assertTrue(berlin.matches("", "de"))
        assertFalse(nordic.matches("", "DE"))
        assertFalse(nowhere.matches("", "DE"))
    }

    @Test
    fun countriesAreListedOnceByNameAndServersWithoutACountryAreLeftOut() {
        val list = list + publicServer("Munich", "munich.example") + publicServer("Odd", "odd.example", "", "NO")

        assertEquals(
            listOf(
                PublicCountry("DE", "Germany"),
                PublicCountry("NO", "NO"),
                PublicCountry("SE", "Sweden"),
                PublicCountry("CH", "Switzerland"),
            ),
            countriesOf(list),
        )
    }

    @Test
    fun aSavedServerMatchesByAddressWhateverTheCaseAndByPort() {
        val saved = listOf(
            savedServer(1, "Mine", address = "VOICE.example.org"),
            savedServer(2, "Other port", address = "mumble.example.de"),
        )

        assertEquals(1L, savedMatch(saved, nordic)?.id)
        assertNull(savedMatch(saved, berlin))
    }

    @Test
    fun rowsAreSortedByNameAndCarryAddressCountryPingAndTheSavedServer() {
        val local = PublicServersLocal(
            servers = list,
            pings = mapOf(nordic.key() to PublicPing.Answered(12, 50, 34), berlin.key() to PublicPing.NoAnswer),
            adding = setOf(alpine.key()),
        )
        val state = publicServersUiState(local, listOf(savedServer(9, "Mine", address = "voice.example.org")))

        assertEquals(PublicListStatus.Ready, state.status)
        assertEquals(4, state.total)
        assertEquals(
            listOf(
                PublicServerRow(alpine.key(), "Alpine Lounge", "AL", "alps.example.ch", "Switzerland", null, null, adding = true),
                PublicServerRow(berlin.key(), "berlin Gaming", "BG", "mumble.example.de:40000", "Germany", PublicPing.NoAnswer, null, adding = false),
                PublicServerRow(nordic.key(), "Nordic Friends", "NF", "voice.example.org", "Sweden", PublicPing.Answered(12, 50, 34), 9L, adding = false),
                PublicServerRow(nowhere.key(), "Somewhere", "S", "somewhere.example", "", null, null, adding = false),
            ),
            state.rows,
        )
    }

    @Test
    fun searchAndCountryNarrowTheRowsButNotTheTotal() {
        val state = publicServersUiState(PublicServersLocal(servers = list, search = "example", country = "SE"), emptyList())

        assertEquals(listOf("Nordic Friends"), state.rows.map { it.name })
        assertEquals(4, state.total)
        assertEquals("SE", state.country)
    }

    @Test
    fun theStatusFollowsTheLoad() {
        fun status(local: PublicServersLocal) = publicServersUiState(local, emptyList()).status

        assertEquals(PublicListStatus.Loading, status(PublicServersLocal(loading = true)))
        assertEquals(PublicListStatus.Failed, status(PublicServersLocal(loadFailed = true)))
        assertEquals(PublicListStatus.Loading, status(PublicServersLocal(loadFailed = true, loading = true)))
        assertEquals(PublicListStatus.Empty, status(PublicServersLocal(servers = emptyList())))
        assertEquals(PublicListStatus.NoMatch, status(PublicServersLocal(servers = list, search = "nothing like it")))
    }

    @Test
    fun aRefreshOverAShownListKeepsTheList() {
        val refreshing = publicServersUiState(PublicServersLocal(servers = list, loading = true), emptyList())
        assertEquals(PublicListStatus.Ready, refreshing.status)
        assertTrue(refreshing.refreshing)

        val failed = publicServersUiState(PublicServersLocal(servers = list, refreshFailed = true), emptyList())
        assertEquals(4, failed.rows.size)
        assertTrue(failed.refreshFailed)
    }

    @Test
    fun aPingBecomesAnAnswerOrNoAnswer() {
        assertEquals(PublicPing.Answered(3, 20, 41), ServerPing(latencyMs = 41u, peopleOnline = 3u, maxPeople = 20u).toPublicPing())
        assertEquals(PublicPing.NoAnswer, null.toPublicPing())
    }

    @Test
    fun theKeyIgnoresTheCaseOfTheHost() {
        assertEquals(publicServer("A", "Voice.Example.org").key(), publicServer("B", "voice.example.ORG").key())
    }
}
