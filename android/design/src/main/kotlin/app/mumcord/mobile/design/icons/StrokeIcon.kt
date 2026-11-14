// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Builds one icon of design/icons.json: unfilled paths with round caps and joins. The stroke is
 * black and gets its real colour from the tint where the icon is drawn.
 */
internal fun strokeIcon(name: String, viewBox: Float, strokeWidth: Float, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = viewBox.dp,
        defaultHeight = viewBox.dp,
        viewportWidth = viewBox,
        viewportHeight = viewBox,
    )
    for (path in paths) {
        builder.addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

/** The same icon with its outline filled in, for the few places the mockups fill an icon (a star). */
internal fun ImageVector.filled(): ImageVector {
    val builder = ImageVector.Builder(
        name = "$name-filled",
        defaultWidth = defaultWidth,
        defaultHeight = defaultHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    )
    for (node in root) {
        if (node is VectorPath) {
            builder.addPath(
                pathData = node.pathData,
                fill = SolidColor(Color.Black),
                stroke = node.stroke,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
            )
        }
    }
    return builder.build()
}
