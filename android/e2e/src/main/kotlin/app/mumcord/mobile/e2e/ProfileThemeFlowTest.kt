// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.ui.AppViewModel
import app.mumcord.mobile.ui.connected.MORE_EDIT_PROFILE_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_THEMES_TEST_TAG
import app.mumcord.mobile.ui.profile.PROFILE_COMMENT_TEST_TAG
import app.mumcord.mobile.ui.profile.PROFILE_SAVE_TEST_TAG
import app.mumcord.mobile.ui.profile.avatarImage
import app.mumcord.mobile.ui.themes.THEMES_APPLY_TEST_TAG
import app.mumcord.mobile.ui.themes.THEMES_CODE_FIELD_TEST_TAG
import app.mumcord.mobile.ui.themes.THEMES_PASTE_TEST_TAG
import app.mumcord.mobile.ui.themes.THEMES_PREVIEW_TEST_TAG
import app.mumcord.mobile.ui.themes.themeModeTestTag
import java.io.File
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

/**
 * The own profile and the themes (FR-5.80, FR-5.81, AC-5.26) in the real app with the real
 * engine: the comment and a camera photo as the avatar reach the test server, where
 * e2e/profile-watch.py (the scenario "profile" of e2e/run.sh) has to see the comment and the
 * avatar without the photo's EXIF data; then a theme code made by the desktop app is pasted and
 * applied, and the app is drawn in exactly the colours the desktop derives from it, in dark,
 * in light, and with "System" following the phone. Expects fresh app data.
 */
class ProfileThemeFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val phone by lazy { PhoneSystem() }

    @Test
    fun theProfileReachesOthersAndADesktopThemeCodeColoursTheApp() {
        app.chooseTalkMode()
        app.addServer()
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        compose.onNodeWithText(TAB_MORE).performClick()
        app.awaitOrFail("the profile on the More tab") { app.exists(MORE_EDIT_PROFILE_TEST_TAG) }
        compose.onNodeWithTag(MORE_EDIT_PROFILE_TEST_TAG).performClick()
        app.awaitOrFail("edit profile") { app.exists(PROFILE_COMMENT_TEST_TAG) }
        compose.onNode(field(PROFILE_COMMENT_TEST_TAG)).performTextReplacement(COMMENT)
        // The system's photo picker cannot be driven by a test: the photo is handed over the
        // way the app's picker hands it, through the same re-encoding.
        val photo = checkNotNull(avatarImage(cameraPhoto())) { "the test photo could not be read" }
        compose.runOnUiThread {
            ViewModelProvider(compose.activity, AppViewModel.Factory)[AppViewModel::class.java].more.profile.pickAvatar(photo)
        }
        compose.onNodeWithTag(PROFILE_SAVE_TEST_TAG).performClick()
        app.awaitOrFail("the saved profile on the More tab") {
            app.exists(MORE_EDIT_PROFILE_TEST_TAG) && !app.exists(PROFILE_SAVE_TEST_TAG)
        }
        app.awaitOrFail("the new comment on the More tab") {
            compose.onAllNodes(hasText(COMMENT)).fetchSemanticsNodes().isNotEmpty()
        }

        // AC-5.26: a theme code made on the desktop.
        compose.onNodeWithTag(MORE_THEMES_TEST_TAG).performClick()
        app.awaitOrFail("the themes screen") { app.exists(THEMES_PASTE_TEST_TAG) }
        compose.onNodeWithTag(themeModeTestTag(ThemeMode.DARK)).performScrollTo().performClick()
        compose.onNodeWithTag(THEMES_PASTE_TEST_TAG).performScrollTo().performClick()
        app.awaitOrFail("the paste sheet") { app.exists(THEMES_CODE_FIELD_TEST_TAG) }
        compose.onNode(field(THEMES_CODE_FIELD_TEST_TAG)).performTextReplacement(DESKTOP_MESSAGE)
        app.awaitOrFail("the preview of the pasted theme") { app.exists(THEMES_PREVIEW_TEST_TAG) }
        compose.onNodeWithTag(THEMES_APPLY_TEST_TAG).performScrollTo().performClick()
        app.awaitOrFail("the paste sheet to close") { !app.exists(THEMES_CODE_FIELD_TEST_TAG) }

        awaitSurface("dark", DESKTOP_DARK_ELEVATED)
        compose.onNodeWithTag(themeModeTestTag(ThemeMode.LIGHT)).performScrollTo().performClick()
        awaitSurface("light", DESKTOP_LIGHT_ELEVATED)

        // System follows the phone's dark mode.
        val before = phone.shell("cmd uimode night").substringAfter(":").trim()
        try {
            compose.onNodeWithTag(themeModeTestTag(ThemeMode.SYSTEM)).performScrollTo().performClick()
            phone.shell("cmd uimode night yes")
            awaitSurface("dark, following the phone", DESKTOP_DARK_ELEVATED)
            phone.shell("cmd uimode night no")
            awaitSurface("light, following the phone", DESKTOP_LIGHT_ELEVATED)
        } finally {
            phone.shell("cmd uimode night ${before.ifEmpty { "auto" }}")
        }

        // Back from the themes to the More tab, where Disconnect is.
        compose.onNodeWithContentDescription(BACK).performClick()
        app.disconnect()
    }

    /**
     * Waits until the "Paste theme code" row, which lies on the theme's raised surface, is drawn
     * in [expected] (`#rrggbb`), give or take the film grain.
     */
    private fun awaitSurface(what: String, expected: Int) {
        var seen: Int? = null
        // The row must be on screen to be drawn; the themes above it may push it down.
        compose.onNodeWithTag(THEMES_PASTE_TEST_TAG).performScrollTo()
        try {
            app.awaitOrFail("the desktop theme's surface in $what") {
                if (!app.exists(THEMES_PASTE_TEST_TAG)) return@awaitOrFail false
                val image = compose.onNodeWithTag(THEMES_PASTE_TEST_TAG).captureToImage().toPixelMap()
                // Left of the row's icon, half way down, only the surface is drawn; the film grain
                // over the whole app is averaged out over a small square.
                val pixel = average(image, image.width / 50, image.height / 2)
                seen = pixel
                close(pixel, expected)
            }
        } catch (e: AssertionError) {
            val last = seen?.let { "#%06x".format(it and RGB) } ?: "nothing"
            throw AssertionError("The surface was $last instead of #%06x".format(expected and RGB), e)
        }
    }

    private fun average(image: PixelMap, centerX: Int, centerY: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (x in centerX - SAMPLE_RADIUS..centerX + SAMPLE_RADIUS) {
            for (y in centerY - SAMPLE_RADIUS..centerY + SAMPLE_RADIUS) {
                val pixel = image[x, y].toArgb()
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        return Color.rgb(red / count, green / count, blue / count)
    }

    private fun close(actual: Int, expected: Int): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= TOLERANCE &&
            abs(Color.green(actual) - Color.green(expected)) <= TOLERANCE &&
            abs(Color.blue(actual) - Color.blue(expected)) <= TOLERANCE

    /** A camera photo as a JPEG with a GPS position in its EXIF data, as a phone writes it. */
    private fun cameraPhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(PHOTO_EDGE, PHOTO_EDGE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0x3e, 0xe3, 0x9e))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "e2e-photo.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,31/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "13/1,24/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    /** The editable text inside the text field tagged [tag] (the tag is on the field's frame). */
    private fun field(tag: String) = hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))

    private companion object {
        /** The comment e2e/run.sh expects the second person to see (PROFILE_COMMENT there). */
        const val COMMENT = "Hello from the phone"
        const val TAB_MORE = "More"
        const val BACK = "Back"

        /**
         * A share message with the theme code the desktop app made for its test vectors
         * (`desktopTheme` in bridge/src/themes/vectors.json), and the raised surface
         * (`--color-bg-elevated`) the desktop derives from it in dark and in light.
         */
        const val DESKTOP_MESSAGE = "🎨 Mumcord theme \"From the desktop\" – mct1.eyJ2IjoiMC44LjAiLCJuIjoiRnJvbSB0aGUgZGVza3RvcCIsImRhcmsiOnsiYmFja2dyb3VuZCI6IiMxMjBkMGMiLCJzdXJmYWNlIjoiIzEzMzYxNyIsInRleHQiOiIjY2VmMGYzIiwiYWNjZW50IjoiI2YzNTIxZCIsInRhbGtpbmciOiIjZTc5M2ViIiwid2FybmluZyI6IiNjYTg3OWQiLCJlcnJvciI6IiM2OWRjZDkifSwibGlnaHQiOnsiYmFja2dyb3VuZCI6IiNmZGZjZmMiLCJzdXJmYWNlIjoiI2RjZjNkZSIsInRleHQiOiIjMTMyODJhIiwiYWNjZW50IjoiI2YyNGIxMyIsInRhbGtpbmciOiIjZDY0NmRkIiwid2FybmluZyI6IiNhYTRiNmEiLCJlcnJvciI6IiMxYjczNzAifX0"
        val DESKTOP_DARK_ELEVATED = Color.rgb(0x13, 0x36, 0x17)
        val DESKTOP_LIGHT_ELEVATED = Color.rgb(0xdc, 0xf3, 0xde)

        // The film grain lays a little grey over every colour; the Mumcord colours are far
        // further away than this.
        const val TOLERANCE = 8
        const val SAMPLE_RADIUS = 4
        const val RGB = 0xFFFFFF
        const val PHOTO_EDGE = 600
        const val JPEG_QUALITY = 90
    }
}
