// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.mumcord.mobile.MumcordApplication
import app.mumcord.mobile.platform.call.SelfVoice
import app.mumcord.mobile.session.selfVoice
import java.util.regex.Pattern

/**
 * The phone around the app: the home and power buttons, the notification shade, the phone
 * call simulated by the emulator, and what the system knows about the app's service and calls.
 */
class PhoneSystem {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)

    init {
        // The call notification counts the seconds, so the screen never comes to rest; without
        // this every step in the shade would first wait ten seconds for it to.
        Configurator.getInstance().waitForIdleTimeout = IDLE_TIMEOUT_MILLIS
    }

    /** Runs a command as the phone's shell user and returns what it printed. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** Turns the screen off; the emulator locks with it. */
    fun lock() {
        device.sleep()
    }

    /** Turns the screen on again and puts the lock screen away, which brings the app back. */
    fun unlock() {
        device.wakeUp()
        shell("wm dismiss-keyguard")
    }

    fun openShade() {
        check(device.openNotification()) { "the notification shade did not open" }
    }

    fun closeShade() {
        device.pressBack()
    }

    /** A view of the open shade with exactly [text], or null after waiting in vain. */
    fun inShade(text: String, timeoutMillis: Long = SystemDialog.TIMEOUT_MILLIS): UiObject2? {
        // The system draws a button's icon into its label and writes labels its own way
        // (capitals, kinds of spaces).
        val label = Pattern.compile(
            "\\W*" + text.split(' ').joinToString("[\\s\\u00A0]+", transform = Pattern::quote),
            Pattern.CASE_INSENSITIVE,
        )
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            (device.findObject(By.text(label)) ?: device.findObject(By.desc(label)))?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    /** The title of the session's notification in the open shade: "<server> · Lobby". */
    fun callNotification(): UiObject2? =
        device.wait(Until.findObject(By.textEndsWith(" · ${AppDriver.LOBBY}")), SystemDialog.TIMEOUT_MILLIS)

    /** What the user is set to on the server, read from the app itself. */
    fun selfVoice(): SelfVoice? {
        val application = instrumentation.targetContext.applicationContext as MumcordApplication
        return selfVoice(application.sessionRepository.state.value)
    }

    /** The foreground service types of the session's service as the system lists them, or null. */
    fun serviceTypes(): Int? {
        val service = shell("dumpsys activity services $APP_PACKAGE/$SERVICE")
        if (!service.contains("isForeground=true")) return null
        return TYPES.find(service)?.groupValues?.get(1)?.toInt(16)
    }

    /**
     * How many entries the phone's recent calls have besides those of the simulated caller
     * (whose calls belong there, and stay on the emulator from earlier runs). The voice
     * session must never add one.
     */
    fun recentCallsOfTheSession(): Int =
        shell("content query --uri content://call_log/calls").lines()
            .count { it.startsWith("Row") && !it.contains("number=$CALLER,") }

    /**
     * Asks e2e/run.sh to act as the phone network: [command] is `call` (somebody calls the
     * phone) or `cancel` (they hang up). Only the host can reach the emulator's console.
     */
    fun network(command: String) {
        Log.i(LOG_TAG, "gsm $command $CALLER")
    }

    /** Accepts the ringing phone call, as the answer key of a headset would. */
    fun answerPhoneCall() {
        shell("input keyevent KEYCODE_CALL")
    }

    /**
     * The state of normal phone calls as the system publishes it: 0 none, 1 ringing, 2 in a
     * call. (The audio mode does not show ringing while a voice session runs: the phone then
     * announces the call with a waiting tone.)
     */
    fun phoneCallState(): Int? =
        CALL_STATE.find(shell("dumpsys telephony.registry"))?.groupValues?.get(1)?.toInt()

    companion object {
        const val APP_PACKAGE = "app.mumcord.mobile"
        const val CALLER = "5550100"
        private const val SERVICE = "app.mumcord.mobile.platform.call.CallSessionService"
        private const val POLL_MILLIS = 100L
        private const val IDLE_TIMEOUT_MILLIS = 500L
        private const val LOG_TAG = "MumcordE2E"
        const val PHONE_RINGING = 1
        const val PHONE_IN_CALL = 2
        private val CALL_STATE = Regex("""mCallState=(\d)""")
        private val TYPES = Regex("""types=0x([0-9a-fA-F]+)""")
    }
}
