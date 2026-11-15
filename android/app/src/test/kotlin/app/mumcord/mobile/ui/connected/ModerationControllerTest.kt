// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BanResult
import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.MOCKUP_AFK
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.setup.SetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Move, server mute, kick and ban from the person sheet, against the fake engine as server admin. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Connected as server admin, with Jonas's sheet open. */
    private fun TestScope.jonasSheet(configure: (FakeEngine) -> Unit = {}): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer().granting { it.allRights() }, servers = listOf(savedServer(1, "Nordlicht")))
                    .also(configure)
                    .also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        return AppViewModel(repository, DoneSetup, FakeLocalNetworkGateway(), FakeMicrophoneAccess()).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()
            viewModel.connect(1)
            runCurrent()
            viewModel.connected.openPerson(MOCKUP_JONAS)
            runCurrent()
        }
    }

    private val AppViewModel.sheet get() = uiState.value.connected?.sheet
    private val AppViewModel.moderation get() = connected.moderation

    @Test
    fun movingClosesTheSheetsAndMovesThePerson() = runTest(dispatcher) {
        val viewModel = jonasSheet()
        assertTrue((viewModel.sheet as ConnectedSheet.PersonSheet).moderation.move)
        viewModel.moderation.openMove()
        runCurrent()
        assertTrue((viewModel.sheet as ConnectedSheet.Moderation).sheet is ModerationSheet.Move)

        viewModel.moderation.move(MOCKUP_AFK)
        runCurrent()
        assertNull(viewModel.sheet)
        assertEquals(listOf("move $MOCKUP_JONAS $MOCKUP_AFK"), engine.moderationCalls)
    }

    @Test
    fun goingBackFromAStepShowsThePersonSheetAgain() = runTest(dispatcher) {
        val viewModel = jonasSheet()
        viewModel.moderation.openKick()
        runCurrent()
        viewModel.connected.dismissSheet()
        runCurrent()
        assertTrue(viewModel.sheet is ConnectedSheet.PersonSheet)
    }

    @Test
    fun serverMuteTogglesAndTheEntryFollowsTheServer() = runTest(dispatcher) {
        val viewModel = jonasSheet()
        viewModel.moderation.toggleServerMute()
        runCurrent()
        assertTrue((viewModel.sheet as ConnectedSheet.PersonSheet).moderation.serverMuted)
        viewModel.moderation.toggleServerMute()
        runCurrent()
        assertFalse((viewModel.sheet as ConnectedSheet.PersonSheet).moderation.serverMuted)
        assertEquals(listOf("mute $MOCKUP_JONAS true", "mute $MOCKUP_JONAS false"), engine.moderationCalls)
    }

    @Test
    fun aMuteThatFailsClosesTheSheetSoTheNoticeBehindItShows() = runTest(dispatcher) {
        val viewModel = jonasSheet { it.moderationFailure = BridgeException.Internal("broken") }
        viewModel.moderation.toggleServerMute()
        runCurrent()
        assertNull(viewModel.sheet)
        assertTrue(viewModel.uiState.value.connected?.denied != null)
    }

    @Test
    fun aKickSendsTheTrimmedReasonAndClosesTheSheets() = runTest(dispatcher) {
        val viewModel = jonasSheet()
        viewModel.moderation.openKick()
        viewModel.moderation.changeKickReason("  cool down ")
        viewModel.moderation.confirmKick()
        runCurrent()
        assertNull(viewModel.sheet)
        assertEquals(listOf<String?>("cool down"), engine.kickReasons)
    }

    @Test
    fun aRefusedKickStaysInTheSheetWithTheServersText() = runTest(dispatcher) {
        val viewModel = jonasSheet { it.moderationFailure = BridgeException.PermissionDenied("Not here") }
        viewModel.moderation.openKick()
        viewModel.moderation.confirmKick()
        runCurrent()
        val kick = (viewModel.sheet as ConnectedSheet.Moderation).sheet as ModerationSheet.Kick
        assertEquals(ModerationFailure.Refused("Not here"), kick.failure)
        assertFalse(kick.busy)
    }

    @Test
    fun theBanSheetAsksTheServerFirstThenBansWithTheChosenLength() = runTest(dispatcher) {
        val viewModel = jonasSheet()
        viewModel.moderation.openBan()
        // Asked for, not yet answered.
        assertNull((viewModel.connected.local.value.moderation as ModerationLocal.Ban).target)
        runCurrent()
        val ban = (viewModel.sheet as ConnectedSheet.Moderation).sheet as ModerationSheet.Ban
        assertEquals(engine.banTargetAnswer, ban.target)

        viewModel.moderation.selectBanDuration(604_800u)
        viewModel.moderation.changeBanReason("spam")
        viewModel.moderation.setBanRemoveNow(false)
        viewModel.moderation.confirmBan()
        runCurrent()
        assertNull(viewModel.sheet)
        val sent = engine.banInputs.single()
        assertEquals(604_800u, sent.durationSecs)
        assertEquals("spam", sent.reason)
        assertTrue(sent.byCertificate && sent.byAddress && !sent.removeNow && !sent.quick && !sent.force)
    }

    @Test
    fun aConflictAsksBeforeSavingAnyway() = runTest(dispatcher) {
        val viewModel = jonasSheet { it.banResults += BanResult.Conflict }
        viewModel.moderation.openBan()
        runCurrent()
        viewModel.moderation.confirmBan()
        runCurrent()
        assertTrue(((viewModel.sheet as ConnectedSheet.Moderation).sheet as ModerationSheet.Ban).conflict)

        viewModel.moderation.banAnyway()
        runCurrent()
        assertNull(viewModel.sheet)
        assertEquals(listOf(false, true), engine.banInputs.map { it.force })
    }

    @Test
    fun aRefusedBanTargetLeavesOnlyTheRefusal() = runTest(dispatcher) {
        val viewModel = jonasSheet { it.moderationFailure = BridgeException.PermissionDenied("") }
        viewModel.moderation.openBan()
        runCurrent()
        val ban = (viewModel.sheet as ConnectedSheet.Moderation).sheet as ModerationSheet.Ban
        assertEquals(ModerationFailure.Refused(""), ban.failure)
        assertNull(ban.target)
        assertFalse(ban.loading)
    }

    @Test
    fun kickOnlyInsteadKeepsTheReason() = runTest(dispatcher) {
        val viewModel = jonasSheet()
        viewModel.moderation.openBan()
        runCurrent()
        viewModel.moderation.changeBanReason("calm down")
        viewModel.moderation.kickInstead()
        runCurrent()
        val kick = (viewModel.sheet as ConnectedSheet.Moderation).sheet as ModerationSheet.Kick
        assertEquals("calm down", kick.reason)
    }
}
