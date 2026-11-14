// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.shadows.ShadowLooper
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.ui.chat.ChatClock
import app.mumcord.mobile.ui.chat.ChatController
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.chat.ChatImageSystem
import app.mumcord.mobile.ui.connected.ChatEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** The size of the mockups: 390 x 844 dp at two pixels per dp, so the files can be laid over them. */
const val PHONE_QUALIFIERS = "w390dp-h844dp-xhdpi"

private const val SHOT = "screenshot"

// Text edges are drawn slightly differently on other machines.
private const val CHANGE_THRESHOLD = 0.02f

/**
 * Draws [content] and handles the picture as the way the tests were started asks for (see
 * app/build.gradle.kts): a normal test run only draws it into build/outputs/screenshots,
 * `recordScreenshots` writes it to docs/images/screenshots, and `verifyScreenshots` compares it
 * with the file there.
 */
fun ComposeContentTestRule.captureScreenshot(name: String, scale: Double = 1.0, content: @Composable () -> Unit) {
    val mode = System.getProperty("mumcord.screenshots.mode") ?: "render"
    val goldens = File(checkNotNull(System.getProperty("mumcord.screenshots.goldens")))
    val output = File(checkNotNull(System.getProperty("mumcord.screenshots.output")))
    setContent {
        Box(Modifier.testTag(SHOT)) { content() }
    }
    // The picture is copied from the window. In a test run with many screenshots the window of
    // some screens was never drawn and the picture came out blank; asking for a redraw and
    // letting the main thread finish it makes every picture reliable.
    val view = (onNodeWithTag(SHOT).fetchSemanticsNode().root as ViewRootForTest).view
    runOnUiThread {
        view.rootView.invalidate()
        view.invalidate()
    }
    waitForIdle()
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    val verify = mode == "verify"
    val target = if (mode == "render") File(output, "$name.png") else File(goldens, "$name.png")
    onNodeWithTag(SHOT).captureRoboImage(
        file = target,
        roborazziOptions = RoborazziOptions(
            taskType = if (verify) RoborazziTaskType.Verify else RoborazziTaskType.Record,
            compareOptions = RoborazziOptions.CompareOptions(
                outputDirectoryPath = output.path,
                changeThreshold = CHANGE_THRESHOLD,
            ),
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = scale),
        ),
    )
}

/** A chat controller for screens that show no chat: nothing is open and nothing is asked for. */
fun screenshotChatController(repository: SessionRepository, scope: CoroutineScope): ChatController =
    ChatController(repository.chat, openChat = flowOf(null), shown = flowOf(false), scope = scope)

/** The day of the mockups' chat, in UTC with a 24 hour clock, and pictures drawn by [loader]. */
fun screenshotChatEnvironment(loader: ChatImageLoader = ChatImageLoader { _, _, _ -> null }): ChatEnvironment =
    ChatEnvironment(
        loader = loader,
        images = ChatImageSystem(pickPhoto = {}, takePhoto = {}),
        clock = ChatClock(ZoneOffset.UTC, MOCKUP_DAY, use24Hour = true, locale = Locale.US),
    )

val MOCKUP_DAY: LocalDate = LocalDate.of(2026, 5, 4)
