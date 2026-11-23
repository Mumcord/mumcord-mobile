// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.profile

import app.mumcord.mobile.bridge.Profile

/** What the edit is going to do with the avatar when it is saved. */
sealed interface AvatarChange {
    data object Keep : AvatarChange

    data object Remove : AvatarChange

    /** A new picture, already re-encoded on the phone (see `AvatarImage`). */
    class Set(val image: ByteArray) : AvatarChange
}

/** Why the edit profile screen shows an error. */
enum class ProfileError {
    /** The chosen picture could not be read or is no picture. */
    AvatarUnreadable,

    /** The server refused or the connection failed while saving. */
    SaveFailed,
}

/** The sheet of the avatar: where a new picture comes from, or removing it. */
enum class ProfileSheet { Avatar }

/**
 * The edit profile screen (mockup `20-edit-profile`, FR-5.80): the comment as it is typed and
 * the avatar change, saved together. The name is the user name of the connection and cannot be
 * changed here.
 */
data class ProfileEdit(
    val comment: String,
    /** The comment as it was read; an unchanged comment is not sent again. */
    val initialComment: String,
    val avatar: AvatarChange = AvatarChange.Keep,
    /** The server's limit for a comment, in characters; null until it was read. */
    val limit: UInt? = null,
    val sheet: ProfileSheet? = null,
    val saving: Boolean = false,
    val error: ProfileError? = null,
) {
    val tooLong: Boolean
        get() = limit != null && comment.length.toUInt() > limit

    val commentChanged: Boolean
        get() = comment != initialComment

    val changed: Boolean
        get() = commentChanged || avatar != AvatarChange.Keep

    val canSave: Boolean
        get() = changed && !tooLong && !saving
}

/** The user's own profile on the shown server, as the More tab and the editor show it. */
data class OwnProfile(
    val name: String,
    val comment: String,
    /** The avatar's bytes as the server holds them; null without one. */
    val avatar: ByteArray?,
) {
    /** The first line of the comment, for the More tab. */
    val commentLine: String
        get() = comment.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    // Compared by content, so that reading the same profile again draws nothing new.
    override fun equals(other: Any?): Boolean =
        other is OwnProfile && name == other.name && comment == other.comment && avatar.contentEquals(other.avatar)

    override fun hashCode(): Int = (name.hashCode() * HASH_PRIME + comment.hashCode()) * HASH_PRIME + avatar.contentHashCode()
}

private const val HASH_PRIME = 31

/** The profile as read from the engine, for the user called [name]. */
fun ownProfile(name: String, profile: Profile): OwnProfile =
    OwnProfile(name = name, comment = profile.commentText, avatar = profile.avatar?.bytes)

/** The edit profile screen as a value. */
data class ProfileEditUiState(
    val name: String,
    val edit: ProfileEdit,
    /** The picture shown: the new one, none after removing, or the current one. */
    val avatar: ByteArray?,
) {
    val hasAvatar: Boolean
        get() = avatar != null

    // Compared by content, see OwnProfile.
    override fun equals(other: Any?): Boolean =
        other is ProfileEditUiState && name == other.name && edit == other.edit && avatar.contentEquals(other.avatar)

    override fun hashCode(): Int = (name.hashCode() * HASH_PRIME + edit.hashCode()) * HASH_PRIME + avatar.contentHashCode()
}

fun profileEditUiState(own: OwnProfile, edit: ProfileEdit): ProfileEditUiState = ProfileEditUiState(
    name = own.name,
    edit = edit,
    avatar = when (val avatar = edit.avatar) {
        AvatarChange.Keep -> own.avatar
        AvatarChange.Remove -> null
        is AvatarChange.Set -> avatar.image
    },
)
