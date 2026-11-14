// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.ConnectRequest
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.ServerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(Channel(id = 0u, parentId = null, name = "Root", position = 0)),
        people = listOf(Person(session = 10u, channelId = 0u, name = "Me")),
    )
    private val prompt = CertificatePrompt(fingerprint = "AA:BB", previousFingerprint = null)

    private val engines = mutableListOf<FakeEngine>()
    private val logs = mutableListOf<String>()
    private var engineStartFailure: Throwable? = null

    private fun TestScope.repository(prompt: CertificatePrompt? = this@SessionRepositoryTest.prompt) =
        SessionRepository(
            engineFactory = { listener ->
                engineStartFailure?.let { throw it }
                FakeEngine(listener, server, prompt).also { engines += it }
            },
            scope = backgroundScope,
            engineDispatcher = StandardTestDispatcher(testScheduler),
            log = { logs += it },
        )

    @Test
    fun connectShowsConnectingAtOnceThenThePromptThenTheServer() = runTest {
        val repository = repository()

        repository.connect("server.example", 64738u, "Me")
        assertEquals(ConnectionPhase.Connecting, repository.state.value.phase)
        runCurrent()
        assertEquals(CertificatePromptState("AA:BB", null), repository.state.value.certificatePrompt)
        assertEquals(
            listOf(ConnectRequest("server.example", 64738u, "Me", null)),
            engines.single().connectRequests.toList(),
        )

        repository.answerCertificatePrompt("AA:BB", trust = true)
        assertNull(repository.state.value.certificatePrompt)
        runCurrent()
        assertEquals(listOf("AA:BB" to true), engines.single().certificateAnswers.toList())
        assertTrue(repository.state.value.showsServer)

        repository.disconnect()
        runCurrent()
        assertEquals(SessionState(), repository.state.value)
    }

    @Test
    fun rejectingTheCertificateEndsInTheCertificateRejectedError() = runTest {
        val repository = repository()
        repository.connect("server.example", 64738u, "Me")
        runCurrent()

        repository.answerCertificatePrompt("AA:BB", trust = false)
        runCurrent()

        assertEquals(
            SessionState(phase = ConnectionPhase.Failed(ConnectionError.CertificateRejected)),
            repository.state.value,
        )
    }

    @Test
    fun theEngineIsCreatedOnceAndReusedForTheNextConnection() = runTest {
        val repository = repository(prompt = null)
        repository.connect("server.example", 64738u, "Me")
        repository.disconnect()
        repository.connect("server.example", 64738u, "Me")
        runCurrent()

        assertEquals(1, engines.size)
        assertEquals(2, engines.single().connectRequests.size)
        assertTrue(repository.state.value.showsServer)
    }

    @Test
    fun eventsAreFoldedInTheOrderTheEngineSentThem() = runTest {
        val repository = repository(prompt = null)
        repository.connect("server.example", 64738u, "Me")
        val engine = engines.single()
        engine.emit(BridgeEvent.PersonChanged(Person(11u, 0u, "Alice")))
        engine.emit(BridgeEvent.PersonChanged(Person(11u, 0u, "Alice renamed")))
        engine.emit(BridgeEvent.PersonRemoved(10u))
        runCurrent()

        assertEquals(
            listOf(
                TreeRow.ChannelRow(id = 0u, name = "Root", peopleCount = 1, depth = 0),
                TreeRow.PersonRow(session = 11u, name = "Alice renamed", isSelf = false, depth = 1),
            ),
            repository.state.value.server?.rows,
        )
    }

    @Test
    fun aConnectTheEngineRefusesBecomesAFailureWithoutLoggingTheDetail() = runTest {
        val repository = repository()
        repository.connect("server.example", 64738u, "Me")
        repository.disconnect()
        runCurrent()
        engines.single().connectFailure = BridgeException.InvalidInput("bad address secret.example")

        repository.connect("secret.example", 64738u, "Me")
        runCurrent()

        assertEquals(
            SessionState(phase = ConnectionPhase.Failed(ConnectionError.InvalidInput)),
            repository.state.value,
        )
        assertEquals(listOf("connect failed: InvalidInput (InvalidInput)"), logs)
    }

    @Test
    fun aFailedEngineStartIsReportedAndTheNextConnectTriesAgain() = runTest {
        val repository = repository(prompt = null)
        engineStartFailure = BridgeException.SecureStorageUnavailable("locked")
        repository.connect("server.example", 64738u, "Me")
        runCurrent()
        assertEquals(
            ConnectionPhase.Failed(ConnectionError.SecureStorageUnavailable),
            repository.state.value.phase,
        )

        engineStartFailure = UnsatisfiedLinkError("no library")
        repository.connect("server.example", 64738u, "Me")
        runCurrent()
        assertEquals(ConnectionPhase.Failed(ConnectionError.Internal), repository.state.value.phase)

        engineStartFailure = null
        repository.connect("server.example", 64738u, "Me")
        runCurrent()
        assertTrue(repository.state.value.showsServer)
    }
}
