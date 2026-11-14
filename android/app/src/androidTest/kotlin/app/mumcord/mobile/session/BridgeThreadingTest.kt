// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.ServerInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The real engine, called the way that used to end the process: from a coroutine that continues
 * wherever it is resumed. No server is needed; the commands only read and write the engine's
 * own data.
 */
class BridgeThreadingTest {
    @Test
    fun commandsThroughEngineCallsNeverContinueOnABridgeThread() = runBlocking(Dispatchers.Unconfined) {
        repeat(REPEATS) {
            assertNotNull(calls.call { it.talkMode() })
            assertEquals("continued on a bridge thread", EngineCalls.THREAD_NAME, Thread.currentThread().name)
            assertNotNull(calls.call { it.voiceSettings() })
            assertNotNull(calls.now { it.snapshot() })
        }
    }

    @Test
    fun aCallFromTheListenerThreadIsRefused() = runBlocking {
        val refused = CompletableDeferred<Boolean>()
        onEvent = { event ->
            if (event is BridgeEvent.ServersChanged && !refused.isCompleted) {
                refused.complete(runCatching { calls.now { it.snapshot() } }.exceptionOrNull() is IllegalStateException)
            }
        }
        try {
            // Changing the server list is the one event that needs no connection.
            val saved = calls.call { it.addServer(SERVER) }
            calls.call { it.removeServer(saved.id) }
            assertTrue("the call on the listener thread went through", withTimeout(EVENT_TIMEOUT_MS) { refused.await() })
        } finally {
            onEvent = {}
        }
    }

    /**
     * The same commands without [EngineCalls]. Not run: it does not fail, it ends the test
     * process. The bridge finishes `talkMode()` on one of its threads, the coroutine continues
     * right there, and the device log then shows
     * `runtime.cc] Thread[…,Native,…,"Thread-N"] attempting to detach while still running code`
     * and the run ends with "Process crashed". Remove the annotation to see it.
     */
    @Ignore("ends the process instead of failing; kept to show what EngineCalls prevents")
    @Test
    fun aRawCommandResumedOnTheBridgeThreadEndsTheProcess() = runBlocking(Dispatchers.Unconfined) {
        val engine = calls.call { it }
        assertNotNull(engine.talkMode())
        assertNotNull(engine.voiceSettings())
    }

    private companion object {
        const val REPEATS = 20
        const val EVENT_TIMEOUT_MS = 10_000L

        val SERVER = ServerInput(
            name = "Threading test",
            address = "threading.invalid",
            port = 64738u,
            username = "Test",
            password = PasswordInput.Keep,
            identityId = null,
        )

        @Volatile
        var onEvent: (BridgeEvent) -> Unit = {}

        // The engine may exist only once per process, so the tests share it.
        val calls = EngineCalls(
            factory = BridgeEngineFactory(InstrumentationRegistry.getInstrumentation().targetContext),
            dispatcher = EngineCalls.newDispatcher(),
            listener = object : EngineListener {
                override fun onEvent(event: BridgeEvent) = onEvent.invoke(event)
            },
            checkThreads = true,
        )
    }
}
