// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.call

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The session's service and notification on a real system, without an engine: the test says
 * what the session is and counts what the notification's buttons ask for.
 */
@RunWith(AndroidJUnit4::class)
class CallSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val info = MutableStateFlow<CallInfo?>(null)

    private class Presses : CallActions {
        val pressed = mutableListOf<String>()

        override fun toggleMute() {
            pressed += "mute"
        }

        override fun toggleDeafen() {
            pressed += "deafen"
        }

        override fun leave() {
            pressed += "leave"
        }
    }

    private val presses = Presses()

    @After
    fun endTheSession() {
        info.value = null
        scope.cancel()
        device.pressHome()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** The service's foreground types as the system lists them, or null while it is not one. */
    private fun serviceTypes(): Int? {
        val service = shell("dumpsys activity services ${context.packageName}/${CallSessionService::class.java.name}")
        if (!service.contains("isForeground=true")) return null
        return Regex("""types=0x([0-9a-fA-F]+)""").find(service)?.groupValues?.get(1)?.toInt(16)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (!condition()) {
            assertTrue("Timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(100)
        }
    }

    /**
     * A view of the open shade labelled [text]. The system draws a button's icon into its
     * label, so the label may begin with characters that are not letters;
     * it also writes labels its own way (capitals, kinds of spaces).
     */
    private fun inShade(text: String): UiObject2? {
        val label = Pattern.compile(
            "\\W*" + text.split(' ').joinToString("[\\s\\u00A0]+", transform = Pattern::quote),
            Pattern.CASE_INSENSITIVE,
        )
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            (device.findObject(By.text(label)) ?: device.findObject(By.desc(label)))?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    /**
     * Opens the shade and waits for [title] in it. Right after another test's app was closed
     * the system may put the shade away again, so it is opened again if need be.
     */
    private fun openShadeWith(title: String): UiObject2? {
        repeat(OPEN_ATTEMPTS) {
            device.openNotification()
            inShade(title)?.let { return it }
        }
        return null
    }

    private fun tap(label: String, pressed: String) {
        val before = presses.pressed.size
        checkNotNull(inShade(label)) { "the notification has no button \"$label\"" }.click()
        await("the button \"$label\" to reach the app") { presses.pressed.size > before }
        assertEquals(pressed, presses.pressed.last())
    }

    @Test
    fun aSessionIsAForegroundServiceWithACallNotificationThatFollowsItAndEndsWithIt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val content = PendingIntent.getBroadcast(
            context,
            0,
            Intent("app.mumcord.mobile.platform.call.test.OPEN").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        instrumentation.runOnMainSync {
            CallSession(context, info, presses, content, onInterruption = {}, scope = scope, log = {})
        }
        assertNull("No service without a session", serviceTypes())

        info.value = CallInfo("Nordlicht", "Lobby", muted = false, deafened = false)
        await("the foreground service") { serviceTypes() != null }
        // The test runner installs with every permission granted, the microphone among them.
        val microphone =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        assertEquals(foregroundServiceTypes(microphone), serviceTypes())
        assertTrue(serviceTypes()!! and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL != 0)

        assertNotNull("The call is named after server and channel", openShadeWith("Nordlicht · Lobby"))
        tap("Mute", "mute")
        tap("Deafen", "deafen")

        // What the session becomes shows in the notification without a new one being posted.
        info.value = CallInfo("Nordlicht", "Raid", muted = true, deafened = true)
        assertNotNull("The notification follows the channel", inShade("Nordlicht · Raid"))
        assertNotNull(inShade("Unmute"))
        assertNotNull(inShade("Undeafen"))

        tap("Hang up", "leave")
        info.value = null
        await("the service to stop with the session") { serviceTypes() == null }
        assertTrue(
            "The notification goes with the session",
            device.wait(Until.gone(By.text("Nordlicht · Raid")), TIMEOUT_MILLIS),
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val OPEN_ATTEMPTS = 3
    }
}
