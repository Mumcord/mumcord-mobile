// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

/**
 * The session's call without a phone system: the test plays it and says when the system ends
 * the call, as it does for a headset's button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TelecomCallTest {
    /** One call the fake system took. It runs until the system or the app ends it. */
    private class FakeCall(override val coroutineContext: CoroutineContext) : CallControlScope {
        val over = CompletableDeferred<Unit>()
        val muted = MutableStateFlow(false)
        val available = MutableSharedFlow<List<CallEndpointCompat>>(replay = 1)
        val current = MutableSharedFlow<CallEndpointCompat>(replay = 1)
        val requested = mutableListOf<CallEndpointCompat>()
        lateinit var systemEnds: () -> Unit

        override fun getCallId(): ParcelUuid = throw UnsupportedOperationException()

        override suspend fun setActive(): CallControlResult = CallControlResult.Success()

        override suspend fun setInactive(): CallControlResult = CallControlResult.Success()

        override suspend fun answer(callType: Int): CallControlResult = CallControlResult.Success()

        override suspend fun disconnect(disconnectCause: DisconnectCause): CallControlResult {
            over.complete(Unit)
            return CallControlResult.Success()
        }

        override suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult {
            requested += endpoint
            return CallControlResult.Success()
        }

        override val currentCallEndpoint: Flow<CallEndpointCompat> = current
        override val availableEndpoints: Flow<List<CallEndpointCompat>> = available
        override val isMuted: Flow<Boolean> = muted
    }

    /** The phone system: takes calls until [refuse] is set. */
    private class FakeSystem : CallAdder {
        val calls = mutableListOf<FakeCall>()
        var refuse = false

        override suspend fun addCall(
            displayName: String,
            onDisconnect: () -> Unit,
            onActive: () -> Unit,
            onInactive: () -> Unit,
            block: CallControlScope.() -> Unit,
        ) {
            if (refuse) throw IllegalStateException("refused")
            coroutineScope {
                val call = FakeCall(coroutineContext)
                // As on a phone: the system says that it ended the call, and the call's scope
                // goes on until the app ends it.
                call.systemEnds = { onDisconnect() }
                calls += call
                call.block()
                call.over.await()
                // The real call's scope ends with the call.
                coroutineContext[Job]?.children?.forEach { it.cancel() }
            }
        }
    }

    private class Heard(var sessionGoesOn: Boolean) : CallListener {
        val events = mutableListOf<String>()
        var outputs: OutputState? = OutputState()

        override fun onHeld(held: Boolean) {
            events += "held $held"
        }

        override fun onEndRequested(): Boolean {
            events += "end requested"
            return sessionGoesOn
        }

        override fun onMuted(muted: Boolean) {
            events += "muted $muted"
        }

        override fun onOutputs(state: OutputState?) {
            outputs = state
            if (state == null) events += "no call"
        }
    }

    private val system = FakeSystem()
    private val logged = mutableListOf<String>()
    private val speaker = CallEndpointCompat("Speaker", CallEndpointCompat.TYPE_SPEAKER, ParcelUuid.fromString(SPEAKER_ID))
    private val buds = CallEndpointCompat("Buds", CallEndpointCompat.TYPE_BLUETOOTH, ParcelUuid.fromString(BUDS_ID))

    private fun TestScope.call(heard: Heard): TelecomCall {
        var clock = 0L
        return TelecomCall(system, backgroundScope, logged::add, heard, now = { clock.also { clock += 40 } })
            .also {
                it.start("Nordlicht · Lobby")
                runCurrent()
            }
    }

    @Test
    fun theCallsEndpointsAreTheSessionsOutputsAndAChoiceGoesToTheCall() = runTest {
        val heard = Heard(sessionGoesOn = true)
        val call = call(heard)
        system.calls.single().available.emit(listOf(speaker, buds))
        system.calls.single().current.emit(buds)
        runCurrent()

        assertEquals(
            OutputState(
                available = listOf(
                    AudioOutput(SPEAKER_ID, OutputKind.Speaker, "Speaker"),
                    AudioOutput(BUDS_ID, OutputKind.Bluetooth, "Buds"),
                ),
                current = AudioOutput(BUDS_ID, OutputKind.Bluetooth, "Buds"),
            ),
            heard.outputs,
        )

        call.select(SPEAKER_ID)
        call.select("unknown")
        runCurrent()
        assertEquals(listOf(speaker), system.calls.single().requested)
    }

    @Test
    fun aCallTheSystemEndsWhileTheSessionGoesOnIsAddedAgainAtOnce() = runTest {
        val heard = Heard(sessionGoesOn = true)
        call(heard)

        system.calls.single().systemEnds()
        runCurrent()

        assertEquals(listOf("end requested"), heard.events)
        assertEquals("A second call replaced the first", 2, system.calls.size)
        assertTrue("How long it took is logged", logged.any { it.contains("added again after 40 ms") })
    }

    @Test
    fun whenTheSystemRefusesTheCallAgainTheSessionGoesOnWithoutOne() = runTest {
        val heard = Heard(sessionGoesOn = true)
        call(heard)

        system.refuse = true
        system.calls.single().systemEnds()
        runCurrent()

        assertEquals("Nothing asks to leave the server", listOf("end requested", "no call"), heard.events)
        assertEquals(1, system.calls.size)
    }

    @Test
    fun aCallTheSystemEndsForASessionThatIsLeftIsNotAddedAgain() = runTest {
        val heard = Heard(sessionGoesOn = false)
        call(heard)

        system.calls.single().systemEnds()
        runCurrent()

        assertEquals(listOf("end requested", "no call"), heard.events)
        assertEquals(1, system.calls.size)
    }

    @Test
    fun aRefusedCallLeavesTheSessionWithoutOne() = runTest {
        system.refuse = true
        val heard = Heard(sessionGoesOn = true)
        call(heard)

        assertEquals(listOf("no call"), heard.events)
    }

    @Test
    fun onlyAChangeOfTheSystemsMuteIsReportedNotHowTheCallStarts() = runTest {
        val heard = Heard(sessionGoesOn = true)
        call(heard)
        val muted = system.calls.single().muted

        muted.value = true
        runCurrent()
        muted.value = false
        runCurrent()

        assertEquals(listOf("muted true", "muted false"), heard.events)
    }

    @Test
    fun endingTheSessionEndsTheCallForGood() = runTest {
        val heard = Heard(sessionGoesOn = true)
        val call = call(heard)

        call.end()
        runCurrent()

        assertTrue(system.calls.single().over.isCompleted)
        assertEquals(1, system.calls.size)
    }

    private companion object {
        const val SPEAKER_ID = "00000000-0000-0000-0000-000000000001"
        const val BUDS_ID = "00000000-0000-0000-0000-000000000002"
    }
}
