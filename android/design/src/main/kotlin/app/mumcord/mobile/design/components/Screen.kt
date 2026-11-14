// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.theme.ellipticalGlow
import java.util.Random

private const val GRAIN_TILE = 160
private const val GRAIN_SEED = 7L

// The desktop app's grain is an SVG filter that outputs 50 % grey in linear light, which is
// this value in sRGB.
private const val GRAIN_GREY = 0xBCBCBC

/**
 * Film grain like the desktop app: grey noise of varying opacity. The seed is fixed so that
 * every start and every screenshot shows the same pattern.
 */
private fun grainBitmap(): Bitmap {
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE) {
        val alpha = (128 + random.nextGaussian() * 48).toInt().coerceIn(0, 255)
        (alpha shl 24) or GRAIN_GREY
    }
    return Bitmap.createBitmap(pixels, GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
}

/**
 * The background every screen sits on (`.screen` in the mockups): the sidebar colour with two
 * soft glows in the top corners and film grain over everything, bars included. Children are laid
 * out in a column; bars handle the system bar insets themselves.
 */
@Composable
fun MumcordScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MumcordTheme.colors
    val grainOpacity = MumcordTheme.opacities.grain
    val grain = remember { ShaderBrush(ImageShader(grainBitmap().asImageBitmap(), TileMode.Repeated, TileMode.Repeated)) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(colors.bgSidebar)
            .drawWithContent {
                // 60rem x 40rem at 85 % / -10 % and 50rem x 36rem at -10 % / 10 %, as in tokens.css.
                ellipticalGlow(
                    color = colors.glowBackdrop1,
                    center = Offset(size.width * 0.85f, size.height * -0.1f),
                    radiusX = 960.dp.toPx(),
                    radiusY = 640.dp.toPx(),
                )
                ellipticalGlow(
                    color = colors.glowBackdrop2,
                    center = Offset(size.width * -0.1f, size.height * 0.1f),
                    radiusX = 800.dp.toPx(),
                    radiusY = 576.dp.toPx(),
                )
                drawContent()
                drawRect(brush = grain, alpha = grainOpacity)
            },
        content = content,
    )
}

@Preview(name = "Screen background dark")
@Composable
private fun ScreenDarkPreview() {
    MumcordTheme(darkTheme = true) { MumcordScreen {} }
}

@Preview(name = "Screen background light")
@Composable
private fun ScreenLightPreview() {
    MumcordTheme(darkTheme = false) { MumcordScreen {} }
}
