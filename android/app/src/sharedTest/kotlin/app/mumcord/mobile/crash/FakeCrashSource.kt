// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.crash

import app.mumcord.mobile.bridge.CrashKind
import app.mumcord.mobile.bridge.CrashReport
import java.util.concurrent.CopyOnWriteArrayList

/** A saved report as the bridge lists it; 14 Nov 2023 for the default time. */
fun crashReport(
    id: String,
    createdMs: Long = 1_700_000_000_000,
    kind: CrashKind = CrashKind.Panic,
    cause: String = "attempt to subtract with overflow",
    version: String = "0.8.0",
    uploaded: Boolean = false,
): CrashReport = CrashReport(id = id, createdMs = createdMs, version = version, kind = kind, cause = cause, uploaded = uploaded)

/** The text the fake gives as the preview of report [id]; the comment comes last, as the engine adds it. */
fun crashPreview(id: String, comment: String): String =
    "Mumcord crash report\nversion: 0.8.0\nkind: panic\nthread: $id\n" + if (comment.isEmpty()) "" else "\ncomment:\n$comment\n"

/**
 * Crash reports without the engine: it records what would have been sent and sends nothing.
 * [unseen] is handed out once, as the bridge does.
 */
class FakeCrashSource(reports: List<CrashReport>, unseen: List<CrashReport> = reports) : CrashSource {
    val reports = CopyOnWriteArrayList(reports)
    val uploads = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile
    var uploadWorks = true

    @Volatile
    var listWorks = true

    @Volatile
    private var unseen = unseen

    override fun list(): List<CrashReport> {
        check(listWorks) { "crash reports unreadable" }
        return reports.sortedByDescending { it.createdMs }
    }

    override fun takeUnseen(): List<CrashReport> = unseen.also { unseen = emptyList() }

    override fun preview(id: String, comment: String): String {
        check(reports.any { it.id == id }) { "no such crash report" }
        return crashPreview(id, comment)
    }

    override fun delete(id: String) {
        reports.removeIf { it.id == id }
    }

    override fun deleteAll() = reports.clear()

    override suspend fun upload(id: String, comment: String): Boolean {
        if (!uploadWorks) return false
        uploads += id to comment
        reports.replaceAll { if (it.id == id) it.copy(uploaded = true) else it }
        return true
    }
}
