// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.crash

import app.mumcord.mobile.bridge.CrashReport
import app.mumcord.mobile.crash.CrashSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Crash reports as on the desktop (FR-5.103): the notice after a crash, the list of saved
 * reports with delete, and the upload sheet. A report leaves the phone only through [send], the
 * user's own "Send report" in the sheet that shows exactly what goes out. Created once per app
 * start, so the new reports are taken once.
 */
class CrashController(
    private val source: CrashSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(CrashLocal())
    val local: StateFlow<CrashLocal> = mutable.asStateFlow()

    private var previewJob: Job? = null

    init {
        scope.launch {
            val report = read("reading new crash reports") { source.takeUnseen() }?.let(::noticeReport)
            if (report != null) mutable.update { it.copy(notice = CrashNotice.Crashed(report)) }
        }
    }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    /** Reads the list when it opens; what the last action did is forgotten. */
    fun loadReports() {
        mutable.update { it.copy(status = null) }
        scope.launch { reload() }
    }

    fun openUpload(report: CrashReport) {
        mutable.update { it.copy(upload = CrashUpload(report), status = null) }
        refreshPreview()
    }

    fun changeComment(comment: String) {
        mutable.update { it.copy(upload = it.upload?.copy(comment = comment, failed = false)) }
        refreshPreview()
    }

    fun cancelUpload() {
        previewJob?.cancel()
        mutable.update { it.copy(upload = null) }
    }

    /** Sends the open report with its comment, only when the sheet shows that very text. */
    fun send() {
        val upload = mutable.value.upload?.takeIf { it.canSend } ?: return
        mutable.update { it.copy(upload = upload.copy(sending = true, failed = false)) }
        scope.launch {
            val sent = source.upload(upload.report.id, upload.comment)
            mutable.update { state ->
                if (sent) {
                    val notice = state.notice
                    state.copy(
                        upload = null,
                        notice = if (notice is CrashNotice.Crashed && notice.report.id == upload.report.id) CrashNotice.Sent else notice,
                        status = CrashStatus.Sent,
                    )
                } else {
                    // It stays on the phone; the sheet stays open to try again.
                    state.copy(upload = state.upload?.copy(sending = false, failed = true))
                }
            }
            if (sent && mutable.value.reports is CrashList.Loaded) reload()
        }
    }

    fun askDelete(report: CrashReport) = mutable.update { it.copy(confirm = CrashDelete.One(report)) }

    fun askDeleteAll() = mutable.update { it.copy(confirm = CrashDelete.All) }

    fun cancelDelete() = mutable.update { it.copy(confirm = null) }

    fun confirmDelete() {
        val target = mutable.value.confirm ?: return
        mutable.update { it.copy(confirm = null, status = null) }
        scope.launch {
            val deleted = read("deleting crash reports") {
                when (target) {
                    is CrashDelete.One -> source.delete(target.report.id)
                    CrashDelete.All -> source.deleteAll()
                }
            } != null
            mutable.update { state ->
                val notice = state.notice
                val gone = deleted && notice is CrashNotice.Crashed &&
                    (target == CrashDelete.All || (target as CrashDelete.One).report.id == notice.report.id)
                state.copy(
                    notice = if (gone) null else notice,
                    status = when {
                        !deleted -> CrashStatus.Failed
                        target == CrashDelete.All -> CrashStatus.DeletedAll
                        else -> CrashStatus.Deleted
                    },
                )
            }
            reload()
        }
    }

    private suspend fun reload() {
        val reports = read("reading the crash reports") { source.list() }
        mutable.update { it.copy(reports = reports?.let { all -> CrashList.Loaded(shownCrashes(all)) } ?: CrashList.Failed) }
    }

    /** Reads what the open sheet would send; a newer comment replaces an older reading. */
    private fun refreshPreview() {
        val upload = mutable.value.upload ?: return
        previewJob?.cancel()
        previewJob = scope.launch {
            val text = read("reading the crash report for the upload") { source.preview(upload.report.id, upload.comment) }
            mutable.update { state ->
                val open = state.upload?.takeIf { it.report.id == upload.report.id && it.comment == upload.comment }
                state.copy(upload = open?.copy(preview = text, previewFor = upload.comment, unreadable = text == null) ?: state.upload)
            }
        }
    }

    /** Runs [block] on [io]; a failure is logged by its kind only (the detail names files) and gives null. */
    private suspend fun <T : Any> read(what: String, block: () -> T): T? =
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed (${e.javaClass.simpleName})")
            null
        } catch (e: LinkageError) {
            // The native library is missing or does not fit this device.
            log("$what failed (${e.javaClass.simpleName})")
            null
        }
}
