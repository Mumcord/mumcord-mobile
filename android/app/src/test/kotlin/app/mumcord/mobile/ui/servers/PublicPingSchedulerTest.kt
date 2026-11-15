// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.servers

import app.mumcord.mobile.bridge.PublicServer
import app.mumcord.mobile.session.publicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicPingSchedulerTest {
    private val servers = List(10) { publicServer("Server $it", "s$it.example") }

    /** Pings wait until the test answers them, so the test sees how many are on their way at once. */
    private val waiting = LinkedHashMap<String, CompletableDeferred<PublicPing>>()
    private val asked = mutableListOf<String>()
    private val results = mutableListOf<Pair<String, PublicPing>>()

    private fun TestScope.scheduler(concurrency: Int = 4) = PublicPingScheduler(
        scope = backgroundScope,
        ping = { server: PublicServer ->
            asked += server.host
            CompletableDeferred<PublicPing>().also { waiting[server.key()] = it }.await()
        },
        onResult = { key, ping -> results += key to ping },
        concurrency = concurrency,
    )

    private fun TestScope.answer(index: Int, ping: PublicPing = PublicPing.NoAnswer) {
        checkNotNull(waiting.remove(servers[index].key())).complete(ping)
        runCurrent()
    }

    @Test
    fun nothingIsPingedBeforeItIsStarted() = runTest {
        val scheduler = scheduler()
        scheduler.setVisible(servers.take(3))
        runCurrent()
        assertTrue(asked.isEmpty())

        scheduler.start()
        runCurrent()
        assertEquals(listOf("s0.example", "s1.example", "s2.example"), asked)
    }

    @Test
    fun onlyTheVisibleRowsArePingedAFewAtATimeTopToBottom() = runTest {
        val scheduler = scheduler(concurrency = 2)
        scheduler.start()
        scheduler.setVisible(servers.take(4))
        runCurrent()
        assertEquals(listOf("s0.example", "s1.example"), asked)

        answer(0, PublicPing.Answered(3, 10, 20))
        assertEquals(listOf("s0.example", "s1.example", "s2.example"), asked)
        assertEquals(listOf(servers[0].key() to PublicPing.Answered(3, 10, 20)), results)
    }

    @Test
    fun rowsThatScrolledAwayBeforeTheirTurnAreNotPinged() = runTest {
        val scheduler = scheduler(concurrency = 1)
        scheduler.start()
        scheduler.setVisible(servers.take(3))
        runCurrent()
        scheduler.setVisible(servers.drop(5).take(2))
        answer(0)

        assertEquals(listOf("s0.example", "s5.example"), asked)
    }

    @Test
    fun eachServerIsPingedOncePerLoadAndAgainAfterAReset() = runTest {
        val scheduler = scheduler()
        scheduler.start()
        scheduler.setVisible(servers.take(2))
        runCurrent()
        answer(0)
        answer(1)
        scheduler.setVisible(servers.take(2))
        runCurrent()
        assertEquals(2, asked.size)

        scheduler.reset()
        runCurrent()
        assertEquals(listOf("s0.example", "s1.example", "s0.example", "s1.example"), asked)
    }

    @Test
    fun stoppingCancelsThePingsOnTheirWaySendsNothingMoreAndStartingAsksThemAgain() = runTest {
        val scheduler = scheduler(concurrency = 2)
        scheduler.start()
        scheduler.setVisible(servers.take(4))
        runCurrent()
        scheduler.stop()
        runCurrent()
        waiting.values.forEach { it.complete(PublicPing.NoAnswer) }
        waiting.clear()
        runCurrent()
        assertEquals(2, asked.size)
        assertTrue("no answer arrives after stopping", results.isEmpty())

        scheduler.setVisible(servers.take(4))
        runCurrent()
        assertEquals("nothing is sent while stopped", 2, asked.size)

        scheduler.start()
        runCurrent()
        assertEquals(listOf("s0.example", "s1.example", "s0.example", "s1.example"), asked)
    }
}
