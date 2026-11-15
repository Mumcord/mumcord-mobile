// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.channel
import app.mumcord.mobile.session.person
import app.mumcord.mobile.session.publicServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicServersControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(channel(0u, "Root")),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )
    private lateinit var engine: FakeEngine
    private lateinit var repository: SessionRepository

    // More than one page of the bridge, so reading the list in pages is covered.
    private val listed = List(130) { publicServer("Server $it", "s$it.example") }

    private fun TestScope.controller(): PublicServersController {
        repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server).also {
                    it.publicServers += listed
                    engine = it
                }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        backgroundScope.launch { repository.loadServers() }
        runCurrent()
        return PublicServersController(repository, backgroundScope)
    }

    @Test
    fun nothingIsRequestedBeforeTheBrowserOpensAndThenTheWholeListOnce() = runTest(dispatcher) {
        val controller = controller()
        assertEquals(0, engine.publicDownloads)

        controller.open()
        assertTrue(controller.local.value.loading)
        runCurrent()
        assertEquals(listed, controller.local.value.servers)
        assertEquals(1, engine.publicDownloads)

        controller.close()
        controller.open()
        runCurrent()
        assertEquals("the list loaded before is shown again", 1, engine.publicDownloads)
    }

    @Test
    fun aFailedFirstLoadIsAFailureAndTryAgainLoads() = runTest(dispatcher) {
        val controller = controller()
        engine.publicListFailure = BridgeException.ServerUnreachable("offline")
        controller.open()
        runCurrent()
        assertTrue(controller.local.value.loadFailed)
        assertNull(controller.local.value.servers)

        engine.publicListFailure = null
        controller.refresh()
        runCurrent()
        assertFalse(controller.local.value.loadFailed)
        assertEquals(130, controller.local.value.servers?.size)
    }

    @Test
    fun aFailedRefreshKeepsTheLastList() = runTest(dispatcher) {
        val controller = controller()
        controller.open()
        runCurrent()

        engine.publicListFailure = BridgeException.ServerUnreachable("offline")
        controller.refresh()
        runCurrent()

        assertEquals(2, engine.publicDownloads)
        assertEquals(listed, controller.local.value.servers)
        assertTrue(controller.local.value.refreshFailed)
        assertFalse(controller.local.value.loadFailed)
    }

    @Test
    fun visibleRowsArePingedAndTheAnswersShowAndClosingStopsThePings() = runTest(dispatcher) {
        val controller = controller()
        engine.publicPings["s1.example:64738"] = ServerPing(latencyMs = 30u, peopleOnline = 4u, maxPeople = 40u)
        controller.open()
        runCurrent()

        controller.showing(listOf(listed[1].key(), listed[2].key()))
        runCurrent()
        assertEquals(listOf("s1.example:64738", "s2.example:64738"), engine.publicPingRequests)
        assertEquals(
            mapOf(listed[1].key() to PublicPing.Answered(4, 40, 30), listed[2].key() to PublicPing.NoAnswer),
            controller.local.value.pings,
        )

        controller.close()
        controller.showing(listOf(listed[3].key()))
        runCurrent()
        assertEquals(2, engine.publicPingRequests.size)
    }

    @Test
    fun pausedPingsWaitForTheScreenToComeBack() = runTest(dispatcher) {
        val controller = controller()
        controller.open()
        runCurrent()
        controller.pausePings()
        controller.showing(listOf(listed[0].key()))
        runCurrent()
        assertTrue(engine.publicPingRequests.isEmpty())

        controller.resumePings()
        controller.showing(listOf(listed[0].key()))
        runCurrent()
        assertEquals(listOf("s0.example:64738"), engine.publicPingRequests)
    }

    @Test
    fun addingSavesTheServerWithTheNameOfTheDefaultIdentity() = runTest(dispatcher) {
        val controller = controller()
        controller.open()
        runCurrent()

        controller.add(listed[5].key())
        assertTrue(listed[5].key() in controller.local.value.adding)
        runCurrent()

        assertEquals(
            listOf(ServerInput("Server 5", "s5.example", 64738u, "Lea", PasswordInput.Keep, identityId = null)),
            engine.serverInputs,
        )
        assertTrue(controller.local.value.adding.isEmpty())
        assertEquals(
            repository.state.value.servers.single().id,
            publicServersUiState(controller.local.value, repository.state.value.servers).rows.single { it.name == "Server 5" }.savedId,
        )
    }

    @Test
    fun closingForgetsSearchAndCountry() = runTest(dispatcher) {
        val controller = controller()
        controller.open()
        controller.search("Server 1")
        controller.selectCountry("DE")
        controller.close()

        assertEquals("", controller.local.value.search)
        assertNull(controller.local.value.country)
    }
}
