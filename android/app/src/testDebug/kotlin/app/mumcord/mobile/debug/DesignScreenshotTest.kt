// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.theme.MumcordTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the channels reference screen and the component gallery in both themes, at the size of
 * the mockups (390 x 844 dp at two pixels per dp, so the files can be laid over the mockup
 * images).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, scale: Double = 1.0, content: @Composable () -> Unit) =
        composeRule.captureScreenshot(name, scale, content)

    @Composable
    private fun ReferenceScreen(dark: Boolean) {
        MumcordTheme(darkTheme = dark) {
            // The status bar and home indicator areas of the mockup's phone.
            ChannelsReferenceScreen(
                onOpenGallery = {},
                topInsets = WindowInsets(top = 47.dp),
                bottomInsets = WindowInsets(bottom = 34.dp),
            )
        }
    }

    @Composable
    private fun Gallery(dark: Boolean) {
        MumcordTheme(darkTheme = dark) {
            GalleryScreen(
                theme = if (dark) GalleryTheme.Dark else GalleryTheme.Light,
                onThemeChange = {},
                onOpenChannels = {},
                scrollable = false,
                topInsets = WindowInsets(),
                bottomInsets = WindowInsets(),
            )
        }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun channelsReferenceDark() = capture("android-channels-reference-dark") { ReferenceScreen(dark = true) }

    @Test
    @Config(qualifiers = PHONE)
    fun channelsReferenceLight() = capture("android-channels-reference-light") { ReferenceScreen(dark = false) }

    @Test
    @Config(qualifiers = TALL)
    fun galleryDark() = capture("android-gallery-dark", GALLERY_SCALE) { Gallery(dark = true) }

    @Test
    @Config(qualifiers = TALL)
    fun galleryLight() = capture("android-gallery-light", GALLERY_SCALE) { Gallery(dark = false) }

    private companion object {
        const val PHONE = PHONE_QUALIFIERS

        // Tall enough for the whole gallery; the picture is cut to the content's height.
        const val TALL = "w390dp-h4800dp-xhdpi"

        // The gallery is only looked at, never laid over a mockup: half size keeps the files small.
        const val GALLERY_SCALE = 0.5
    }
}
