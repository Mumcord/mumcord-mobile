// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** A CSS box shadow from design/tokens.json: vertical offset, blur radius, spread and colour. */
@Immutable
data class ShadowToken(val y: Dp, val blur: Dp, val spread: Dp, val color: Color)

/**
 * A text style from design/tokens.json. [size] is in sp so that text follows the system font
 * size; [lineHeight] is a multiple of the size and [letterSpacing] is in em.
 */
@Immutable
data class TextToken(
    val display: Boolean,
    val size: Float,
    val weight: Int,
    val lineHeight: Float,
    val letterSpacing: Float,
)
