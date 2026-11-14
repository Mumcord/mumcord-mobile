// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeEvent
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.CertificatePrompt
import app.mumcord.mobile.bridge.MumbleLink
import app.mumcord.mobile.bridge.PasswordInput
import app.mumcord.mobile.bridge.ServerInput
import app.mumcord.mobile.bridge.ServerPing
import app.mumcord.mobile.bridge.TalkMode
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
        channels = listOf(channel(0u, "Root")),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )
    private val prompt = CertificatePrompt(fingerprint = "AA:BB", previousFingerprint = null)

    private val engines = mutableListOf<FakeEngine>()
    private val logs = mutableListOf<String>()
    private var engineStartFailure: Throwable? = null
    private val saved = savedServer(1, "Nordlicht")
    private val idle = SessionState(servers = listOf(saved), serversLoaded = true)

    private suspend fun TestScope.repository(prompt: CertificatePrompt? = this@SessionRepositoryTest.prompt) =
        SessionRepository(
            engineFactory = { listener ->
                engineStartFailure?.let { throw it }
                FakeEngine(listener, server, prompt, servers = listOf(saved)).also { engines += it }
            },
            scope = backgroundScope,
            engineDispatcher = StandardTestDispatcher(testScheduler),
            log = { logs += it },
            linkParser = { url ->
                if (url.startsWith("mumble://")) {
                    MumbleLink("link.example", 64738u, null, emptyList())
                } else {
                    throw BridgeException.InvalidInput("not a link: $url")
                }
            },
        ).also { it.loadServers() }

    @Test
    fun connectShowsConnectingAtOnceThenThePromptThenTheServer() = runTest {
        val repository = repository()

        repository.connectServer(1, null, emptyList())
        assertEquals(ConnectionPhase.Connecting, repository.state.value.phase)
        assertEquals(1L, repository.state.value.activeServerId)
        runCurrent()
        assertEquals(CertificatePromptState("AA:BB", null), repository.state.value.certificatePrompt)
        assertEquals(
            listOf(ConnectCall(1, null, emptyList())),
            engines.single().connectCalls.toList(),
        )

        repository.answerCertificatePrompt("AA:BB", trust = true)
        assertNull(repository.state.value.certificatePrompt)
        runCurrent()
        assertEquals(listOf("AA:BB" to true), engines.single().certificateAnswers.toList())
        assertTrue(repository.state.value.showsServer)

        repository.disconnect()
        runCurrent()
        assertEquals(idle, repository.state.value)
    }

    @Test
    fun rejectingTheCertificateEndsInTheCertificateRejectedError() = runTest {
        val repository = repository()
        repository.connectServer(1, null, emptyList())
        runCurrent()

        repository.answerCertificatePrompt("AA:BB", trust = false)
        runCurrent()

        assertEquals(
            idle.copy(phase = ConnectionPhase.Failed(ConnectionError.CertificateRejected), activeServerId = 1),
            repository.state.value,
        )
    }

    @Test
    fun theEngineIsCreatedOnceAndReusedForTheNextConnection() = runTest {
        val repository = repository(prompt = null)
        repository.connectServer(1, null, emptyList())
        repository.disconnect()
        repository.connectServer(1, null, emptyList())
        runCurrent()

        assertEquals(1, engines.size)
        assertEquals(2, engines.single().connectCalls.size)
        assertTrue(repository.state.value.showsServer)
    }

    @Test
    fun eventsAreFoldedInTheOrderTheEngineSentThem() = runTest {
        val repository = repository(prompt = null)
        repository.connectServer(1, null, emptyList())
        val engine = engines.single()
        engine.emit(BridgeEvent.PersonChanged(person(11u, "Alice", channelId = 0u)))
        engine.emit(BridgeEvent.PersonChanged(person(11u, "Alice renamed", channelId = 0u)))
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
        repository.connectServer(1, null, emptyList())
        repository.disconnect()
        runCurrent()
        engines.single().connectFailure = BridgeException.InvalidInput("bad address secret.example")

        repository.connectServer(1, null, emptyList())
        runCurrent()

        assertEquals(
            idle.copy(phase = ConnectionPhase.Failed(ConnectionError.InvalidInput), activeServerId = 1),
            repository.state.value,
        )
        assertEquals(listOf("connect failed: InvalidInput (InvalidInput)"), logs)
    }

    @Test
    fun aFailedEngineStartIsReportedAndTheNextTryStartsItAgain() = runTest {
        engineStartFailure = BridgeException.SecureStorageUnavailable("locked")
        val repository = repository(prompt = null)
        assertEquals(
            SessionState(phase = ConnectionPhase.Failed(ConnectionError.SecureStorageUnavailable)),
            repository.state.value,
        )

        engineStartFailure = UnsatisfiedLinkError("no library")
        repository.connectServer(1, null, emptyList())
        runCurrent()
        assertEquals(ConnectionPhase.Failed(ConnectionError.Internal), repository.state.value.phase)

        engineStartFailure = null
        repository.dismissFailure()
        repository.loadServers()
        assertEquals(idle, repository.state.value)
        repository.connectServer(1, null, emptyList())
        runCurrent()
        assertTrue(repository.state.value.showsServer)
    }

    @Test
    fun theServerListFollowsAddEditAndRemove() = runTest {
        val repository = repository()
        val input = ServerInput("Tidepool", "voice.tidepool.example", 64738u, "Me", PasswordInput.Set("secret"), null)

        val added = (repository.addServer(input) as Outcome.Ok).value
        runCurrent()
        assertEquals(listOf(saved, added), repository.state.value.servers)
        assertTrue(added.hasPassword)

        repository.updateServer(added.id, input.copy(name = "Tide", password = PasswordInput.Clear))
        runCurrent()
        assertEquals(listOf("Nordlicht", "Tide"), repository.state.value.servers.map { it.name })
        assertEquals(listOf(false, false), repository.state.value.servers.map { it.hasPassword })

        assertEquals(Outcome.Ok(Unit), repository.removeServer(added.id))
        runCurrent()
        assertEquals(listOf(saved), repository.state.value.servers)
        assertEquals(Outcome.Failed(ConnectionError.NotFound), repository.removeServer(added.id))
    }

    @Test
    fun aFailedSaveIsReturnedToTheFormAndLeavesTheConnectionAlone() = runTest {
        val repository = repository()
        engines.single().saveFailure = BridgeException.SecureStorageUnavailable("locked")

        val result = repository.addServer(ServerInput("", "a.example", 64738u, "Me", PasswordInput.Keep, null))

        assertEquals(Outcome.Failed(ConnectionError.SecureStorageUnavailable), result)
        assertEquals(idle, repository.state.value)
    }

    @Test
    fun aPingAnswersWithPeopleAndLatencyOrNothing() = runTest {
        val repository = repository()
        engines.single().pings[1] = ServerPing(latencyMs = 24u, peopleOnline = 5u, maxPeople = 50u)

        assertEquals(ServerPing(24u, 5u, 50u), repository.pingServer(1, refresh = false))
        assertNull(repository.pingServer(99, refresh = false))
    }

    @Test
    fun thePasswordOfARetryIsPassedOnButNeverLogged() = runTest {
        val repository = repository(prompt = null)
        engines.single().requiredPassword = "open sesame"

        repository.connectServer(1, null, emptyList())
        runCurrent()
        assertEquals(ConnectionPhase.Failed(ConnectionError.WrongPassword), repository.state.value.phase)
        assertEquals(1L, repository.state.value.activeServerId)

        repository.connectServer(1, "open sesame", listOf("Lobby"))
        runCurrent()
        assertTrue(repository.state.value.showsServer)
        assertEquals(ConnectCall(1, "open sesame", listOf("Lobby")), engines.single().connectCalls.last())
        assertTrue(logs.none { "sesame" in it })
    }

    @Test
    fun theTalkModeIsSavedInTheEngine() = runTest {
        val repository = repository()

        assertEquals(Outcome.Ok(Unit), repository.setTalkMode(TalkMode.PUSH_TO_TALK))

        assertEquals(TalkMode.PUSH_TO_TALK, engines.single().talkMode)
    }

    @Test
    fun aLinkIsOnlyParsedAndABrokenOneIsAnError() = runTest {
        val repository = repository()

        assertEquals(
            Outcome.Ok(MumbleLink("link.example", 64738u, null, emptyList())),
            repository.parseLink("mumble://link.example"),
        )
        assertEquals(Outcome.Failed(ConnectionError.InvalidInput), repository.parseLink("https://link.example"))
        assertEquals(idle, repository.state.value)
        assertTrue(engines.single().connectCalls.isEmpty())
    }

    @Test
    fun dismissingAFailureGoesBackToIdle() = runTest {
        val repository = repository()
        engines.single().connectFailure = BridgeException.Banned("reason from the server")
        repository.connectServer(1, null, emptyList())
        runCurrent()
        assertEquals(ConnectionPhase.Failed(ConnectionError.Banned), repository.state.value.phase)

        repository.dismissFailure()

        assertEquals(idle, repository.state.value)
    }
}
