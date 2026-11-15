// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.profile

import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.commentLimit
import app.mumcord.mobile.session.personProfile
import app.mumcord.mobile.session.setOwnAvatar
import app.mumcord.mobile.session.setOwnComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The user's own profile on the shown server (FR-5.80): read whenever the server says it may
 * have changed, and edited on the edit profile screen. [onClosed] leaves that screen.
 */
class ProfileController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    private val mutableOwn = MutableStateFlow<OwnProfile?>(null)

    /** Null while no server is shown or the profile has not been read yet. */
    val own: StateFlow<OwnProfile?> = mutableOwn.asStateFlow()

    private val mutableEdit = MutableStateFlow<ProfileEdit?>(null)

    /** The open edit, or null while the editor is closed. */
    val edit: StateFlow<ProfileEdit?> = mutableEdit.asStateFlow()

    init {
        scope.launch {
            repository.state
                .map { state ->
                    val server = state.server?.takeIf { state.showsServer }
                    server?.let { ProfileKey(it.selfSession, it.people[it.selfSession]?.name.orEmpty(), state.ownProfileChanges) }
                }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) {
                        mutableOwn.value = null
                        mutableEdit.value = null
                        return@collectLatest
                    }
                    when (val read = repository.personProfile(key.session)) {
                        is Outcome.Ok -> mutableOwn.value = ownProfile(key.name, read.value)
                        // The profile stays as it was; the next change reads it again.
                        is Outcome.Failed -> Unit
                    }
                }
        }
    }

    private data class ProfileKey(val session: UInt, val name: String, val changes: Int)

    /** Opens the editor on the profile as it is and reads the server's limit for the comment. */
    fun open() {
        val own = mutableOwn.value
        mutableEdit.value = ProfileEdit(comment = own?.comment.orEmpty(), initialComment = own?.comment.orEmpty())
        scope.launch {
            val limit = repository.commentLimit() ?: return@launch
            mutableEdit.update { it?.copy(limit = limit) }
        }
    }

    /** Leaves the editor; what was not saved is dropped. */
    fun close() = onClosed()

    /** Drops the open edit; called when the editor is left. */
    fun discard() {
        mutableEdit.value = null
    }

    fun changeComment(comment: String) = mutableEdit.update { it?.copy(comment = comment, error = null) }

    fun openAvatarSheet() = mutableEdit.update { it?.copy(sheet = ProfileSheet.Avatar) }

    fun closeSheet() = mutableEdit.update { it?.copy(sheet = null) }

    /** A new picture, already re-encoded without its metadata on the phone. */
    fun pickAvatar(image: ByteArray) = mutableEdit.update { it?.copy(avatar = AvatarChange.Set(image), sheet = null, error = null) }

    fun avatarUnreadable() = mutableEdit.update { it?.copy(sheet = null, error = ProfileError.AvatarUnreadable) }

    /** Shows no picture: removes the server's one; with none there, only the new one is dropped. */
    fun removeAvatar() = mutableEdit.update { edit ->
        val serverHasOne = mutableOwn.value?.avatar != null
        val avatar = if (serverHasOne) AvatarChange.Remove else AvatarChange.Keep
        edit?.copy(avatar = avatar, sheet = null, error = null)
    }

    /** Saves what changed: the avatar first, then the comment; the editor closes when both are in. */
    fun save() {
        val edit = mutableEdit.value ?: return
        if (!edit.canSave) return
        mutableEdit.value = edit.copy(saving = true, error = null)
        scope.launch {
            val avatar = when (val change = edit.avatar) {
                AvatarChange.Keep -> Outcome.Ok(Unit)
                AvatarChange.Remove -> repository.setOwnAvatar(null)
                is AvatarChange.Set -> repository.setOwnAvatar(change.image)
            }
            val saved = if (avatar is Outcome.Ok && edit.commentChanged) repository.setOwnComment(edit.comment) else avatar
            when (saved) {
                // Only while the editor is still open: the user may have left it meanwhile.
                is Outcome.Ok -> if (mutableEdit.value != null) close()
                is Outcome.Failed -> mutableEdit.update { open ->
                    // A saved avatar is not sent again with the next try.
                    val left = if (avatar is Outcome.Ok) AvatarChange.Keep else open?.avatar ?: AvatarChange.Keep
                    open?.copy(saving = false, avatar = left, error = ProfileError.SaveFailed)
                }
            }
        }
    }
}
