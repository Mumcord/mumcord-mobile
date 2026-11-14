// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/** The system's own permission dialog and settings, which belong to other apps. */
class SystemDialog(private val device: UiDevice) {
    /**
     * Taps a button of the system's permission dialog and waits until the dialog is gone;
     * [permission] names it in a failure, which also carries what was on the screen.
     */
    fun answer(button: Pattern, permission: String) {
        val selector = By.res(button)
        if (!device.wait(Until.hasObject(selector), TIMEOUT_MILLIS)) {
            throw AssertionError("The system did not ask for $permission. On the screen:\n${windowDump()}")
        }
        // What the system asks is not the app's text; it is logged for the device checklist.
        device.findObject(By.res(MESSAGE))?.let { Log.i(LOG_TAG, "System dialog: ${it.text}") }
        repeat(TAP_ATTEMPTS) { attempt ->
            // The dialog slides in, and a slow device shows it before it takes touches: a tap
            // at where the button was a moment ago can miss. So the button is looked up anew
            // for every tap, once the screen has come to rest.
            device.waitForIdle(IDLE_MILLIS)
            // Gone meanwhile: an earlier tap was taken after all.
            val found = device.findObject(selector) ?: return
            try {
                found.click()
            } catch (_: StaleObjectException) {
                // The dialog changed between finding and tapping; the next round looks again.
            }
            if (device.wait(Until.gone(selector), CLOSE_MILLIS)) return
            Log.w(LOG_TAG, "The system dialog is still open after tap ${attempt + 1}")
        }
        throw AssertionError(
            "The system dialog for $permission did not close after $TAP_ATTEMPTS taps. On the screen:\n${windowDump()}",
        )
    }

    /** Whether the app's page in the system settings came to the front. */
    fun settingsOpened(): Boolean = device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE)), TIMEOUT_MILLIS)

    /** Every window on the screen as the accessibility tree, to diagnose a failure from a log. */
    private fun windowDump(): String = try {
        ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())
    } catch (error: java.io.IOException) {
        "(the screen could not be read: $error)"
    }

    companion object {
        /** Generous: the hosted CI emulator takes several seconds to bring up another app's window. */
        const val TIMEOUT_MILLIS = 30_000L

        /** How long the dialog may take to close after one tap before the tap counts as missed. */
        private const val CLOSE_MILLIS = 10_000L
        private const val IDLE_MILLIS = 2_000L
        private const val TAP_ATTEMPTS = 3
        private const val LOG_TAG = "MumcordE2E"
        private const val SETTINGS_PACKAGE = "com.android.settings"

        // The dialog's package differs between Android builds; its view names do not.
        val ALLOW: Pattern = Pattern.compile(".*:id/permission_allow_button")

        /** The microphone can be allowed "while using the app" instead of once and for all. */
        val ALLOW_WHILE_IN_USE: Pattern = Pattern.compile(".*:id/permission_allow_foreground_only_button")
        val DENY: Pattern = Pattern.compile(".*:id/permission_deny(_and_dont_ask_again)?_button")
        private val MESSAGE: Pattern = Pattern.compile(".*:id/permission_message")
    }
}
