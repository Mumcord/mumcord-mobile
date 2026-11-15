// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The daily check against a fake release source, and the HTTP source against a local server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val release = AppInfo("0.2.0", "0.8.0 (6d2ac79e)", debuggable = false)
    private var asked = 0
    private var answer: () -> String = { releaseJson() }
    private val source = ReleaseSource {
        asked++
        answer()
    }
    private val store = InMemoryUpdateStore()
    private val logged = mutableListOf<String>()

    private fun TestScope.checker(info: AppInfo = release) = UpdateChecker(
        info,
        source,
        store,
        clock = { currentTime },
        log = { logged += it },
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun aNewerReleaseIsFoundAndRemembered() = runTest {
        val checker = checker()
        checker.checkIfDue(enabled = true)
        val expected = AvailableUpdate(ReleaseVersion(0, 3, 0), hasApk = true)
        assertEquals(expected, checker.available.value)
        assertEquals(expected, store.available)
        assertEquals(1, asked)
        // After a restart the notice is there without asking again.
        assertEquals(expected, checker().available.value)
    }

    @Test
    fun atMostOnceADay() = runTest {
        val checker = checker()
        checker.checkIfDue(enabled = true)
        testScheduler.advanceTimeBy(CHECK_INTERVAL_MILLIS - 1)
        checker.checkIfDue(enabled = true)
        assertEquals(1, asked)
        testScheduler.advanceTimeBy(1)
        checker.checkIfDue(enabled = true)
        assertEquals(2, asked)
    }

    @Test
    fun nothingIsAskedWithTheSwitchOffOrInDebugAndTestBuilds() = runTest {
        checker().checkIfDue(enabled = false)
        checker(AppInfo("0.1.0", "", debuggable = true)).checkIfDue(enabled = true)
        checker(AppInfo("0.0.0-dryrun+0123456789ab", "", debuggable = false)).checkIfDue(enabled = true)
        assertEquals(0, asked)
        assertNull(store.lastCheckMillis)
    }

    @Test
    fun aFailedCheckWaitsADayAndLogsNoDetails() = runTest {
        answer = { throw IOException("connect to 192.0.2.7 failed") }
        val checker = checker()
        checker.checkIfDue(enabled = true)
        checker.checkIfDue(enabled = true)
        assertEquals(1, asked)
        assertNull(checker.available.value)
        assertEquals(listOf("update check failed: IOException"), logged)

        answer = { "{\"tag_name\": \"v9.9.9\"}" }
        testScheduler.advanceTimeBy(CHECK_INTERVAL_MILLIS)
        checker.checkIfDue(enabled = true)
        assertNull(checker.available.value)
        assertEquals("update check failed: InvalidReleaseException", logged.last())
    }

    @Test
    fun aClosedNoticeIsRemembered() = runTest {
        val checker = checker()
        checker.dismiss(ReleaseVersion(0, 3, 0))
        assertEquals(ReleaseVersion(0, 3, 0), checker.dismissed.value)
        assertEquals(ReleaseVersion(0, 3, 0), checker().dismissed.value)
    }

    @Test
    fun theHttpSourceReadsTheAnswerAndRefusesEverythingElse() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun route(path: String, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) =
            server.createContext(path) { exchange ->
                headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
                exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        route("/ok", 200, releaseJson().toByteArray())
        route("/missing", 404, "{}".toByteArray())
        route("/moved", 302, ByteArray(0), mapOf("Location" to "/ok"))
        route("/huge", 200, ByteArray(GitHubReleaseSource.MAX_ANSWER_BYTES + 1) { ' '.code.toByte() })
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(releaseJson(), GitHubReleaseSource("$base/ok").latestReleaseJson())
            listOf("/missing", "/moved", "/huge").forEach { path ->
                assertThrows(path, IOException::class.java) { GitHubReleaseSource("$base$path").latestReleaseJson() }
            }
        } finally {
            server.stop(0)
        }
    }
}
