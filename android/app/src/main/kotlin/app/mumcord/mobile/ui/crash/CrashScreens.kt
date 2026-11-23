// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.crash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.CrashKind
import app.mumcord.mobile.bridge.CrashReport
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val CRASH_NOTICE_TEST_TAG = "crash-notice"
const val CRASH_NOTICE_REPORT_TEST_TAG = "crash-notice-report"
const val CRASH_NOTICE_DISMISS_TEST_TAG = "crash-notice-dismiss"
const val CRASH_REPORTS_SCREEN_TEST_TAG = "crash-reports"
const val CRASH_REPORT_ROW_TEST_TAG = "crash-report-row"
const val CRASH_REPORT_UPLOAD_TEST_TAG = "crash-report-upload"
const val CRASH_REPORT_DELETE_TEST_TAG = "crash-report-delete"
const val CRASH_DELETE_ALL_TEST_TAG = "crash-delete-all"
const val CRASH_DELETE_CONFIRM_TEST_TAG = "crash-delete-confirm"
const val CRASH_UPLOAD_PREVIEW_TEST_TAG = "crash-upload-preview"
const val CRASH_UPLOAD_COMMENT_TEST_TAG = "crash-upload-comment"
const val CRASH_UPLOAD_SEND_TEST_TAG = "crash-upload-send"
const val CRASH_UPLOAD_CANCEL_TEST_TAG = "crash-upload-cancel"

/**
 * "Mumcord closed unexpectedly" on the server list after a crash in an earlier run (FR-5.103,
 * no mockup), as on the desktop: "Report…" opens the upload sheet, "Dismiss" hides it. Nothing
 * is sent from here.
 */
@Composable
fun CrashNoticeBanner(notice: CrashNotice, onReport: (CrashReport) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.testTag(CRASH_NOTICE_TEST_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
        when (notice) {
            is CrashNotice.Crashed -> MumcordBanner(
                title = stringResource(R.string.crash_notice_title),
                text = stringResource(R.string.crash_notice_text),
                icon = MumcordIcons.Alert,
                tone = MumcordBannerTone.Warning,
            )
            CrashNotice.Sent -> MumcordBanner(text = stringResource(R.string.crash_notice_sent), icon = MumcordIcons.Check)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier.padding(horizontal = MumcordSpace.s4),
        ) {
            if (notice is CrashNotice.Crashed) {
                MumcordButton(
                    text = stringResource(R.string.crash_notice_report),
                    onClick = { onReport(notice.report) },
                    small = true,
                    modifier = Modifier.testTag(CRASH_NOTICE_REPORT_TEST_TAG),
                )
            }
            MumcordButton(
                text = stringResource(R.string.crash_notice_dismiss),
                onClick = onDismiss,
                style = MumcordButtonStyle.Ghost,
                small = true,
                modifier = Modifier.testTag(CRASH_NOTICE_DISMISS_TEST_TAG),
            )
        }
    }
}

/**
 * The saved crash reports (FR-5.103, no mockup), reached from About: when, kind, version and
 * cause of each, "Report crash…" and "Delete", and "Delete all". Reports of the system ending
 * the app are left out (see [shownCrashes]).
 */
@Composable
fun CrashReportsScreen(
    state: CrashLocal,
    actions: CrashController,
    onBack: () -> Unit,
    topInsets: WindowInsets,
    clock: CrashClock,
) {
    Column(Modifier.fillMaxSize().testTag(CRASH_REPORTS_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.crash_reports_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.crash_reports_back),
            windowInsets = topInsets,
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
            Paragraph(
                stringResource(R.string.crash_reports_info),
                Modifier.padding(start = MumcordSpace.s4, end = MumcordSpace.s4, bottom = MumcordSpace.s2),
            )
            state.status?.let { StatusBanner(it) }
            when (val reports = state.reports) {
                is CrashList.Loaded -> ReportList(reports.reports, actions, clock)
                CrashList.Failed -> {
                    MumcordBanner(
                        text = stringResource(R.string.crash_reports_failed),
                        icon = MumcordIcons.Alert,
                        tone = MumcordBannerTone.Danger,
                    )
                    MumcordButton(
                        text = stringResource(R.string.crash_reports_retry),
                        onClick = actions::loadReports,
                        small = true,
                        modifier = Modifier.padding(horizontal = MumcordSpace.s4),
                    )
                }
                // Reading takes a moment at most; an empty page is calmer than a flashing spinner.
                CrashList.NotRead -> Unit
            }
        }
    }
}

@Composable
private fun StatusBanner(status: CrashStatus) {
    val failed = status == CrashStatus.Failed
    MumcordBanner(
        text = stringResource(
            when (status) {
                CrashStatus.Sent -> R.string.crash_status_sent
                CrashStatus.Deleted -> R.string.crash_status_deleted
                CrashStatus.DeletedAll -> R.string.crash_status_deleted_all
                CrashStatus.Failed -> R.string.crash_status_failed
            },
        ),
        icon = if (failed) MumcordIcons.Alert else MumcordIcons.Check,
        tone = if (failed) MumcordBannerTone.Danger else MumcordBannerTone.Info,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ReportList(reports: List<CrashReport>, actions: CrashController, clock: CrashClock) {
    if (reports.isEmpty()) {
        MumcordList {
            MumcordListRow(
                title = stringResource(R.string.crash_reports_empty_title),
                subtitle = stringResource(R.string.crash_reports_empty_text),
                icon = MumcordIcons.Check,
                wrapTitle = true,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3)) {
        reports.forEach { report -> ReportCard(report, actions, clock) }
        MumcordButton(
            text = stringResource(R.string.crash_delete_all),
            onClick = actions::askDeleteAll,
            style = MumcordButtonStyle.Danger,
            icon = MumcordIcons.Trash,
            modifier = Modifier.padding(horizontal = MumcordSpace.s4).fillMaxWidth().testTag(CRASH_DELETE_ALL_TEST_TAG),
        )
    }
}

@Composable
private fun ReportCard(report: CrashReport, actions: CrashController, clock: CrashClock) {
    val details = stringResource(R.string.crash_row_details, kindLabel(report.kind), report.version)
    MumcordList(Modifier.testTag(CRASH_REPORT_ROW_TEST_TAG)) {
        MumcordListRow(
            title = clock.format(report.createdMs),
            subtitle = if (report.uploaded) "$details · ${stringResource(R.string.crash_uploaded)}" else details,
            icon = MumcordIcons.Alert,
        )
        BasicText(
            text = report.cause,
            style = MumcordTheme.typography.caption.copy(color = MumcordTheme.colors.textSecondary),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = MumcordSpace.s4),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier.padding(start = MumcordSpace.s4, top = MumcordSpace.s2, end = MumcordSpace.s4, bottom = MumcordSpace.s3),
        ) {
            // Every card has the same two buttons; TalkBack names the report they belong to.
            val reportLabel = stringResource(R.string.crash_report_label, clock.format(report.createdMs))
            val deleteLabel = stringResource(R.string.crash_delete_label, clock.format(report.createdMs))
            MumcordButton(
                text = stringResource(R.string.crash_report),
                onClick = { actions.openUpload(report) },
                style = MumcordButtonStyle.Secondary,
                small = true,
                modifier = Modifier.testTag(CRASH_REPORT_UPLOAD_TEST_TAG).semantics { contentDescription = reportLabel },
            )
            MumcordButton(
                text = stringResource(R.string.crash_delete),
                onClick = { actions.askDelete(report) },
                style = MumcordButtonStyle.Ghost,
                small = true,
                icon = MumcordIcons.Trash,
                modifier = Modifier.testTag(CRASH_REPORT_DELETE_TEST_TAG).semantics { contentDescription = deleteLabel },
            )
        }
    }
}

@Composable
private fun kindLabel(kind: CrashKind): String = stringResource(
    when (kind) {
        CrashKind.Panic -> R.string.crash_kind_panic
        is CrashKind.Signal -> R.string.crash_kind_native
        // Not listed on Android (see shownCrashes); named as the desktop names it.
        CrashKind.Unexpected -> R.string.crash_kind_unexpected
    },
)

/** The crash sheets over any screen: the upload sheet or the delete question, or nothing. */
@Composable
fun CrashSheetHost(
    state: CrashLocal,
    actions: CrashController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    clock: CrashClock,
) {
    val upload = state.upload
    val confirm = state.confirm
    when {
        upload != null -> CrashUploadSheet(upload, actions, topInsets, bottomInsets, clock)
        confirm != null -> CrashDeleteSheet(confirm, shownCount(state), actions, topInsets, bottomInsets, clock)
    }
}

private fun shownCount(state: CrashLocal): Int = (state.reports as? CrashList.Loaded)?.reports?.size ?: 0

/**
 * "Send crash report to the Mumcord team?" (no mockup), as the desktop asks it: exactly what
 * will be sent, an optional comment, where it goes and what it shares. Sends only on "Send
 * report", and only while the text shown is the text of the comment typed.
 */
@Composable
fun CrashUploadSheet(
    upload: CrashUpload,
    actions: CrashController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    clock: CrashClock,
) {
    SheetOverlay(stringResource(R.string.crash_upload_title), actions::cancelUpload, topInsets, bottomInsets) {
        BasicText(
            text = "${clock.format(upload.report.createdMs)} · ${upload.report.id}",
            style = MumcordTheme.typography.caption.copy(color = MumcordTheme.colors.textMuted),
        )
        BasicText(
            text = stringResource(R.string.crash_upload_what),
            style = MumcordTheme.typography.smallStrong.copy(color = MumcordTheme.colors.text),
        )
        PreviewBox(upload.preview.orEmpty())
        if (upload.unreadable) ErrorText(stringResource(R.string.crash_upload_unreadable))
        MumcordTextField(
            value = upload.comment,
            onValueChange = actions::changeComment,
            label = stringResource(R.string.crash_upload_comment),
            placeholder = stringResource(R.string.crash_upload_comment_placeholder),
            minLines = 3,
            modifier = Modifier.testTag(CRASH_UPLOAD_COMMENT_TEST_TAG),
        )
        Paragraph(stringResource(R.string.crash_upload_where))
        Paragraph(stringResource(R.string.crash_upload_shared))
        if (upload.failed) ErrorText(stringResource(R.string.crash_upload_failed))
        MumcordButton(
            text = stringResource(if (upload.sending) R.string.crash_upload_sending else R.string.crash_upload_send),
            onClick = actions::send,
            enabled = upload.canSend,
            icon = MumcordIcons.Send,
            modifier = Modifier.fillMaxWidth().testTag(CRASH_UPLOAD_SEND_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::cancelUpload,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(CRASH_UPLOAD_CANCEL_TEST_TAG),
        )
    }
}

/** The report as it goes out, in a box of its own that scrolls; a report is up to a few hundred lines. */
@Composable
private fun PreviewBox(text: String) {
    val label = stringResource(R.string.crash_upload_what)
    val shape = RoundedCornerShape(MumcordRadius.lg)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 220.dp)
            .background(MumcordTheme.colors.bgHover, shape)
            .border(1.dp, MumcordTheme.colors.border, shape)
            .verticalScroll(rememberScrollState())
            .padding(MumcordSpace.s3)
            .testTag(CRASH_UPLOAD_PREVIEW_TEST_TAG)
            .semantics { contentDescription = label },
    ) {
        BasicText(
            text = text,
            style = MumcordTheme.typography.caption.copy(color = MumcordTheme.colors.textSecondary, fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun ErrorText(text: String) {
    BasicText(
        text = text,
        style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.danger),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "Delete crash report?" or "Delete all crash reports?" (no mockup). */
@Composable
private fun CrashDeleteSheet(
    confirm: CrashDelete,
    count: Int,
    actions: CrashController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
    clock: CrashClock,
) {
    val all = confirm == CrashDelete.All
    SheetOverlay(
        stringResource(if (all) R.string.crash_delete_all_title else R.string.crash_delete_title),
        actions::cancelDelete,
        topInsets,
        bottomInsets,
    ) {
        Paragraph(
            when (confirm) {
                CrashDelete.All -> pluralStringResource(R.plurals.crash_delete_all_text, count, count)
                is CrashDelete.One -> stringResource(R.string.crash_delete_text, clock.format(confirm.report.createdMs))
            },
        )
        Spacer(Modifier.height(MumcordSpace.s1))
        MumcordButton(
            text = stringResource(if (all) R.string.crash_delete_all else R.string.crash_delete),
            onClick = actions::confirmDelete,
            style = MumcordButtonStyle.Danger,
            icon = MumcordIcons.Trash,
            modifier = Modifier.fillMaxWidth().testTag(CRASH_DELETE_CONFIRM_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::cancelDelete,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
