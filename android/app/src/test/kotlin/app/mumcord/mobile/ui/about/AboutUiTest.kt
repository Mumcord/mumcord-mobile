// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.about

import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MorePage
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.update.AppInfo
import app.mumcord.mobile.update.AvailableUpdate
import app.mumcord.mobile.update.InMemoryUpdateStore
import app.mumcord.mobile.update.releaseJson
import app.mumcord.mobile.update.ReleaseVersion
import app.mumcord.mobile.update.UpdateChecker
import java.io.IOException
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** About Mumcord, its licences and the update notice, through the view model (FR-5.100, FR-5.101). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AboutUiTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private var notices: () -> String = { "Mumcord Mobile notices\n\n--------------------\nInstrument Sans\n--------------------\n\nOFL-1.1\n" }
    private val info = AppInfo("0.2.0", "0.8.0 (6d2ac79e)", debuggable = false)
    private val newer = AvailableUpdate(ReleaseVersion(0, 3, 0), hasApk = true)

    private object DoneSetup : SetupStore {
        override val firstStartDone = true
        override fun markFirstStartDone() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.app(): AppViewModel {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupVoiceServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        repository.loadServers()
        val checker = UpdateChecker(info, { releaseJson() }, InMemoryUpdateStore(), clock = { 0 }, io = dispatcher)
        val viewModel = AppViewModel(
            repository,
            DoneSetup,
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            appInfo = info,
            updates = checker,
            notices = NoticesSource { notices() },
            io = dispatcher,
        )
        backgroundScope.launch { viewModel.moreState.collect() }
        runCurrent()
        return viewModel
    }

    @Test
    fun theNoticeFollowsTheSwitchAndStaysClosed() = runTest(dispatcher) {
        val app = app()
        assertNull(app.updateNotice.value)
        app.checkForUpdate()
        runCurrent()
        assertEquals(newer, app.updateNotice.value)

        app.more.changeMobile { it.copy(updateCheck = false) }
        runCurrent()
        assertNull(app.updateNotice.value)
        app.more.changeMobile { it.copy(updateCheck = true) }
        runCurrent()
        assertEquals(newer, app.updateNotice.value)

        app.more.about.dismissUpdate()
        runCurrent()
        assertNull(app.updateNotice.value)
    }

    @Test
    fun aboutNamesBothVersionsAndTheSourceOfThisVersion() = runTest(dispatcher) {
        val app = app()
        app.connect(1)
        runCurrent()
        assertEquals("0.2.0", app.moreState.value?.appVersion)
        app.more.openAbout()
        runCurrent()
        val about = checkNotNull(app.moreState.value?.about)
        assertEquals("0.2.0", about.appVersion)
        assertEquals("0.8.0 (6d2ac79e)", about.engineVersion)
        assertEquals("https://github.com/Mumcord/mumcord-mobile/tree/v0.2.0", about.sourceUrl)
    }

    @Test
    fun theLicencesAreReadOnceAndCanBeRetried() = runTest(dispatcher) {
        notices = { throw IOException("no asset") }
        val app = app()
        app.connect(1)
        runCurrent()
        app.more.openAbout()
        app.more.openLicences()
        runCurrent()
        assertEquals(Licences.Failed, app.moreState.value?.licences)

        notices = { "Mumcord Mobile notices\n\n--------------------\nInstrument Sans\n--------------------\n\nOFL-1.1\n" }
        app.more.about.loadLicences()
        runCurrent()
        assertEquals(
            Licences.Loaded(
                listOf(
                    NoticeBlock("Mumcord Mobile notices"),
                    NoticeBlock("Instrument Sans", heading = true),
                    NoticeBlock("OFL-1.1"),
                ),
            ),
            app.moreState.value?.licences,
        )
        app.more.back()
        runCurrent()
        assertEquals(MorePage.About, app.moreState.value?.page)
        app.more.back()
        runCurrent()
        assertEquals(MorePage.Main, app.moreState.value?.page)
    }
}
