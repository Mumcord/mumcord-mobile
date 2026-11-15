// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.profile

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordAvatar
import app.mumcord.mobile.design.components.MumcordAvatarSize
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordIconButton
import app.mumcord.mobile.design.components.MumcordIconButtonStyle
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.components.MumcordTextField
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.ui.common.SheetOverlay
import app.mumcord.mobile.ui.connected.avatarSlot
import app.mumcord.mobile.ui.connected.initials

const val PROFILE_SAVE_TEST_TAG = "profile-save"
const val PROFILE_PHOTO_TEST_TAG = "profile-photo"
const val PROFILE_COMMENT_TEST_TAG = "profile-comment"
const val PROFILE_ERROR_TEST_TAG = "profile-error"

/**
 * Edit profile (mockup `20-edit-profile`, FR-5.80): the avatar with "Change photo", the name of
 * the connection (set per server, not changed here), the comment as plain text with the server's
 * limit, and how others see the result. "Save" sends what changed; going back drops it.
 */
@Composable
fun EditProfileScreen(state: ProfileEditUiState, actions: ProfileController, topInsets: WindowInsets) {
    val edit = state.edit
    val colors = MumcordTheme.colors
    val avatar = rememberAvatarBitmap(state.avatar)
    Column(Modifier.fillMaxSize()) {
        MumcordAppBar(
            title = stringResource(R.string.profile_title),
            onBack = actions::close,
            backContentDescription = stringResource(R.string.action_back),
            windowInsets = topInsets,
            actions = {
                MumcordButton(
                    text = stringResource(R.string.profile_save),
                    onClick = actions::save,
                    small = true,
                    enabled = edit.canSave,
                    modifier = Modifier.testTag(PROFILE_SAVE_TEST_TAG),
                )
            },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s4),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = MumcordSpace.s3), contentAlignment = Alignment.Center) {
                Box {
                    MumcordAvatar(
                        initials = state.name.initials(1),
                        colorSlot = avatarSlot(state.name),
                        size = MumcordAvatarSize.Profile,
                        image = avatar,
                    )
                    // The badge sits on the avatar's edge, ringed in the screen colour (`.av .iconbtn`).
                    MumcordIconButton(
                        icon = MumcordIcons.Camera,
                        contentDescription = stringResource(R.string.profile_change_photo),
                        onClick = actions::openAvatarSheet,
                        style = MumcordIconButtonStyle.On,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 10.dp, y = 6.dp)
                            .border(3.dp, colors.bgChat, CircleShape)
                            .testTag(PROFILE_PHOTO_TEST_TAG),
                    )
                }
            }
            edit.error?.let { error ->
                BasicText(
                    text = stringResource(
                        when (error) {
                            ProfileError.AvatarUnreadable -> R.string.profile_avatar_unreadable
                            ProfileError.SaveFailed -> R.string.profile_save_failed
                        },
                    ),
                    style = MumcordTheme.typography.small.copy(color = colors.danger, textAlign = TextAlign.Center),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MumcordSpace.s4)
                        .testTag(PROFILE_ERROR_TEST_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(MumcordSpace.s1))
            MumcordTextField(
                value = state.name,
                onValueChange = {},
                label = stringResource(R.string.profile_name),
                hint = stringResource(R.string.profile_name_hint),
                readOnly = true,
                trailingIcon = MumcordIcons.Lock,
                modifier = Modifier.padding(horizontal = MumcordSpace.s4),
            )
            CommentField(edit, actions)
            MumcordSectionHeader(stringResource(R.string.profile_preview))
            Preview(state, avatar)
        }
    }
}

@Composable
private fun CommentField(edit: ProfileEdit, actions: ProfileController) {
    val colors = MumcordTheme.colors
    Column(Modifier.padding(horizontal = MumcordSpace.s4)) {
        MumcordTextField(
            value = edit.comment,
            onValueChange = actions::changeComment,
            label = stringResource(R.string.profile_comment),
            error = if (edit.tooLong) stringResource(R.string.profile_too_long) else null,
            minLines = COMMENT_LINES,
            modifier = Modifier.testTag(PROFILE_COMMENT_TEST_TAG),
        )
        val limit = edit.limit
        if (limit != null) {
            val description = pluralStringResource(R.plurals.profile_comment_count_description, limit.toInt(), edit.comment.length, limit.toInt())
            BasicText(
                text = stringResource(R.string.profile_comment_count, edit.comment.length, limit.toInt()),
                style = MumcordTheme.typography.caption.copy(
                    color = if (edit.tooLong) colors.danger else colors.textMuted,
                    textAlign = TextAlign.End,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .semantics { contentDescription = description },
            )
        }
    }
}

/** The profile as the person list shows it to others (`.prev`). */
@Composable
private fun Preview(state: ProfileEditUiState, avatar: ImageBitmap?) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.list)
    val you = stringResource(R.string.profile_you)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .fillMaxWidth()
            .background(colors.bgElevated, shape)
            .border(1.dp, colors.border, shape)
            .padding(MumcordSpace.s4)
            .semantics(mergeDescendants = true) {},
    ) {
        MumcordAvatar(
            initials = state.name.initials(1),
            colorSlot = avatarSlot(state.name),
            size = MumcordAvatarSize.Large,
            image = avatar,
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(state.name) }
                    append(' ')
                    withStyle(MumcordTheme.typography.small.toSpanStyle().copy(color = colors.textMuted)) { append(you) }
                },
                style = MumcordTheme.typography.body.copy(color = colors.text),
            )
            // Plain characters, line breaks kept: what the server shows others is this text.
            if (state.edit.comment.isNotBlank()) {
                BasicText(
                    text = state.edit.comment.trim(),
                    style = MumcordTheme.typography.small.copy(color = colors.textSecondary),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// The text area of the mockup is three lines high before it grows.
private const val COMMENT_LINES = 3

/** Where a new profile picture comes from, or removing the one there is. */
@Composable
fun AvatarSourceSheet(
    hasAvatar: Boolean,
    onLibrary: () -> Unit,
    onCamera: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    SheetOverlay(stringResource(R.string.profile_avatar_title), onDismiss, topInsets, bottomInsets) {
        MumcordList(horizontalMargin = MumcordSpace.s1) {
            MumcordListRow(
                title = stringResource(R.string.image_source_library),
                icon = MumcordIcons.Image,
                onClick = onLibrary,
                modifier = Modifier.testTag(AVATAR_LIBRARY_TEST_TAG),
            )
            MumcordListRow(
                title = stringResource(R.string.image_source_camera),
                icon = MumcordIcons.Camera,
                divider = true,
                onClick = onCamera,
                modifier = Modifier.testTag(AVATAR_CAMERA_TEST_TAG),
            )
            if (hasAvatar) {
                MumcordListRow(
                    title = stringResource(R.string.profile_avatar_remove),
                    icon = MumcordIcons.Trash,
                    divider = true,
                    danger = true,
                    onClick = onRemove,
                    modifier = Modifier.testTag(AVATAR_REMOVE_TEST_TAG),
                )
            }
        }
    }
}

const val AVATAR_LIBRARY_TEST_TAG = "avatar-library"
const val AVATAR_CAMERA_TEST_TAG = "avatar-camera"
const val AVATAR_REMOVE_TEST_TAG = "avatar-remove"
