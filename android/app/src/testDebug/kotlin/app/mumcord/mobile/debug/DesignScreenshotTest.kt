// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.theme.MumcordTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the channels reference screen and the component gallery in both themes, at the size of
 * the mockups (390 x 844 dp at two pixels per dp, so the files can be laid over the mockup
 * images).
 *
 * What happens with the pictures depends on how the tests are started (see app/build.gradle.kts):
 * a normal test run only draws them into build/outputs/screenshots, `recordScreenshots` writes
 * them to docs/images/screenshots, and `verifyScreenshots` compares them with the files there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val mode = System.getProperty("mumcord.screenshots.mode") ?: "render"
    private val goldens = File(checkNotNull(System.getProperty("mumcord.screenshots.goldens")))
    private val output = File(checkNotNull(System.getProperty("mumcord.screenshots.output")))

    private fun capture(name: String, scale: Double = 1.0, content: @Composable () -> Unit) {
        composeRule.setContent {
            Box(Modifier.testTag(SHOT)) { content() }
        }
        val verify = mode == "verify"
        val target = if (mode == "render") File(output, "$name.png") else File(goldens, "$name.png")
        composeRule.onNodeWithTag(SHOT).captureRoboImage(
            file = target,
            roborazziOptions = RoborazziOptions(
                taskType = if (verify) RoborazziTaskType.Verify else RoborazziTaskType.Record,
                compareOptions = RoborazziOptions.CompareOptions(
                    outputDirectoryPath = output.path,
                    // Text edges are drawn slightly differently on other machines.
                    changeThreshold = CHANGE_THRESHOLD,
                ),
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = scale),
            ),
        )
    }

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
        const val SHOT = "screenshot"
        const val PHONE = "w390dp-h844dp-xhdpi"

        // Tall enough for the whole gallery; the picture is cut to the content's height.
        const val TALL = "w390dp-h4200dp-xhdpi"
        const val CHANGE_THRESHOLD = 0.02f

        // The gallery is only looked at, never laid over a mockup: half size keeps the files small.
        const val GALLERY_SCALE = 0.5
    }
}
