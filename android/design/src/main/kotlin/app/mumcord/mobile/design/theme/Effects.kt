// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.theme

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import app.mumcord.mobile.design.tokens.ShadowToken

// A CSS blur radius is twice the standard deviation of the blur; Android's blur mask filter
// derives its standard deviation from the radius with this factor and offset.
private const val BLUR_SIGMA_SCALE = 0.57735f
private const val BLUR_SIGMA_OFFSET = 0.5f

/** Draws a CSS-style box shadow of the given [shape] behind the element. */
fun Modifier.tokenShadow(token: ShadowToken, shape: Shape): Modifier = drawBehind {
    val spread = token.spread.toPx()
    val shadowSize = Size(size.width + 2 * spread, size.height + 2 * spread)
    if (shadowSize.width <= 0f || shadowSize.height <= 0f) return@drawBehind
    val paint = Paint()
    paint.asFrameworkPaint().apply {
        isAntiAlias = true
        color = token.color.toArgb()
        val sigma = token.blur.toPx() / 2f
        if (sigma > 0f) {
            maskFilter = BlurMaskFilter((sigma - BLUR_SIGMA_OFFSET) / BLUR_SIGMA_SCALE, BlurMaskFilter.Blur.NORMAL)
        }
    }
    val outline = shape.createOutline(shadowSize, layoutDirection, this)
    translate(left = -spread, top = -spread + token.y.toPx()) {
        drawIntoCanvas { it.drawOutline(outline, paint) }
    }
}

/**
 * A CSS `radial-gradient(<radiusX> <radiusY> at <center>, color, transparent 60%)`: an ellipse
 * that fades from [color] in its centre to nothing at 60 % of its radius.
 */
internal fun DrawScope.ellipticalGlow(color: Color, center: Offset, radiusX: Float, radiusY: Float) {
    if (radiusX <= 0f || radiusY <= 0f) return
    val brush = Brush.radialGradient(
        0f to color,
        0.6f to color.copy(alpha = 0f),
        center = center,
        radius = radiusX,
    )
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
        // Large enough to cover the element however the vertical scale stretches it.
        drawRect(brush = brush, topLeft = Offset(-radiusX * 4, -radiusX * 4), size = Size(radiusX * 8 + size.width, radiusX * 8 + size.height))
    }
}
