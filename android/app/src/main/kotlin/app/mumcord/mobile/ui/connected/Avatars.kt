// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

private const val AVATAR_COLOURS = 8

/** The first [count] letters or digits of a name in upper case, "?" when it has none. */
fun String.initials(count: Int): String =
    filter { it.isLetterOrDigit() }.take(count).uppercase().ifEmpty { "?" }

/** One of the eight avatar colours, always the same for the same name. */
fun avatarSlot(name: String): Int = Math.floorMod(name.hashCode(), AVATAR_COLOURS) + 1
