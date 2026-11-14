// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Where the local Mumble test server is reachable from the emulator. */
data class TestServer(val host: String, val port: Int) {
    companion object {
        /** Channels below the root that the test environment creates on a fresh server. */
        val SEEDED_CHANNELS = setOf("Lobby", "Raid", "AFK")

        private const val HOST_ARGUMENT = "serverHost"
        private const val PORT_ARGUMENT = "serverPort"

        /** The server e2e/run.sh passes as runner arguments, or null when the tests were started without it. */
        fun fromRunnerArguments(): TestServer? {
            val arguments = InstrumentationRegistry.getArguments()
            val host = arguments.getString(HOST_ARGUMENT) ?: return null
            val port = arguments.getString(PORT_ARGUMENT)?.toIntOrNull() ?: return null
            return TestServer(host, port)
        }
    }
}

/**
 * Skips a test when no test server was passed, so that a plain run of all emulator tests does
 * not fail for the lack of a server. Put it before the rule that starts the app.
 */
class RequireTestServer : TestRule {
    lateinit var server: TestServer
        private set

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val passed = TestServer.fromRunnerArguments()
            assumeTrue("No test server passed; end-to-end tests run through e2e/run.sh", passed != null)
            server = checkNotNull(passed)
            base.evaluate()
        }
    }
}
