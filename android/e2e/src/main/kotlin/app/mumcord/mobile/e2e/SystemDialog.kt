// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import java.util.regex.Pattern

/** The system's own permission dialog and settings, which belong to other apps. */
class SystemDialog(private val device: UiDevice) {
    /** Taps a button of the system's permission dialog; [permission] names it in a failure. */
    fun answer(button: Pattern, permission: String) {
        val found = device.wait(Until.findObject(By.res(button)), TIMEOUT_MILLIS)
            ?: throw AssertionError("The system did not ask for $permission")
        // What the system asks is not the app's text; it is logged for the device checklist.
        device.findObject(By.res(MESSAGE))?.let { Log.i(LOG_TAG, "System dialog: ${it.text}") }
        found.click()
        assertTrue("The system dialog did not close", device.wait(Until.gone(By.res(button)), TIMEOUT_MILLIS))
    }

    /** Whether the app's page in the system settings came to the front. */
    fun settingsOpened(): Boolean = device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE)), TIMEOUT_MILLIS)

    companion object {
        const val TIMEOUT_MILLIS = 10_000L
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
