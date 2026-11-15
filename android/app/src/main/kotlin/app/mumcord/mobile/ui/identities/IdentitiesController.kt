// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.TransferException
import app.mumcord.mobile.bridge.TransferScan
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.toConnectionError
import app.mumcord.mobile.ui.camera.CameraAnswer
import app.mumcord.mobile.ui.camera.CameraGateway
import app.mumcord.mobile.ui.camera.record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The largest text taken from a QR code; a part of a transfer is far smaller. */
const val MAX_QR_CHARS = 4096

/**
 * The identity screens and the scan of a transfer from the desktop app: their own state and
 * what the user does on them. Key material never comes here; the typed code and the passwords
 * of identity files only pass through, in memory.
 */
class IdentitiesController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val camera: CameraGateway,
) : IdentitySheetActions {
    val local = MutableStateFlow(IdentitiesLocal())

    /** The picked identity file while its password is asked for. */
    private var importData: ByteArray? = null

    /** The bridge's side of the open scan. */
    private var transfer: TransferScan? = null

    /** The QR text refused last: the camera sees it many times a second. */
    private var refusedText: String? = null

    fun open() = local.update { it.copy(open = true) }

    fun close() = local.update { it.copy(open = false, sheet = null, unreadableFile = false) }

    override fun closeSheet() {
        importData = null
        local.update { state ->
            val sheet = state.sheet
            // "Back up first" came from the delete warning and returns to it.
            val back = (sheet as? IdentitySheetLocal.Export)?.takeIf { it.fromDelete }
            state.copy(sheet = back?.let { IdentitySheetLocal.Delete(it.identityId) })
        }
    }

    fun openDetails(identityId: Long) = local.update { it.copy(sheet = IdentitySheetLocal.Details(identityId)) }

    // ----- create -----

    fun openCreate() = local.update { it.copy(sheet = IdentitySheetLocal.Create(), unreadableFile = false) }

    override fun changeCreateName(name: String) = sheet<IdentitySheetLocal.Create> {
        it.copy(name = name.take(MAX_IDENTITY_NAME), error = null)
    }

    override fun create() {
        val form = local.value.sheet as? IdentitySheetLocal.Create ?: return
        val name = form.name.trim()
        if (name.isEmpty() || form.saving) return
        sheet<IdentitySheetLocal.Create> { it.copy(saving = true, error = null) }
        scope.launch {
            when (val created = repository.createIdentity(name)) {
                is Outcome.Ok -> local.update { it.copy(sheet = null) }
                is Outcome.Failed -> sheet<IdentitySheetLocal.Create> { it.copy(saving = false, error = created.error) }
            }
        }
    }

    // ----- import a file -----

    /** The bytes of the file the user picked, or null when it could not be read. */
    fun importFile(data: ByteArray?) {
        importData = data
        local.update {
            if (data == null) {
                it.copy(unreadableFile = true)
            } else {
                it.copy(sheet = IdentitySheetLocal.Import(), unreadableFile = false)
            }
        }
    }

    override fun changeImportPassword(password: String) = sheet<IdentitySheetLocal.Import> {
        it.copy(password = password, problem = null)
    }

    override fun confirmImport() {
        val form = local.value.sheet as? IdentitySheetLocal.Import ?: return
        val data = importData ?: return
        if (form.saving) return
        sheet<IdentitySheetLocal.Import> { it.copy(saving = true, problem = null) }
        scope.launch {
            when (val imported = repository.importIdentity(data, form.password)) {
                is Outcome.Ok -> {
                    importData = null
                    local.update { it.copy(sheet = null) }
                }
                is Outcome.Failed -> sheet<IdentitySheetLocal.Import> {
                    it.copy(
                        saving = false,
                        password = "",
                        problem = when (imported.error) {
                            ConnectionError.InvalidInput -> ImportProblem.WrongPasswordOrFile
                            ConnectionError.DuplicateIdentity -> ImportProblem.Duplicate
                            else -> ImportProblem.Failed
                        },
                    )
                }
            }
        }
    }

    // ----- default, export, delete -----

    override fun setDefault(identityId: Long) {
        scope.launch {
            if (repository.setDefaultIdentity(identityId) is Outcome.Failed) repository.reportFailure()
        }
    }

    override fun openExport(identityId: Long) = local.update { state ->
        state.copy(sheet = IdentitySheetLocal.Export(identityId, fromDelete = state.sheet is IdentitySheetLocal.Delete))
    }

    override fun changeExportPassword(password: String) = sheet<IdentitySheetLocal.Export> {
        it.copy(password = password, failed = false)
    }

    override fun changeExportRepeat(repeat: String) = sheet<IdentitySheetLocal.Export> {
        it.copy(repeat = repeat, failed = false)
    }

    /** Makes the backup; it then waits in [IdentitiesLocal.exported] for the share sheet. */
    override fun export() {
        val form = local.value.sheet as? IdentitySheetLocal.Export ?: return
        if (!form.canExport) return
        val label = repository.state.value.identities.orEmpty().firstOrNull { it.id == form.identityId }?.label ?: return
        sheet<IdentitySheetLocal.Export> { it.copy(saving = true, failed = false) }
        scope.launch {
            when (val exported = repository.exportIdentity(form.identityId, form.password)) {
                is Outcome.Ok -> local.update {
                    it.copy(
                        sheet = if (form.fromDelete) IdentitySheetLocal.Delete(form.identityId) else null,
                        exported = ExportedIdentity(exportFileName(label), exported.value),
                    )
                }
                is Outcome.Failed -> sheet<IdentitySheetLocal.Export> {
                    it.copy(saving = false, password = "", repeat = "", failed = true)
                }
            }
        }
    }

    /** The backup was handed to the share sheet. */
    fun exportShared() = local.update { it.copy(exported = null) }

    override fun openDelete(identityId: Long) = local.update { it.copy(sheet = IdentitySheetLocal.Delete(identityId)) }

    override fun changeDeleteConfirmation(typed: String) = sheet<IdentitySheetLocal.Delete> {
        it.copy(typed = typed, error = null)
    }

    override fun delete() {
        val form = local.value.sheet as? IdentitySheetLocal.Delete ?: return
        val identities = repository.state.value.identities.orEmpty()
        val label = identities.firstOrNull { it.id == form.identityId }?.label ?: return
        if (form.deleting || identities.size < 2 || form.typed.trim() != label) return
        sheet<IdentitySheetLocal.Delete> { it.copy(deleting = true, error = null) }
        scope.launch {
            when (val deleted = repository.deleteIdentity(form.identityId)) {
                is Outcome.Ok -> local.update { it.copy(sheet = null) }
                is Outcome.Failed -> sheet<IdentitySheetLocal.Delete> { it.copy(deleting = false, error = deleted.error) }
            }
        }
    }

    private inline fun <reified T : IdentitySheetLocal> sheet(change: (T) -> T) = local.update { state ->
        val sheet = state.sheet as? T ?: return@update state
        state.copy(sheet = change(sheet))
    }

    // ----- scan from the desktop app -----

    fun openScan() {
        if (local.value.scan != null) return
        refusedText = null
        local.update { it.copy(scan = ScanState(camera = cameraNotice()), unreadableFile = false) }
        scope.launch {
            val scan = repository.newTransfer()
            when {
                scan == null -> updateScan { it.copy(note = ScanNote.Failed) }
                // Closed again before the engine was there.
                local.value.scan == null -> release(scan)
                else -> transfer = scan
            }
        }
    }

    fun closeScan() {
        local.update { it.copy(scan = null) }
        transfer?.let(::release)
        transfer = null
    }

    /**
     * Forgets the scan in the bridge. Not on the caller's thread: it waits for a check of the
     * code that is still running.
     */
    private fun release(scan: TransferScan) {
        scope.launch {
            try {
                repository.transferStep {
                    scan.reset()
                    scan.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Nothing to tell the user: the screen is closed and the scan is dropped anyway.
            }
        }
    }

    /** The text of a QR code the camera sees; called for the same code again and again. */
    fun onQrText(text: String) {
        val scan = transfer ?: return
        if (local.value.scan?.scanning != true || text == refusedText) return
        if (text.length > MAX_QR_CHARS) {
            refusedText = text
            updateScan { it.copy(note = ScanNote.NotATransfer) }
            return
        }
        try {
            try {
                scan.addPart(text)
            } catch (_: TransferException.OtherTransfer) {
                // The desktop made a new transfer: what was collected belongs to the old one.
                scan.reset()
                scan.addPart(text)
            }
            refusedText = null
            val standing = scan.standing()
            updateScan { it.afterPart(standing) }
        } catch (e: TransferException) {
            refusedText = text
            updateScan { it.afterPartRefused(e) }
        } catch (_: RuntimeException) {
            refusedText = text
            updateScan { it.copy(note = ScanNote.Failed) }
        }
    }

    /** The phone has no camera that can be used. */
    fun cameraFailed() = updateScan { it.copy(note = ScanNote.NoCamera) }

    /** What the user typed into the code field; the check starts with the last digit. */
    fun typeCode(text: String) {
        val scan = transfer ?: return
        val step = local.value.scan?.step as? ScanStep.Code ?: return
        val typed = codeInput(text, step.digits)
        if (typed.length < step.digits) {
            updateScan { it.copy(step = step.copy(typed = typed, wrong = step.wrong && typed.isEmpty()), note = null) }
            return
        }
        updateScan { it.copy(step = ScanStep.Checking(step.digits, step.attemptsLeft), note = null) }
        scope.launch {
            val next: (ScanState) -> ScanState = try {
                val preview = repository.transferStep { scan.open(typed) }
                ({ it.copy(step = preview.toStep(), note = null) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferException) {
                val standing = scan.standing()
                ({ it.afterCodeRefused(e, standing) })
            } catch (_: RuntimeException) {
                val standing = scan.standing()
                ({ it.afterCodeRefused(TransferException.Failed(), standing) })
            }
            // The screen may have been closed while the code was checked.
            if (transfer === scan) updateScan(next)
        }
    }

    /** The user confirmed what the preview lists. */
    fun importTransfer() {
        val scan = transfer ?: return
        val step = local.value.scan?.step as? ScanStep.Preview ?: return
        if (step.importing) return
        updateScan { it.copy(step = step.copy(importing = true, error = null)) }
        scope.launch {
            val next: ScanStep = try {
                repository.transferStep { scan.importOpened() }.toStep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                step.copy(importing = false, error = e.toConnectionError())
            } catch (_: RuntimeException) {
                step.copy(importing = false, error = ConnectionError.Internal)
            }
            if (transfer === scan) updateScan { it.copy(step = next) }
        }
    }

    private fun TransferScan.standing() =
        ScanStanding(progress(), codeDigits().toInt(), attemptsLeft().toInt())

    private fun updateScan(change: (ScanState) -> ScanState) = local.update { state ->
        state.copy(scan = state.scan?.let(change))
    }

    // ----- camera permission -----

    private fun cameraNotice(): CameraNotice? = when {
        camera.granted() -> null
        camera.promptBlocked -> CameraNotice.Blocked
        else -> CameraNotice.Explain
    }

    /** The user put the explanation away without letting the system ask. */
    fun cameraNotNow() = updateScan { it.copy(camera = CameraNotice.Denied) }

    fun answerCamera(answer: CameraAnswer) {
        camera.record(answer)
        updateScan {
            it.copy(
                camera = when {
                    answer.granted -> null
                    camera.promptBlocked -> CameraNotice.Blocked
                    else -> CameraNotice.Denied
                },
            )
        }
    }

    /** The app is on screen again: the permission may have changed in the system settings. */
    fun recheckCamera() {
        if (local.value.scan?.camera == null || !camera.granted()) return
        camera.promptBlocked = false
        updateScan { it.copy(camera = null) }
    }
}
