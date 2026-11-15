// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordScreen
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.connect.messageRes

const val SCAN_SCREEN_TEST_TAG = "scan-screen"
const val SCAN_PROGRESS_TEST_TAG = "scan-progress"
const val SCAN_NOTE_TEST_TAG = "scan-note"
const val SCAN_CODE_TEST_TAG = "scan-code"
const val SCAN_CODE_STATUS_TEST_TAG = "scan-code-status"
const val SCAN_CAMERA_ACTION_TEST_TAG = "scan-camera-action"
const val SCAN_SUMMARY_TEST_TAG = "scan-summary"
const val SCAN_IMPORT_TEST_TAG = "scan-import"
const val SCAN_DONE_TEST_TAG = "scan-done"

/**
 * "Scan from desktop app" (mockup 18): the camera collects the QR codes of a transfer in any
 * order, then the user types the code the desktop shows, sees what would arrive and imports it.
 * [viewfinder] draws the camera picture; it is only called while there is something to scan and
 * the camera may be used.
 */
@Composable
fun ScanScreen(
    state: ScanState,
    viewfinder: @Composable (Modifier) -> Unit,
    onClose: () -> Unit,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onCode: (String) -> Unit,
    onImport: () -> Unit,
    topInsets: WindowInsets = WindowInsets.statusBars,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
) {
    MumcordScreen(Modifier.testTag(SCAN_SCREEN_TEST_TAG)) {
        MumcordAppBar(
            title = stringResource(R.string.scan_title),
            onBack = onClose,
            backContentDescription = stringResource(R.string.action_close),
            // A screen of its own task, closed rather than gone back from (mockup 18).
            backIcon = MumcordIcons.X,
            windowInsets = topInsets,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4),
            modifier = Modifier
                .weight(1f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(bottomInsets)
                .padding(start = MumcordSpace.s4, top = MumcordSpace.s2, end = MumcordSpace.s4, bottom = MumcordSpace.s4),
        ) {
            when (val step = state.step) {
                is ScanStep.Scanning -> {
                    Viewfinder(state, viewfinder, onAllowCamera, onOpenSettings)
                    Column(verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3)) {
                        Progress(step.have, step.total)
                        Hint(step.total == 0)
                    }
                    Note(state.note)
                }
                is ScanStep.Code -> {
                    AllScanned()
                    Note(state.note)
                    CodeEntry(step, onCode)
                }
                is ScanStep.Checking -> {
                    AllScanned()
                    Checking(step.digits)
                }
                is ScanStep.Preview -> Preview(step, onImport)
                is ScanStep.Done -> Done(step, onClose)
            }
        }
    }
}

private val VIEWFINDER_HEIGHT = 300.dp
private val FRAME_SIZE = 210.dp

/** The camera picture in its card (`.cam`), or what to do while the camera cannot be used. */
@Composable
private fun Viewfinder(
    state: ScanState,
    viewfinder: @Composable (Modifier) -> Unit,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(VIEWFINDER_HEIGHT)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.bgServers, colors.bgHover)))
            .border(1.dp, colors.border, shape),
    ) {
        when (state.camera) {
            null -> {
                val description = stringResource(R.string.scan_viewfinder)
                MumcordIcon(
                    icon = MumcordIcons.QrCode,
                    contentDescription = null,
                    tint = colors.textMuted,
                    size = 72.dp,
                    modifier = Modifier.alpha(0.5f),
                )
                if (state.note != ScanNote.NoCamera) {
                    viewfinder(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description })
                }
                val accent = colors.accentText
                Box(Modifier.size(FRAME_SIZE).drawBehind { drawFrame(accent) })
            }
            // The explanation is a sheet over this screen; behind it the card stays calm.
            CameraNotice.Explain -> Unit
            CameraNotice.Denied, CameraNotice.Blocked -> CameraRefused(
                blocked = state.camera == CameraNotice.Blocked,
                onClick = if (state.camera == CameraNotice.Blocked) onOpenSettings else onAllowCamera,
            )
        }
    }
}

/** The four corners and the line of the mockup's scan frame. */
private fun DrawScope.drawFrame(color: Color) {
    val arm = 38.dp.toPx()
    val radius = 14.dp.toPx()
    val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Butt)
    val inset = stroke.width / 2
    val w = size.width - inset
    val h = size.height - inset
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        val path = Path().apply {
            moveTo(x, y + dy * arm)
            lineTo(x, y + dy * radius)
            quadraticTo(x, y, x + dx * radius, y)
            lineTo(x + dx * arm, y)
        }
        drawPath(path, color, style = stroke)
    }
    corner(inset, inset, 1f, 1f)
    corner(w, inset, -1f, 1f)
    corner(inset, h, 1f, -1f)
    corner(w, h, -1f, -1f)
    val line = 2.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(12.dp.toPx(), size.height / 2 - line / 2),
        size = Size(size.width - 24.dp.toPx(), line),
        cornerRadius = CornerRadius(line / 2),
    )
}

@Composable
private fun CameraRefused(blocked: Boolean, onClick: () -> Unit) {
    val colors = MumcordTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = Modifier.padding(MumcordSpace.s4),
    ) {
        MumcordIcon(MumcordIcons.Alert, contentDescription = null, tint = colors.warning, size = MumcordSize.iconLg)
        BasicText(
            text = stringResource(if (blocked) R.string.scan_camera_blocked else R.string.scan_camera_denied),
            style = MumcordTheme.typography.body.copy(color = colors.text, textAlign = TextAlign.Center),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        MumcordButton(
            text = stringResource(if (blocked) R.string.camera_open_settings else R.string.camera_allow),
            onClick = onClick,
            modifier = Modifier.testTag(SCAN_CAMERA_ACTION_TEST_TAG),
        )
    }
}

/** "Code 3 of 7" with its share and one segment per code (`.prog`). */
@Composable
private fun Progress(have: Int, total: Int) {
    val colors = MumcordTheme.colors
    val text = if (total == 0) {
        stringResource(R.string.scan_progress_none)
    } else {
        stringResource(R.string.scan_progress, have, total)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
        modifier = Modifier
            .testTag(SCAN_PROGRESS_TEST_TAG)
            // One announcement per new code, not one per element.
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(text, style = MumcordTheme.typography.bodyStrong.copy(color = colors.text), modifier = Modifier.weight(1f))
            if (total > 0) {
                BasicText(
                    text = stringResource(R.string.scan_percent, ScanStep.Scanning(have, total).percent()),
                    style = MumcordTheme.typography.small.copy(color = colors.textMuted),
                )
            }
        }
        if (total > 0) {
            // Many codes share the width; the gap shrinks before the segments vanish.
            val gap = if (total > MANY_SEGMENTS) 2.dp else 6.dp
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(total) { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(8.dp)
                            .background(if (index < have) colors.accent else colors.bgHover, CircleShape),
                    )
                }
            }
        }
    }
}

private const val MANY_SEGMENTS = 24

@Composable
private fun Hint(first: Boolean) {
    BasicText(
        text = stringResource(if (first) R.string.scan_hint_where else R.string.scan_hint),
        style = MumcordTheme.typography.small.copy(
            color = MumcordTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Note(note: ScanNote?) {
    if (note == null) return
    val calm = note == ScanNote.NotATransfer || note == ScanNote.Unreadable
    MumcordBanner(
        text = stringResource(
            when (note) {
                ScanNote.NotATransfer -> R.string.scan_note_not_a_transfer
                ScanNote.Unreadable -> R.string.scan_note_unreadable
                ScanNote.NewerVersion -> R.string.scan_note_newer_version
                ScanNote.Expired -> R.string.scan_note_expired
                ScanNote.ClockSkew -> R.string.scan_note_clock_skew
                ScanNote.RescanRequired -> R.string.scan_note_rescan
                ScanNote.Damaged -> R.string.scan_note_damaged
                ScanNote.OutOfMemory -> R.string.scan_note_out_of_memory
                ScanNote.Failed -> R.string.scan_note_failed
                ScanNote.NoCamera -> R.string.scan_note_no_camera
            },
        ),
        icon = MumcordIcons.Alert,
        tone = if (calm) MumcordBannerTone.Warning else MumcordBannerTone.Danger,
        horizontalMargin = 0.dp,
        modifier = Modifier.testTag(SCAN_NOTE_TEST_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Stands where the camera was once every code is in: the camera is off from here on. */
@Composable
private fun AllScanned() {
    val colors = MumcordTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgElevated, RoundedCornerShape(MumcordRadius.lg))
            .border(1.dp, colors.border, RoundedCornerShape(MumcordRadius.lg))
            .padding(horizontal = MumcordSpace.s4, vertical = 12.dp),
    ) {
        MumcordIcon(MumcordIcons.Check, contentDescription = null, tint = colors.success)
        BasicText(
            text = stringResource(R.string.scan_all_scanned),
            style = MumcordTheme.typography.bodyStrong.copy(color = colors.text),
        )
    }
}

/** The code of the desktop, typed into one box per digit in two groups (`.code`). */
@Composable
private fun CodeEntry(step: ScanStep.Code, onCode: (String) -> Unit) {
    val colors = MumcordTheme.colors
    val focus = remember { FocusRequester() }
    val description = pluralStringResource(R.plurals.scan_code_description, step.digits, step.digits)
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = stringResource(R.string.scan_code_label),
            style = MumcordTheme.typography.smallStrong.copy(color = colors.textSecondary),
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicTextField(
            // Not the text-only field: it drops a text equal to the last one it reported, which
            // lost the same code typed again when the refusal came back before the next frame.
            value = TextFieldValue(step.typed, selection = TextRange(step.typed.length)),
            onValueChange = { if (it.text != step.typed) onCode(it.text) },
            singleLine = true,
            // The digits are drawn by the boxes; the field itself only takes the typing.
            textStyle = MumcordTheme.typography.body.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag(SCAN_CODE_TEST_TAG)
                .semantics { contentDescription = description },
            decorationBox = { CodeBoxes(step.typed, step.digits, active = true) },
        )
        if (step.wrong) {
            BasicText(
                text = pluralStringResource(R.plurals.scan_wrong_code, step.attemptsLeft, step.attemptsLeft),
                style = MumcordTheme.typography.small.copy(color = colors.danger),
                modifier = Modifier.testTag(SCAN_CODE_STATUS_TEST_TAG).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun Checking(digits: Int) {
    val colors = MumcordTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The typed code is gone from the screen while it is checked.
        Box(Modifier.clearAndSetSemantics {}) { CodeBoxes("", digits, active = false) }
        BasicText(
            text = stringResource(R.string.scan_checking),
            style = MumcordTheme.typography.small.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(SCAN_CODE_STATUS_TEST_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun CodeBoxes(typed: String, digits: Int, active: Boolean) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    val half = digits / 2
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(digits) { index ->
            if (index > 0) Spacer(Modifier.width(if (index == half) MumcordSpace.s4 else 6.dp))
            val current = active && index == typed.length
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(colors.bgElevated, shape)
                    .border(if (current) 2.dp else 1.dp, if (current) colors.focus else colors.border, shape),
            ) {
                BasicText(
                    text = typed.getOrNull(index)?.toString().orEmpty(),
                    style = MumcordTheme.typography.bodyStrong.copy(
                        color = colors.text,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

/** What the transfer holds, before anything is imported (`.sum` and the lists below it). */
@Composable
private fun Preview(step: ScanStep.Preview, onImport: () -> Unit) {
    Summary(stringResource(R.string.scan_receiving), counts(step.identities.size, step.servers?.size))
    Names(stringResource(R.string.scan_section_identities), step.identities)
    step.servers?.let { servers ->
        Names(stringResource(R.string.scan_section_servers), servers)
        Paragraph(stringResource(R.string.scan_no_passwords))
    }
    step.error?.let { error ->
        MumcordBanner(
            text = stringResource(error.messageRes()),
            icon = MumcordIcons.Alert,
            tone = MumcordBannerTone.Danger,
            horizontalMargin = 0.dp,
            modifier = Modifier.testTag(SCAN_NOTE_TEST_TAG),
        )
    }
    MumcordButton(
        text = stringResource(if (step.importing) R.string.scan_importing else R.string.scan_import),
        onClick = onImport,
        enabled = !step.importing,
        modifier = Modifier.fillMaxWidth().testTag(SCAN_IMPORT_TEST_TAG),
    )
}

@Composable
private fun Done(step: ScanStep.Done, onClose: () -> Unit) {
    BasicText(
        text = stringResource(R.string.scan_done_title),
        style = MumcordTheme.typography.title.copy(color = MumcordTheme.colors.text),
        modifier = Modifier.semantics { heading() },
    )
    if (step.identitiesImported == 0 && step.serversImported == 0) {
        Paragraph(stringResource(R.string.scan_done_nothing))
    } else {
        Summary(
            stringResource(R.string.scan_done_added),
            counts(step.identitiesImported, step.serversImported.takeIf { it > 0 }, zeroIdentities = false),
        )
    }
    if (step.skipped.isNotEmpty()) {
        val duplicate = stringResource(R.string.scan_skipped_duplicate)
        val invalid = stringResource(R.string.scan_skipped_invalid)
        Names(
            stringResource(R.string.scan_section_skipped),
            step.skipped.map { "${it.name} · ${if (it.alreadyThere) duplicate else invalid}" },
        )
    }
    MumcordButton(
        text = stringResource(R.string.scan_done),
        onClick = onClose,
        modifier = Modifier.fillMaxWidth().testTag(SCAN_DONE_TEST_TAG),
    )
}

/** "1 identity, 4 servers"; a part with nothing in it is left out. */
@Composable
private fun counts(identities: Int, servers: Int?, zeroIdentities: Boolean = true): String {
    val identityText = pluralStringResource(R.plurals.scan_receiving_identities, identities, identities)
    val serverText = servers?.let { pluralStringResource(R.plurals.scan_receiving_servers, it, it) }
    return when {
        serverText == null -> identityText
        identities == 0 && !zeroIdentities -> serverText
        else -> stringResource(R.string.scan_receiving_both, identityText, serverText)
    }
}

@Composable
private fun Summary(lead: String, what: String) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    val text: AnnotatedString = buildAnnotatedString {
        append(lead)
        append(' ')
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(what) }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgElevated, shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = MumcordSpace.s4, vertical = 12.dp)
            .testTag(SCAN_SUMMARY_TEST_TAG),
    ) {
        MumcordIcon(MumcordIcons.Key, contentDescription = null, tint = colors.accentText)
        BasicText(text, style = MumcordTheme.typography.small.copy(color = colors.text))
    }
}

/** Names from the desktop are drawn as plain text, one per line. */
@Composable
private fun Names(title: String, names: List<String>) {
    val colors = MumcordTheme.colors
    Column {
        // The section header of the lists, without its own side margin: the screen has one.
        BasicText(
            text = title.uppercase(),
            style = MumcordTheme.typography.section.copy(color = colors.textMuted),
            modifier = Modifier.padding(bottom = MumcordSpace.s2).semantics { heading() },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgElevated, RoundedCornerShape(MumcordRadius.lg))
                .padding(horizontal = MumcordSpace.s4, vertical = MumcordSpace.s3),
        ) {
            names.forEach { name ->
                BasicText(name, style = MumcordTheme.typography.body.copy(color = colors.text))
            }
        }
    }
}
