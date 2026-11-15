// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.Identity
import app.mumcord.mobile.bridge.ImportReport
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.SkippedServer
import app.mumcord.mobile.bridge.TransferException
import app.mumcord.mobile.bridge.TransferIdentityPreview
import app.mumcord.mobile.bridge.TransferPreview
import app.mumcord.mobile.bridge.TransferServerPreview
import app.mumcord.mobile.bridge.TransferSkipReason
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.FAKE_IDENTITY
import app.mumcord.mobile.session.FakeEngine
import app.mumcord.mobile.session.FakeTransferScan
import app.mumcord.mobile.session.IMPORTED_NAME
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.channel
import app.mumcord.mobile.session.person
import app.mumcord.mobile.session.savedServer
import app.mumcord.mobile.ui.camera.CameraAnswer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class IdentitiesControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = ServerState(
        selfSession = 10u,
        channels = listOf(channel(0u, "Root")),
        people = listOf(person(10u, "Me", channelId = 0u)),
    )
    private lateinit var engine: FakeEngine
    private lateinit var repository: SessionRepository
    private val camera = FakeCameraGateway()

    // Made for each run: no code and no QR text of a test is ever a fixed value.
    private val code = Random.nextInt(0, 100_000_000).toString().padStart(8, '0')
    private val wrongCode = code.map { if (it == '9') '0' else it + 1 }.joinToString("")
    private val parts = List(7) { "part-$it-${Random.nextLong()}" }
    private val preview = TransferPreview(
        identities = listOf(TransferIdentityPreview("Desktop", "ab".repeat(20), isDefault = true)),
        servers = listOf(
            TransferServerPreview("Nordlicht", "nordlicht.example", 64738u, "Lea"),
            TransferServerPreview("Tidepool", "voice.tidepool.example", 64738u, "Lea"),
        ),
    )
    private var imports = 0
    private val transfer = FakeTransferScan(parts, code, preview) {
        imports++
        ImportReport(
            identitiesImported = listOf(FAKE_IDENTITY.copy(id = 7, label = "Desktop")),
            identitiesSkipped = emptyList(),
            serversImported = listOf(savedServer(8, "Nordlicht")),
            serversSkipped = listOf(SkippedServer("Tidepool", "voice.tidepool.example", TransferSkipReason.DUPLICATE)),
        )
    }

    private fun TestScope.controller(identities: List<Identity> = listOf(FAKE_IDENTITY)): IdentitiesController {
        repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, server, identities = identities).also {
                    it.transfer = transfer
                    engine = it
                }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        val controller = IdentitiesController(repository, backgroundScope, camera)
        backgroundScope.launch { repository.loadServers() }
        runCurrent()
        return controller
    }

    private val IdentitiesController.sheet get() = local.value.sheet
    private val IdentitiesController.scan get() = checkNotNull(local.value.scan)
    private val labels get() = repository.state.value.identities.orEmpty().map { it.label }

    /** The codes in any order, twice over: a camera sees each of them again and again. */
    private fun TestScope.scanAll(controller: IdentitiesController) {
        controller.openScan()
        runCurrent()
        val order = parts.shuffled()
        (order + order).forEach(controller::onQrText)
    }

    // ----- create, import, export, delete -----

    @Test
    fun aNewIdentityIsCreatedWithTheTypedNameAndTheFirstOneIsTheDefault() = runTest(dispatcher) {
        val controller = controller(identities = emptyList())
        controller.openCreate()
        controller.create()
        runCurrent()
        assertTrue("nothing is created without a name", labels.isEmpty())

        controller.changeCreateName("  Lea  ")
        controller.create()
        assertTrue((controller.sheet as IdentitySheetLocal.Create).saving)
        runCurrent()

        assertNull(controller.sheet)
        assertEquals(listOf("Lea"), labels)
        assertTrue(repository.state.value.identities.orEmpty().single().isDefault)
    }

    @Test
    fun aNameIsNeverLongerThanACertificateTakes() = runTest(dispatcher) {
        val controller = controller()
        controller.openCreate()
        controller.changeCreateName("x".repeat(100))
        assertEquals(MAX_IDENTITY_NAME, (controller.sheet as IdentitySheetLocal.Create).name.length)
    }

    @Test
    fun anIdentityFileIsImportedWithItsPassword() = runTest(dispatcher) {
        val controller = controller()
        engine.filePassword = "file-${Random.nextInt()}"
        controller.importFile(Random.nextBytes(16))
        assertEquals(IdentitySheetLocal.Import(), controller.sheet)

        controller.changeImportPassword("not it")
        controller.confirmImport()
        runCurrent()
        val refused = controller.sheet as IdentitySheetLocal.Import
        assertEquals(ImportProblem.WrongPasswordOrFile, refused.problem)
        assertEquals("the wrong password is not kept", "", refused.password)

        controller.changeImportPassword(engine.filePassword)
        controller.confirmImport()
        runCurrent()
        assertNull(controller.sheet)
        assertEquals(listOf("Lea", IMPORTED_NAME), labels)
    }

    @Test
    fun theSameIdentityIsNotImportedTwice() = runTest(dispatcher) {
        val controller = controller()
        val file = Random.nextBytes(16)
        controller.importFile(file)
        controller.confirmImport()
        runCurrent()
        controller.importFile(file)
        controller.confirmImport()
        runCurrent()
        assertEquals(ImportProblem.Duplicate, (controller.sheet as IdentitySheetLocal.Import).problem)
        assertEquals(2, labels.size)
    }

    @Test
    fun aFileThatCannotBeReadIsSaidOnTheScreenAndNothingIsAskedFor() = runTest(dispatcher) {
        val controller = controller()
        controller.importFile(null)
        assertNull(controller.sheet)
        assertTrue(controller.local.value.unreadableFile)
        controller.openCreate()
        assertFalse(controller.local.value.unreadableFile)
    }

    @Test
    fun aBackupIsMadeWithThePasswordTypedTwiceAndHandedToTheShareSheetOnce() = runTest(dispatcher) {
        val controller = controller()
        val password = "backup-${Random.nextInt()}"
        controller.openExport(1)
        controller.changeExportPassword(password)
        controller.export()
        runCurrent()
        assertNull("no backup before the password was repeated", controller.local.value.exported)

        controller.changeExportRepeat(password)
        controller.export()
        runCurrent()
        assertNull(controller.sheet)
        val exported = checkNotNull(controller.local.value.exported)
        assertEquals("mumcord-identity-Lea.p12", exported.fileName)
        assertTrue(exported.bytes.isNotEmpty())
        controller.exportShared()
        assertNull(controller.local.value.exported)
    }

    @Test
    fun aFailedBackupSaysSoAndForgetsThePasswords() = runTest(dispatcher) {
        val controller = controller()
        engine.exportError = BridgeException.SecureStorageUnavailable("locked")
        controller.openExport(1)
        controller.changeExportPassword("a")
        controller.changeExportRepeat("a")
        controller.export()
        runCurrent()
        assertEquals(IdentitySheetLocal.Export(1, failed = true), controller.sheet)
    }

    @Test
    fun anotherIdentityBecomesTheDefault() = runTest(dispatcher) {
        val controller = controller()
        controller.openCreate()
        controller.changeCreateName("Work")
        controller.create()
        runCurrent()
        controller.setDefault(2)
        runCurrent()
        assertEquals(listOf(false, true), engine.identities.map { it.isDefault })
    }

    @Test
    fun deletingNeedsTheTypedLabelAndTheLastIdentityStays() = runTest(dispatcher) {
        val controller = controller()
        controller.openDelete(1)
        controller.changeDeleteConfirmation("Lea")
        controller.delete()
        runCurrent()
        assertEquals("the only identity is never deleted", listOf("Lea"), labels)

        controller.closeSheet()
        controller.openCreate()
        controller.changeCreateName("Work")
        controller.create()
        runCurrent()
        controller.openDelete(2)
        controller.changeDeleteConfirmation("work")
        controller.delete()
        runCurrent()
        assertEquals(listOf("Lea", "Work"), labels)

        controller.changeDeleteConfirmation("Work")
        controller.delete()
        runCurrent()
        assertNull(controller.sheet)
        assertEquals(listOf("Lea"), labels)
    }

    @Test
    fun backUpFirstReturnsToTheDeleteWarning() = runTest(dispatcher) {
        val controller = controller()
        controller.openDelete(1)
        controller.openExport(1)
        assertTrue((controller.sheet as IdentitySheetLocal.Export).fromDelete)
        controller.closeSheet()
        assertEquals(IdentitySheetLocal.Delete(1), controller.sheet)

        controller.openExport(1)
        controller.changeExportPassword("a")
        controller.changeExportRepeat("a")
        controller.export()
        runCurrent()
        assertEquals(IdentitySheetLocal.Delete(1), controller.sheet)
        assertNotNull(controller.local.value.exported)
    }

    // ----- scan -----

    @Test
    fun theCodesAreCollectedInAnyOrderAndCountedOnce() = runTest(dispatcher) {
        val controller = controller()
        controller.openScan()
        runCurrent()
        assertEquals(ScanStep.Scanning(0, 0), controller.scan.step)

        controller.onQrText(parts[4])
        controller.onQrText(parts[4])
        controller.onQrText(parts[0])
        assertEquals(ScanStep.Scanning(2, 7), controller.scan.step)

        (parts - parts[4] - parts[0]).reversed().forEach(controller::onQrText)
        assertEquals(ScanStep.Code(digits = 8, attemptsLeft = 3), controller.scan.step)
    }

    @Test
    fun aForeignQrCodeIsSaidAndWhatWasCollectedStays() = runTest(dispatcher) {
        val controller = controller()
        controller.openScan()
        runCurrent()
        controller.onQrText(parts[0])
        controller.onQrText("https://example.org/not-a-transfer")
        assertEquals(ScanState(ScanStep.Scanning(1, 7), note = ScanNote.NotATransfer), controller.scan)
        controller.onQrText("x".repeat(MAX_QR_CHARS + 1))
        assertEquals(ScanNote.NotATransfer, controller.scan.note)
        controller.onQrText(parts[1])
        assertEquals(ScanState(ScanStep.Scanning(2, 7)), controller.scan)
    }

    @Test
    fun theCodeIsCheckedAtItsLastDigitAndIsGoneFromTheStateAfterwards() = runTest(dispatcher) {
        val controller = controller()
        scanAll(controller)
        controller.typeCode(code.take(7))
        assertEquals(code.take(7), (controller.scan.step as ScanStep.Code).typed)
        assertEquals(0, transfer.checkedCodes.get())

        controller.typeCode(code)
        assertEquals(ScanStep.Checking(8, 3), controller.scan.step)
        runCurrent()

        val shown = controller.scan.step as ScanStep.Preview
        assertEquals(listOf("Desktop"), shown.identities)
        assertEquals(listOf("Nordlicht", "Tidepool"), shown.servers)
        assertFalse("the code is nowhere in the state", controller.local.value.toString().contains(code))
        assertEquals("nothing is imported before the user says so", 0, imports)
    }

    @Test
    fun aWrongCodeIsRefusedThreeTimesAndThenTheScanStartsOver() = runTest(dispatcher) {
        val controller = controller()
        scanAll(controller)

        controller.typeCode(wrongCode)
        runCurrent()
        assertEquals(ScanStep.Code(digits = 8, attemptsLeft = 2, wrong = true), controller.scan.step)
        controller.typeCode(wrongCode)
        runCurrent()
        assertEquals(ScanStep.Code(digits = 8, attemptsLeft = 1, wrong = true), controller.scan.step)
        controller.typeCode(wrongCode)
        runCurrent()

        assertEquals(ScanState(ScanStep.Scanning(), note = ScanNote.RescanRequired), controller.scan)
        // The same codes can be scanned again, and the right code then opens them.
        parts.forEach(controller::onQrText)
        controller.typeCode(code)
        runCurrent()
        assertTrue(controller.scan.step is ScanStep.Preview)
    }

    @Test
    fun theWrongMarkGoesAwayWhenTheUserTypesAgain() = runTest(dispatcher) {
        val controller = controller()
        scanAll(controller)
        controller.typeCode(wrongCode)
        runCurrent()
        controller.typeCode("1")
        assertFalse((controller.scan.step as ScanStep.Code).wrong)
    }

    @Test
    fun anExpiredTransferStartsOverWithAClearNote() = runTest(dispatcher) {
        val controller = controller()
        controller.openScan()
        runCurrent()
        controller.onQrText(parts[0])
        transfer.failNext = TransferException.Expired()
        controller.onQrText(parts[1])
        assertEquals(ScanState(ScanStep.Scanning(), note = ScanNote.Expired), controller.scan)
        // The camera still sees the old code many times a second: the note stays calm.
        controller.onQrText(parts[1])
        assertEquals(ScanNote.Expired, controller.scan.note)

        scanAll(controller)
        transfer.failNext = TransferException.Expired()
        controller.typeCode(code)
        runCurrent()
        assertEquals(ScanState(ScanStep.Scanning(), note = ScanNote.Expired), controller.scan)
    }

    @Test
    fun aCodeOfANewTransferReplacesTheOldOne() = runTest(dispatcher) {
        val controller = controller()
        controller.openScan()
        runCurrent()
        controller.onQrText(parts[0])
        controller.onQrText(parts[1])
        transfer.failNext = TransferException.OtherTransfer()
        controller.onQrText(parts[2])
        assertEquals(1, transfer.resets.get())
        assertEquals(ScanState(ScanStep.Scanning(1, 7)), controller.scan)
    }

    @Test
    fun theImportRunsOnlyAfterTheUserConfirmsAndSaysWhatWasSkipped() = runTest(dispatcher) {
        val controller = controller()
        scanAll(controller)
        controller.typeCode(code)
        runCurrent()

        controller.importTransfer()
        assertTrue((controller.scan.step as ScanStep.Preview).importing)
        controller.importTransfer()
        runCurrent()

        assertEquals(1, imports)
        assertEquals(
            ScanStep.Done(identitiesImported = 1, serversImported = 1, skipped = listOf(SkippedEntry("Tidepool", alreadyThere = true))),
            controller.scan.step,
        )
    }

    @Test
    fun closingTheScanForgetsItInTheBridge() = runTest(dispatcher) {
        val controller = controller()
        scanAll(controller)
        controller.closeScan()
        runCurrent()
        assertNull(controller.local.value.scan)
        assertEquals(1, transfer.resets.get())
        // A code the camera still delivers goes nowhere.
        controller.onQrText(parts[0])
        assertNull(controller.local.value.scan)
    }

    @Test
    fun anEngineWithoutATransferSaysThatScanningFailed() = runTest(dispatcher) {
        val controller = controller()
        engine.transfer = null
        controller.openScan()
        runCurrent()
        assertEquals(ScanNote.Failed, controller.scan.note)
    }

    // ----- camera permission -----

    @Test
    fun theCameraIsExplainedBeforeTheSystemAsksAndNotNowLeavesAClearState() = runTest(dispatcher) {
        camera.isGranted = false
        val controller = controller()
        controller.openScan()
        runCurrent()
        assertEquals(CameraNotice.Explain, controller.scan.camera)
        // Nothing is read while the camera may not be used.
        controller.onQrText(parts[0])
        assertEquals(ScanStep.Scanning(), controller.scan.step)

        controller.cameraNotNow()
        assertEquals(CameraNotice.Denied, controller.scan.camera)
    }

    @Test
    fun aGrantedCameraStartsTheScan() = runTest(dispatcher) {
        camera.isGranted = false
        val controller = controller()
        controller.openScan()
        runCurrent()
        camera.isGranted = true
        controller.answerCamera(CameraAnswer(granted = true, refusedBefore = false, refusedAfter = false))
        assertNull(controller.scan.camera)
        controller.onQrText(parts[0])
        assertEquals(ScanStep.Scanning(1, 7), controller.scan.step)
    }

    @Test
    fun aRefusalIsRememberedAndTheSecondOnePointsToTheSystemSettings() = runTest(dispatcher) {
        camera.isGranted = false
        val controller = controller()
        controller.openScan()
        runCurrent()
        controller.answerCamera(CameraAnswer(granted = false, refusedBefore = false, refusedAfter = true))
        assertEquals(CameraNotice.Denied, controller.scan.camera)
        controller.answerCamera(CameraAnswer(granted = false, refusedBefore = true, refusedAfter = false))
        assertEquals(CameraNotice.Blocked, controller.scan.camera)

        // The next scan does not explain again: the system would not ask anyway.
        controller.closeScan()
        controller.openScan()
        runCurrent()
        assertEquals(CameraNotice.Blocked, controller.scan.camera)

        // Allowed in the system settings: back in the app the scan runs.
        camera.isGranted = true
        controller.recheckCamera()
        assertNull(controller.scan.camera)
        assertFalse(camera.promptBlocked)
    }

    @Test
    fun aPhoneWhoseCameraDoesNotStartSaysSo() = runTest(dispatcher) {
        val controller = controller()
        controller.openScan()
        runCurrent()
        controller.cameraFailed()
        assertEquals(ScanNote.NoCamera, controller.scan.note)
    }

    @Test
    fun aFailedImportKeepsThePreviewForAnotherTry() = runTest(dispatcher) {
        val failing = FakeTransferScan(parts, code, preview) { throw BridgeException.SecureStorageUnavailable("locked") }
        val controller = controller()
        engine.transfer = failing
        scanAll(controller)
        controller.typeCode(code)
        runCurrent()
        controller.importTransfer()
        runCurrent()
        val step = controller.scan.step as ScanStep.Preview
        assertFalse(step.importing)
        assertEquals(ConnectionError.SecureStorageUnavailable, step.error)
    }
}
