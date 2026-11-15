// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.messages

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.ui.common.Paragraph
import app.mumcord.mobile.ui.common.SheetOverlay

const val BLOCK_SHEET_TEST_TAG = "block-sheet"
const val BLOCK_CONFIRM_TEST_TAG = "block-confirm"
const val CHAT_REQUEST_NOTE_TEST_TAG = "chat-request-note"
const val CHAT_REQUEST_SEND_TEST_TAG = "chat-request-send"
const val CHAT_REQUEST_ERROR_TEST_TAG = "chat-request-error"

/** Draws the question about blocking or the chat request that is open. */
@Composable
fun MessagesSheetHost(
    sheet: MessagesSheet,
    actions: MessagesController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    when (sheet) {
        is MessagesSheet.BlockConfirm -> BlockSheet(sheet, actions, topInsets, bottomInsets)
        is MessagesSheet.ChatRequest -> ChatRequestSheet(sheet, actions, topInsets, bottomInsets)
    }
}

/** Says what a block does, as the desktop app does, before anything is blocked. */
@Composable
private fun BlockSheet(
    sheet: MessagesSheet.BlockConfirm,
    actions: MessagesController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.block_title, sheet.name), actions::dismissSheet, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.block_private, sheet.name), Modifier.testTag(BLOCK_SHEET_TEST_TAG))
        Paragraph(stringResource(R.string.block_channel, sheet.name))
        Paragraph(stringResource(R.string.block_voice, sheet.name))
        Paragraph(stringResource(R.string.block_silent))
        if (sheet.failed) ErrorBanner(stringResource(R.string.block_failed))
        MumcordButton(
            text = stringResource(R.string.person_block),
            onClick = actions::confirmBlock,
            style = MumcordButtonStyle.Danger,
            modifier = Modifier.fillMaxWidth().testTag(BLOCK_CONFIRM_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::dismissSheet,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A chat request with an optional short note, as on the desktop. */
@Composable
private fun ChatRequestSheet(
    sheet: MessagesSheet.ChatRequest,
    actions: MessagesController,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.chat_request_title, sheet.name), actions::dismissSheet, topInsets, bottomInsets) {
        Paragraph(stringResource(R.string.chat_request_text, sheet.name))
        MumcordTextField(
            value = sheet.note,
            onValueChange = actions::changeRequestNote,
            label = stringResource(R.string.chat_request_note),
            hint = stringResource(R.string.chat_request_counter, sheet.note.length, CHAT_REQUEST_NOTE_MAX),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { actions.sendChatRequest() }),
            modifier = Modifier.testTag(CHAT_REQUEST_NOTE_TEST_TAG),
        )
        when (sheet.error) {
            ChatRequestError.Refused -> ErrorBanner(stringResource(R.string.chat_request_refused, sheet.name))
            ChatRequestError.Failed -> ErrorBanner(stringResource(R.string.chat_request_failed))
            null -> Unit
        }
        MumcordButton(
            text = stringResource(R.string.chat_request_send),
            onClick = actions::sendChatRequest,
            enabled = !sheet.sending,
            modifier = Modifier.fillMaxWidth().testTag(CHAT_REQUEST_SEND_TEST_TAG),
        )
        MumcordButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions::dismissSheet,
            style = MumcordButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    MumcordBanner(
        text = text,
        tone = MumcordBannerTone.Warning,
        icon = MumcordIcons.Alert,
        horizontalMargin = 0.dp,
        modifier = Modifier.testTag(CHAT_REQUEST_ERROR_TEST_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
