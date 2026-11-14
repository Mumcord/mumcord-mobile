// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.slice

import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.ConnectRequest
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SliceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(Channel(id = 0u, parentId = null, name = "Root", position = 0)),
        people = listOf(Person(session = 10u, channelId = 0u, name = "Me")),
    )
    private val engines = mutableListOf<FakeEngine>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SliceViewModel {
        val repository = SessionRepository(
            engineFactory = { listener -> FakeEngine(listener, server).also { engines += it } },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return SliceViewModel(repository).also { viewModel ->
            // The state is only kept up to date while somebody watches it, as a screen would.
            backgroundScope.launch { viewModel.uiState.collect() }
        }
    }

    @Test
    fun theFormStartsWithTheStandardPortAndCannotConnectWhileIncomplete() = runTest(dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        assertEquals(ConnectForm(address = "", port = "64738", username = ""), viewModel.uiState.value.form)
        assertFalse(viewModel.uiState.value.canConnect)

        viewModel.connect()
        runCurrent()
        assertTrue(engines.isEmpty())
    }

    @Test
    fun portsOutsideTheValidRangeAreRejected() {
        assertNull(ConnectForm(port = "").portNumber)
        assertNull(ConnectForm(port = "0").portNumber)
        assertNull(ConnectForm(port = "65536").portNumber)
        assertNull(ConnectForm(port = "12a").portNumber)
        assertNull(ConnectForm(port = "-1").portNumber)
        assertEquals(65535u.toUShort(), ConnectForm(port = " 65535 ").portNumber)
        assertFalse(ConnectForm(address = "host", port = "0", username = "Me").isValid)
        assertFalse(ConnectForm(address = " ", port = "1", username = "Me").isValid)
        assertFalse(ConnectForm(address = "host", port = "1", username = " ").isValid)
    }

    @Test
    fun connectSendsTheTrimmedFormAndCannotBeStartedTwice() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onAddressChange(" 10.0.2.2 ")
        viewModel.onPortChange("40000")
        viewModel.onUsernameChange(" Me ")
        runCurrent()
        assertTrue(viewModel.uiState.value.canConnect)

        viewModel.connect()
        runCurrent()
        viewModel.connect()
        runCurrent()

        assertEquals(
            listOf(ConnectRequest("10.0.2.2", 40000u, "Me", null)),
            engines.single().connectRequests.toList(),
        )
        assertTrue(viewModel.uiState.value.session.showsServer)
        assertFalse(viewModel.uiState.value.canConnect)
    }

    @Test
    fun disconnectReturnsToAFormThatCanConnectAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onAddressChange("10.0.2.2")
        viewModel.onUsernameChange("Me")
        viewModel.connect()
        runCurrent()

        viewModel.disconnect()
        runCurrent()

        assertFalse(viewModel.uiState.value.session.showsServer)
        assertTrue(viewModel.uiState.value.canConnect)
        assertEquals("10.0.2.2", viewModel.uiState.value.form.address)
    }
}
