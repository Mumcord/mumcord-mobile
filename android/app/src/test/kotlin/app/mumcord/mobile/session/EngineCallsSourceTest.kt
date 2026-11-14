// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the engine behind [EngineCalls]: a command given anywhere else can continue on a bridge
 * thread, which ends the process on a phone (see [EngineCalls]).
 */
class EngineCallsSourceTest {
    @Test
    fun onlyEngineCallsAndTheFactoryNameTheEngine() {
        val sources = File("src/main/kotlin")
        assertTrue("run from the app module", sources.isDirectory)
        val offenders = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name !in ALLOWED }
            .filter { file -> file.useLines { lines -> lines.any { ENGINE.containsMatchIn(it) } } }
            .map { it.name }
            .toList()
        assertEquals("these files reach the engine without EngineCalls", emptyList<String>(), offenders)
    }

    private companion object {
        val ALLOWED = setOf("EngineCalls.kt", "BridgeEngineFactory.kt")

        // The generated engine class and its interface; the data types of the bridge are free.
        val ENGINE = Regex("""\bMumcordEngine(Interface)?\b""")
    }
}
