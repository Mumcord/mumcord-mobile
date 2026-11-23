// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mumcord.mobile.design.R
import app.mumcord.mobile.design.tokens.FontAxes
import app.mumcord.mobile.design.tokens.MumcordTypography
import app.mumcord.mobile.design.tokens.TextToken
import app.mumcord.mobile.design.tokens.mumcordTypography

private val Weights = listOf(400, 500, 600, 700, 800)

/**
 * One family from a variable font file: each weight is the same file with another weight axis
 * value; the other axes are fixed to the values of design/tokens.json.
 */
private fun variableFamily(resId: Int, axes: Map<String, Float>): FontFamily =
    FontFamily(
        Weights.map { weight ->
            val settings = axes.map { FontVariation.Setting(it.key, it.value) } + FontVariation.weight(weight)
            Font(
                resId = resId,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(*settings.toTypedArray()),
            )
        },
    )

/** Instrument Sans: body text. */
val BodyFontFamily: FontFamily = variableFamily(R.font.instrument_sans, FontAxes.body)

/** Bricolage Grotesque: titles. */
val DisplayFontFamily: FontFamily = variableFamily(R.font.bricolage_grotesque, FontAxes.display)

// Lines are centred in their line box and never trimmed, like CSS line-height, so that row
// heights come out the same as in the mockups.
private val CssLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun TextToken.toTextStyle(): TextStyle =
    TextStyle(
        fontFamily = if (display) DisplayFontFamily else BodyFontFamily,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = lineHeight.em,
        letterSpacing = letterSpacing.em,
        lineHeightStyle = CssLineHeight,
    )

internal val MumcordTypographyTokens: MumcordTypography = mumcordTypography { it.toTextStyle() }
