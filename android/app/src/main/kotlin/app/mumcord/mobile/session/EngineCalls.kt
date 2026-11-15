// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumcordEngineInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** A command for the engine that may wait; it gets the engine only for the call. */
typealias EngineCommand<T> = suspend (MumcordEngineInterface) -> T

/**
 * The one way the app calls the engine. Nothing else may hold a [MumcordEngineInterface]
 * (`EngineCallsSourceTest` checks that).
 *
 * Why: a waiting bridge command is finished by a native thread of the bridge, which is attached
 * to the Java runtime only for that one callback. A coroutine that continues right there (one
 * without a dispatcher of its own, `Dispatchers.Unconfined`) makes the bridge call back a second
 * time inside the first, and the native layer then detaches the thread while Kotlin code is
 * still running on it. Android ends the process for that ("attempting to detach while still
 * running code"); there is no exception to catch. So every waiting command runs on
 * [dispatcher], whatever the caller's context is, and the caller gets the result on its own
 * context.
 *
 * @param dispatcher where commands run and where the engine is created (it reads files and the
 *   secure storage); the app uses [newDispatcher].
 * @param listener called on the bridge's event thread: it must only queue what it gets and
 *   never call the engine.
 * @param checkThreads fail a call that is made from inside [listener] (debug builds and tests)
 *   instead of leaving it to the native layer.
 */
class EngineCalls(
    private val factory: EngineFactory,
    private val dispatcher: CoroutineDispatcher,
    listener: EngineListener,
    private val checkThreads: Boolean,
) {
    private val lock = Mutex()

    // The event thread cannot be told by its name: while it is attached to the Java runtime it
    // is called "Thread-N" like any other. So the listener marks it for as long as it runs.
    private val inListener = ThreadLocal<Boolean>()
    private val markingListener = object : EngineListener {
        override fun onEvent(event: BridgeEvent) {
            inListener.set(true)
            try {
                listener.onEvent(event)
            } finally {
                inListener.set(false)
            }
        }
    }

    // Also read without the lock by the commands that must not wait.
    @Volatile
    private var engine: MumcordEngineInterface? = null

    /**
     * Runs a command that may wait. The engine is created on first use and kept for the life of
     * the process; a failed start is retried by the next call.
     */
    suspend fun <T> call(command: EngineCommand<T>): T {
        checkThread()
        return withContext(dispatcher) { command(engine()) }
    }

    /**
     * Runs a command that never waits (a button let go must always get through) on the caller's
     * thread. Null while no engine exists.
     */
    fun <T : Any> now(command: (MumcordEngineInterface) -> T): T? {
        checkThread()
        return engine?.let(command)
    }

    private suspend fun engine(): MumcordEngineInterface = lock.withLock {
        engine ?: factory.create(markingListener).also { engine = it }
    }

    private fun checkThread() {
        check(!checkThreads || inListener.get() != true) {
            "The engine was called from inside its listener, on the bridge's event thread. " +
                "A listener must only queue its events."
        }
    }

    companion object {
        const val THREAD_NAME = "mumcord-engine-calls"

        /** One thread of its own, so commands reach the engine in the order they were given. */
        fun newDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, THREAD_NAME).apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}
