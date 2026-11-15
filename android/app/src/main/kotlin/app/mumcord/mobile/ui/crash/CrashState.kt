// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.crash

import app.mumcord.mobile.bridge.CrashKind
import app.mumcord.mobile.bridge.CrashReport
import app.mumcord.mobile.ui.chat.ChatClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Everything the crash screens show (FR-5.103); see [CrashController]. */
data class CrashLocal(
    /** The notice on the server list, or null. */
    val notice: CrashNotice? = null,
    /** The list of saved reports, read when it is opened. */
    val reports: CrashList = CrashList.NotRead,
    /** The open "Send crash report?" sheet, or null. */
    val upload: CrashUpload? = null,
    /** The open delete question, or null. */
    val confirm: CrashDelete? = null,
    /** What the last action on the list did, or null. */
    val status: CrashStatus? = null,
)

/** The notice on the server list after a crash in an earlier run. */
sealed interface CrashNotice {
    /** The newest new report speaks for all; the others are in the list. */
    data class Crashed(val report: CrashReport) : CrashNotice

    /** The report of the notice was sent. */
    data object Sent : CrashNotice
}

sealed interface CrashList {
    data object NotRead : CrashList
    data object Failed : CrashList
    data class Loaded(val reports: List<CrashReport>) : CrashList
}

/**
 * The upload sheet of one report. [preview] is what goes out for the comment [previewFor]; it is
 * null until read and when the report cannot be read.
 */
data class CrashUpload(
    val report: CrashReport,
    val comment: String = "",
    val preview: String? = null,
    val previewFor: String? = null,
    val unreadable: Boolean = false,
    val sending: Boolean = false,
    val failed: Boolean = false,
) {
    /** Nothing is sent that the sheet does not show: the preview must be the one of this comment. */
    val canSend: Boolean get() = preview != null && previewFor == comment && !sending
}

sealed interface CrashDelete {
    data class One(val report: CrashReport) : CrashDelete
    data object All : CrashDelete
}

enum class CrashStatus { Sent, Deleted, DeletedAll, Failed }

/**
 * The reports the app shows, newest first. Android ends apps in the background routinely and
 * without warning (SIGKILL), which the engine can only record as an unclean exit of kind
 * [CrashKind.Unexpected]. On a phone that is almost never a crash, so these bring no notice and
 * are not listed; the engine is to stop recording them on phones.
 */
fun shownCrashes(reports: List<CrashReport>): List<CrashReport> =
    reports.filter { it.kind !is CrashKind.Unexpected }.sortedByDescending { it.createdMs }

/** The report the notice names: the newest shown one of the new reports. */
fun noticeReport(unseen: List<CrashReport>): CrashReport? = shownCrashes(unseen).firstOrNull()

/** How the crash screens write a report's time: in the zone and language of the chat's clock. */
data class CrashClock(val zone: ZoneId, val locale: Locale) {
    constructor(chat: ChatClock) : this(chat.zone, chat.locale)

    fun format(createdMs: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(createdMs))
}
