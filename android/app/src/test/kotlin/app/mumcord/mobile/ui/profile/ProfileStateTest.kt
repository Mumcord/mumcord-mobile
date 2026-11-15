// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the edit profile screen may save, and what it shows. */
class ProfileStateTest {
    private val edit = ProfileEdit(comment = "Hello", initialComment = "Hello", limit = 10u)

    @Test
    fun nothingChangedCannotBeSaved() {
        assertFalse(edit.changed)
        assertFalse(edit.canSave)
    }

    @Test
    fun aChangedCommentOrAvatarCanBeSaved() {
        assertTrue(edit.copy(comment = "Hi").canSave)
        assertTrue(edit.copy(avatar = AvatarChange.Remove).canSave)
        assertTrue(edit.copy(avatar = AvatarChange.Set(byteArrayOf(1))).canSave)
        assertFalse("Not while saving", edit.copy(comment = "Hi", saving = true).canSave)
    }

    @Test
    fun aCommentLongerThanTheServersLimitCannotBeSaved() {
        val atLimit = edit.copy(comment = "a".repeat(10))
        assertFalse(atLimit.tooLong)
        assertTrue(atLimit.canSave)
        val over = edit.copy(comment = "a".repeat(11))
        assertTrue(over.tooLong)
        assertFalse(over.canSave)
        assertFalse("No limit known yet", edit.copy(comment = "a".repeat(11), limit = null).tooLong)
    }

    @Test
    fun theMoreTabShowsTheFirstLineOfTheComment() {
        assertEquals("Playing late", OwnProfile("Lea", "\n  Playing late \nAsk me", null).commentLine)
        assertEquals("", OwnProfile("Lea", "", null).commentLine)
    }

    @Test
    fun theEditorShowsTheNewTheRemovedOrTheCurrentPicture() {
        val own = OwnProfile("Lea", "", byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), profileEditUiState(own, edit).avatar)
        assertNull(profileEditUiState(own, edit.copy(avatar = AvatarChange.Remove)).avatar)
        assertArrayEquals(byteArrayOf(2), profileEditUiState(own, edit.copy(avatar = AvatarChange.Set(byteArrayOf(2)))).avatar)
    }

    @Test
    fun profilesAreComparedByTheirContent() {
        assertEquals(OwnProfile("Lea", "x", byteArrayOf(1, 2)), OwnProfile("Lea", "x", byteArrayOf(1, 2)))
    }
}
