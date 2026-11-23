// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.DefaultOutput
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordNote
import app.mumcord.mobile.design.components.MumcordOptionRow
import app.mumcord.mobile.design.components.MumcordOutputChip
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.platform.call.AudioOutput
import app.mumcord.mobile.platform.call.OutputKind
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val OUTPUT_CHIP_TEST_TAG = "output-chip"
const val OUTPUT_ROW_TEST_TAG_PREFIX = "output-row-"
const val OUTPUT_QUESTION_SPEAKER_TEST_TAG = "output-question-speaker"
const val OUTPUT_QUESTION_EARPIECE_TEST_TAG = "output-question-earpiece"

/** The test tag of an output's line in the sheet. */
fun outputRowTestTag(kind: OutputKind): String = OUTPUT_ROW_TEST_TAG_PREFIX + kind.name

private fun OutputKind.icon(): ImageVector = when (this) {
    OutputKind.Speaker -> MumcordIcons.Speaker
    OutputKind.Earpiece -> MumcordIcons.Volume
    OutputKind.Bluetooth -> MumcordIcons.Bluetooth
    OutputKind.WiredHeadset -> MumcordIcons.Headphones
}

/** The words for an output: the app's own, or the name of a Bluetooth device. */
@Composable
private fun outputName(kind: OutputKind, name: String): String = when (kind) {
    OutputKind.Speaker -> stringResource(R.string.output_speaker)
    OutputKind.Earpiece -> stringResource(R.string.output_earpiece)
    OutputKind.WiredHeadset -> stringResource(R.string.output_wired)
    OutputKind.Bluetooth -> name.ifBlank { stringResource(R.string.output_bluetooth) }
}

/** The chip in the voice view's header (mockup `03-voice-expanded`); it opens the sheet. */
@Composable
fun OutputChip(current: AudioOutput, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = outputName(current.kind, current.name)
    MumcordOutputChip(
        text = name,
        icon = current.kind.icon(),
        contentDescription = stringResource(R.string.output_chip, name),
        onClick = onClick,
        modifier = modifier.testTag(OUTPUT_CHIP_TEST_TAG),
    )
}

/** The choice of the audio output (mockup `04-audio-output`). Picking one switches at once. */
@Composable
fun AudioOutputSheet(
    rows: List<OutputRow>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.output_title), onDismiss, topInsets, bottomInsets) {
        MumcordList(
            horizontalMargin = 0.dp,
            background = MumcordTheme.colors.bgHover,
            modifier = Modifier.selectableGroup(),
        ) {
            rows.forEachIndexed { index, row ->
                MumcordOptionRow(
                    title = outputName(row.kind, row.name),
                    icon = row.kind.icon(),
                    selected = row.selected,
                    onClick = { row.id?.let(onSelect) },
                    subtitle = when {
                        row.id == null -> stringResource(R.string.output_not_connected)
                        row.kind == OutputKind.Bluetooth -> stringResource(R.string.output_bluetooth)
                        else -> null
                    },
                    unavailable = row.id == null,
                    divider = index > 0,
                    modifier = Modifier.testTag(outputRowTestTag(row.kind)),
                )
            }
        }
        MumcordNote(stringResource(R.string.output_note))
    }
}

/**
 * Asked with the first connection without a headset (FR-5.34): loudspeaker or earpiece. The
 * answer is remembered. Putting the sheet away keeps what the phone chose and asks again with
 * the next session.
 */
@Composable
fun OutputQuestionSheet(
    onAnswer: (DefaultOutput) -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.output_question_title), onDismiss, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.output_question_text))
        MumcordList(horizontalMargin = 0.dp, background = MumcordTheme.colors.bgHover) {
            MumcordOptionRow(
                title = stringResource(R.string.output_question_speaker),
                icon = MumcordIcons.Speaker,
                selected = false,
                onClick = { onAnswer(DefaultOutput.SPEAKER) },
                modifier = Modifier.testTag(OUTPUT_QUESTION_SPEAKER_TEST_TAG),
            )
            MumcordOptionRow(
                title = stringResource(R.string.output_question_earpiece),
                icon = MumcordIcons.Volume,
                selected = false,
                onClick = { onAnswer(DefaultOutput.EARPIECE) },
                divider = true,
                modifier = Modifier.testTag(OUTPUT_QUESTION_EARPIECE_TEST_TAG),
            )
        }
        MumcordNote(stringResource(R.string.output_note))
    }
}
