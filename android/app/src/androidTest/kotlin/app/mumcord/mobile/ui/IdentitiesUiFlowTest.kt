// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.bridge.ImportReport
import app.mumcord.mobile.bridge.SkippedServer
import app.mumcord.mobile.bridge.TransferIdentityPreview
import app.mumcord.mobile.bridge.TransferPreview
import app.mumcord.mobile.bridge.TransferServerPreview
import app.mumcord.mobile.bridge.TransferSkipReason
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.session.FAKE_IDENTITY
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.FakeTransferScan
import app.mumcord.mobile.session.IMPORTED_NAME
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.camera.CAMERA_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.camera.CAMERA_NOT_NOW_TEST_TAG
import app.mumcord.mobile.ui.camera.CameraAnswer
import app.mumcord.mobile.ui.connect.FakeLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkSystem
import app.mumcord.mobile.ui.connected.FakeMicrophoneAccess
import app.mumcord.mobile.ui.connected.MORE_IDENTITIES_TEST_TAG
import app.mumcord.mobile.ui.connected.MicrophoneSystem
import app.mumcord.mobile.ui.connected.VOICE_BAR_TEST_TAG
import app.mumcord.mobile.ui.identities.ExportedIdentity
import app.mumcord.mobile.ui.identities.FakeCameraGateway
import app.mumcord.mobile.ui.identities.IDENTITY_CARD_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_CREATE_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_CREATE_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_DELETE_BACKUP_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_DETAILS_CLOSE_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_DELETE_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_DELETE_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_EXPORT_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_EXPORT_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_FILE_NOTICE_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_IMPORT_CONFIRM_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_IMPORT_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_SCAN_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_SET_DEFAULT_TEST_TAG
import app.mumcord.mobile.ui.identities.IdentitySystem
import app.mumcord.mobile.ui.identities.SCAN_CAMERA_ACTION_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_CODE_STATUS_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_CODE_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_DONE_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_IMPORT_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_NOTE_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_SCREEN_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_SUMMARY_TEST_TAG
import app.mumcord.mobile.ui.identities.ScanStep
import app.mumcord.mobile.ui.servers.SERVER_SCAN_TEST_TAG
import app.mumcord.mobile.ui.setup.SetupStore
import app.mumcord.mobile.ui.setup.TALK_MODE_CONTINUE_TEST_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The identity screens and "Scan from desktop app" on a device against a fake engine. The
 * camera is replaced by a source the test feeds with the texts of QR codes; the codes, the
 * typed code and the passwords are made for each run.
 */
class IdentitiesUiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resizeForTheKeyboard() = compose.resizeForTheKeyboardLikeTheApp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: FakeEngine
    private lateinit var viewModel: AppViewModel
    private val camera = FakeCameraGateway()

    /** What the user answers when the system asks for the camera. */
    private var grantCamera = true
    private var cameraAsked = 0
    private var cameraSettingsOpened = 0
    private val shared = CopyOnWriteArrayList<ExportedIdentity>()

    /** The file the system's picker hands over; null is a file that cannot be read. */
    private var pickedFile: ByteArray? = Random.nextBytes(32)
    private val qrCodes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private val code = Random.nextInt(0, 100_000_000).toString().padStart(8, '0')
    private val wrongCode = code.map { if (it == '9') '0' else it + 1 }.joinToString("")
    private val parts = List(7) { "part-$it-${Random.nextLong()}" }
    private val transfer = FakeTransferScan(
        parts = parts,
        code = code,
        preview = TransferPreview(
            identities = listOf(TransferIdentityPreview("Desktop", "ab".repeat(20), isDefault = true)),
            servers = listOf(
                TransferServerPreview("Tidepool", "voice.tidepool.example", 64738u, "Lea"),
                TransferServerPreview("Nordlicht", "nordlicht.example", 64738u, "Lea"),
            ),
        ),
    ) {
        val imported = runBlocking { engine.createIdentity("Desktop", null) }
        ImportReport(
            identitiesImported = listOf(imported),
            identitiesSkipped = emptyList(),
            serversImported = listOf(savedServer(7, "Tidepool")),
            serversSkipped = listOf(SkippedServer("Nordlicht", "nordlicht.example", TransferSkipReason.DUPLICATE)),
        )
    }

    private class Setup(override var firstStartDone: Boolean) : SetupStore {
        override fun markFirstStartDone() {
            firstStartDone = true
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun start(
        firstStart: Boolean = false,
        identities: List<Identity> = listOf(FAKE_IDENTITY),
        servers: Boolean = true,
        fontScale: Float = 1f,
    ) {
        val repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(
                    listener,
                    mockupServer(),
                    servers = if (servers) listOf(savedServer(1, "Nordlicht")) else emptyList(),
                    identities = identities,
                ).also {
                    it.transfer = transfer
                    engine = it
                }
            },
            scope = scope,
            engineDispatcher = Dispatchers.IO,
            log = {},
        )
        viewModel = AppViewModel(
            repository,
            Setup(firstStartDone = !firstStart),
            FakeLocalNetworkGateway(),
            FakeMicrophoneAccess(),
            camera = camera,
        )
        val system = IdentitySystem(
            pickFile = { viewModel.identities.importFile(pickedFile) },
            share = { shared += it },
            askCamera = {
                cameraAsked++
                camera.isGranted = grantCamera
                viewModel.identities.answerCamera(CameraAnswer(grantCamera, refusedBefore = false, refusedAfter = !grantCamera))
            },
            openCameraSettings = { cameraSettingsOpened++ },
            viewfinder = { LaunchedEffect(Unit) { qrCodes.collect(viewModel.identities::onQrText) } },
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MumcordTheme {
                    AppRoute(
                        viewModel,
                        LocalNetworkSystem(ask = {}, openSettings = {}),
                        MicrophoneSystem(ask = {}, openSettings = {}),
                        identitySystem = system,
                    )
                }
            }
        }
    }

    private fun openIdentitiesFromMore() {
        compose.onNodeWithContentDescription("Connect to Nordlicht").performClick()
        await { shown(VOICE_BAR_TEST_TAG) }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag(MORE_IDENTITIES_TEST_TAG).performClick()
        await { shown(IDENTITY_CARD_TEST_TAG) }
    }

    private fun scan(codes: List<String>) = codes.forEach { text ->
        assertTrue("the scanner takes the code", qrCodes.tryEmit(text))
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun text(text: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** A text field's error, which TalkBack reads with the field rather than as text of its own. */
    private fun fieldError(message: String) =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
            .fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) =
        compose.onAllNodes(hasContentDescription(description, substring = true))
            .fetchSemanticsNodes().isNotEmpty()

    private fun await(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MILLIS, condition)

    private fun labels() = engine.identities.map { it.label }

    // ----- first start -----

    @Test
    fun aFreshInstallCreatesAnIdentityBeforeTheTalkModeIsAskedFor() {
        start(firstStart = true, identities = emptyList())
        await { shown(IDENTITY_CREATE_TEST_TAG) }
        assertTrue("the talk mode comes after the identity", !shown(TALK_MODE_CONTINUE_TEST_TAG))

        compose.onNodeWithTag(IDENTITY_CREATE_TEST_TAG).performClick()
        compose.onNodeWithTag(IDENTITY_CREATE_CONFIRM_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Name on servers").performTextInput("Lea")
        compose.onNodeWithTag(IDENTITY_CREATE_CONFIRM_TEST_TAG).performClick()

        await { shown(TALK_MODE_CONTINUE_TEST_TAG) }
        assertEquals(listOf("Lea"), labels())
    }

    @Test
    fun aFreshInstallReceivesItsIdentityFromTheDesktopByScanningAndTypingTheCode() {
        camera.isGranted = false
        start(firstStart = true, identities = emptyList())
        await { shown(IDENTITY_SCAN_TEST_TAG) }
        compose.onNodeWithTag(IDENTITY_SCAN_TEST_TAG).performClick()

        // The camera is explained first; then the system asks and the user allows it.
        await { shown(CAMERA_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(CAMERA_CONTINUE_TEST_TAG).performClick()
        assertEquals(1, cameraAsked)
        await { described("Waiting for the first code") }

        // The codes come in any order, some of them more than once, and a foreign one between.
        scan(listOf(parts[5], parts[5], parts[0], parts[2]))
        await { described("Code 3 of 7") }
        scan(listOf("https://example.org/something-else"))
        await { shown(SCAN_NOTE_TEST_TAG) }
        assertTrue("what was collected stays", described("Code 3 of 7"))
        scan(parts.reversed())

        // After the last code the code of the desktop is typed: eight digits.
        await { shown(SCAN_CODE_TEST_TAG) }
        assertTrue(described("Code shown on desktop, 8 digits"))
        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
        await { text("Wrong code. 2 tries left.") }
        assertEquals("nothing is imported after a wrong code", emptyList<String>(), labels())

        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(code)
        await { shown(SCAN_SUMMARY_TEST_TAG) }
        assertTrue(text("1 identity, 2 servers"))
        compose.onNodeWithText("Desktop").assertIsDisplayed()
        compose.onNodeWithText("Tidepool").assertIsDisplayed()
        compose.onNodeWithText("Server passwords are not transferred.").assertIsDisplayed()
        assertEquals("nothing is imported before the user says so", emptyList<String>(), labels())

        compose.onNodeWithTag(SCAN_IMPORT_TEST_TAG).performClick()
        await { shown(SCAN_DONE_TEST_TAG) }
        assertTrue(text("1 identity, 1 server"))
        assertTrue(text("Nordlicht · Already on this phone"))
        assertEquals(listOf("Desktop"), labels())

        compose.onNodeWithTag(SCAN_DONE_TEST_TAG).performClick()
        await { shown(TALK_MODE_CONTINUE_TEST_TAG) }
    }

    @Test
    fun threeWrongCodesMeanScanningAgain() {
        start(servers = false)
        await { shown(SERVER_SCAN_TEST_TAG) }
        compose.onNodeWithTag(SERVER_SCAN_TEST_TAG).performClick()
        await { shown(SCAN_SCREEN_TEST_TAG) }
        scan(parts)
        repeat(3) { attempt ->
            await { shown(SCAN_CODE_TEST_TAG) }
            compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
            if (attempt < 2) await { text("Wrong code. ${2 - attempt} tr") }
        }
        await { text("The code was wrong three times.") }
        assertTrue("the scan starts over", described("Waiting for the first code"))
        assertTrue("the code field is gone", !shown(SCAN_CODE_TEST_TAG))

        compose.onNodeWithContentDescription("Close").performClick()
        await { shown(SERVER_SCAN_TEST_TAG) }
    }

    @Test
    fun theSameCodeTypedAgainIsCheckedAgainWhenTheAnswerCameBeforeTheNextFrame() {
        start(servers = false)
        await { shown(SERVER_SCAN_TEST_TAG) }
        compose.onNodeWithTag(SERVER_SCAN_TEST_TAG).performClick()
        await { shown(SCAN_SCREEN_TEST_TAG) }
        scan(parts)
        await { shown(SCAN_CODE_TEST_TAG) }

        // No frame between typing and the answer, as on a busy phone: "Checking" is never drawn
        // and the code field stays on the screen the whole time.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
        awaitScanStep { (it as? ScanStep.Code)?.wrong == true }
        compose.mainClock.autoAdvance = true
        await { text("Wrong code. 2 tries left.") }

        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
        await { text("Wrong code. 1 try left.") }
        assertEquals("both codes reached the check", 2, transfer.checkedCodes.get())
    }

    /** Waits for the scan without drawing a frame, for when the test holds the clock. */
    private fun awaitScanStep(condition: (ScanStep?) -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition(viewModel.identities.local.value.scan?.step)) {
            check(System.currentTimeMillis() < end) { "the scan did not get there in time" }
            Thread.sleep(10)
        }
    }

    @Test
    fun withoutTheCameraTheScreenSaysSoAndOffersToAllowIt() {
        camera.isGranted = false
        grantCamera = false
        start(servers = false)
        await { shown(SERVER_SCAN_TEST_TAG) }
        compose.onNodeWithTag(SERVER_SCAN_TEST_TAG).performClick()
        await { shown(CAMERA_NOT_NOW_TEST_TAG) }
        compose.onNodeWithTag(CAMERA_NOT_NOW_TEST_TAG).performClick()

        await { shown(SCAN_CAMERA_ACTION_TEST_TAG) }
        compose.onNodeWithText("Mumcord may not use the camera, so it cannot read the codes.").assertIsDisplayed()
        assertEquals("not now does not ask the system", 0, cameraAsked)
        // Nothing is read without the camera.
        scan(listOf(parts[0]))

        compose.onNodeWithText("Allow the camera").performClick()
        assertEquals(1, cameraAsked)
        await { shown(SCAN_CAMERA_ACTION_TEST_TAG) }

        grantCamera = true
        compose.onNodeWithTag(SCAN_CAMERA_ACTION_TEST_TAG).performClick()
        await { described("Waiting for the first code") }
    }

    // ----- the identity list -----

    @Test
    fun moreLeadsToTheIdentitiesWhereABackupIsExportedWithAPasswordTypedTwice() {
        start()
        openIdentitiesFromMore()
        assertTrue(described("Lea (default), Used on 1 server"))

        compose.onNodeWithTag(IDENTITY_CARD_TEST_TAG).performClick()
        await { shown(IDENTITY_EXPORT_TEST_TAG) }
        compose.onNodeWithText("Fingerprint (SHA-1)").assertIsDisplayed()
        // The only identity cannot be deleted: there is no button, and the reason is given.
        assertTrue(!shown(IDENTITY_DELETE_TEST_TAG))
        compose.onNodeWithText("Mumcord needs at least one identity", substring = true).assertIsDisplayed()

        compose.onNodeWithTag(IDENTITY_EXPORT_TEST_TAG).performClick()
        val password = "backup-${Random.nextInt()}"
        compose.onNodeWithContentDescription("Password").performTextInput(password)
        compose.onNodeWithTag(IDENTITY_EXPORT_CONFIRM_TEST_TAG).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Repeat the password").performTextInput(password)
        compose.onNodeWithTag(IDENTITY_EXPORT_CONFIRM_TEST_TAG).performScrollTo().assertIsEnabled().performClick()

        await { shared.isNotEmpty() }
        assertEquals("mumcord-identity-Lea.p12", shared.single().fileName)

        compose.onNodeWithContentDescription("Back").performClick()
        await { shown(MORE_IDENTITIES_TEST_TAG) }
    }

    @Test
    fun anIdentityFileIsImportedWithItsPasswordThenMadeTheDefaultAndTheOldOneDeleted() {
        start()
        engine.filePassword = "file-${Random.nextInt()}"
        openIdentitiesFromMore()

        compose.onNodeWithTag(IDENTITY_IMPORT_TEST_TAG).performScrollTo().performClick()
        await { shown(IDENTITY_IMPORT_CONFIRM_TEST_TAG) }
        compose.onNodeWithContentDescription("Password of the file").performTextInput("not it")
        compose.onNodeWithTag(IDENTITY_IMPORT_CONFIRM_TEST_TAG).performClick()
        await { fieldError("The password is wrong, or this file does not hold an identity.") }

        compose.onNodeWithContentDescription("Password of the file").performTextInput(engine.filePassword)
        compose.onNodeWithTag(IDENTITY_IMPORT_CONFIRM_TEST_TAG).performClick()
        await { labels() == listOf("Lea", IMPORTED_NAME) }
        await { compose.onAllNodesWithTag(IDENTITY_CARD_TEST_TAG).fetchSemanticsNodes().size == 2 }

        // The imported one becomes the default.
        compose.onAllNodesWithTag(IDENTITY_CARD_TEST_TAG)[1].performClick()
        await { shown(IDENTITY_SET_DEFAULT_TEST_TAG) }
        compose.onNodeWithTag(IDENTITY_SET_DEFAULT_TEST_TAG).performClick()
        await { engine.identities.first { it.label == IMPORTED_NAME }.isDefault }
        compose.onNodeWithTag(IDENTITY_DETAILS_CLOSE_TEST_TAG).performScrollTo().performClick()

        // Deleting the old one needs its label typed; "Back up first" leads to the export and back.
        compose.onAllNodesWithTag(IDENTITY_CARD_TEST_TAG)[0].performClick()
        await { shown(IDENTITY_DELETE_TEST_TAG) }
        compose.onNodeWithTag(IDENTITY_DELETE_TEST_TAG).performScrollTo().performClick()
        await { shown(IDENTITY_DELETE_CONFIRM_TEST_TAG) }
        compose.onNodeWithTag(IDENTITY_DELETE_CONFIRM_TEST_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(IDENTITY_DELETE_BACKUP_TEST_TAG).performScrollTo().performClick()
        await { shown(IDENTITY_EXPORT_CONFIRM_TEST_TAG) }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        await { shown(IDENTITY_DELETE_CONFIRM_TEST_TAG) }

        compose.onNodeWithContentDescription("Type the identity label to confirm").performScrollTo().performTextInput("Lea")
        compose.onNodeWithTag(IDENTITY_DELETE_CONFIRM_TEST_TAG).performScrollTo().assertIsEnabled().performClick()
        await { labels() == listOf(IMPORTED_NAME) }
        await { compose.onAllNodesWithTag(IDENTITY_CARD_TEST_TAG).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun aFileThatCannotBeReadIsSaidOnTheScreen() {
        pickedFile = null
        start()
        openIdentitiesFromMore()
        compose.onNodeWithTag(IDENTITY_IMPORT_TEST_TAG).performScrollTo().performClick()
        await { shown(IDENTITY_FILE_NOTICE_TEST_TAG) }
        assertTrue("no password is asked for", !shown(IDENTITY_IMPORT_CONFIRM_TEST_TAG))
    }

    // ----- accessibility -----

    @Test
    fun atTwiceTheFontSizeEveryWayToAddAnIdentityIsStillReachableAndLargeEnoughToTap() {
        start(firstStart = true, identities = emptyList(), fontScale = 2f)
        await { shown(IDENTITY_CREATE_TEST_TAG) }
        for (tag in listOf(IDENTITY_SCAN_TEST_TAG, IDENTITY_IMPORT_TEST_TAG, IDENTITY_CREATE_TEST_TAG)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag(IDENTITY_SCAN_TEST_TAG).performScrollTo().performClick()
        await { shown(SCAN_SCREEN_TEST_TAG) }
        // The app bar's buttons have the design's touch size, as on every screen of the app.
        compose.onNodeWithContentDescription("Close").assertHeightIsAtLeast(MumcordSize.touchVisual)
        scan(parts)
        await { shown(SCAN_CODE_TEST_TAG) }
        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
        await { shown(SCAN_CODE_STATUS_TEST_TAG) }
        compose.onNodeWithTag(SCAN_CODE_STATUS_TEST_TAG).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
