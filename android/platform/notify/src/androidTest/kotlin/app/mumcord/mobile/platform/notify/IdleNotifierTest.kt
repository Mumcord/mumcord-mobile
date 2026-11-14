// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The two notifications of "disconnect when alone" on a real system, and the warning's button. */
@RunWith(AndroidJUnit4::class)
class IdleNotifierTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val stayed = CountDownLatch(1)
    private val notifier = AndroidIdleNotifier(
        context,
        open = PendingIntent.getBroadcast(context, 0, Intent("test").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE),
        onStay = stayed::countDown,
    )

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertTrue("The test needs the phone to show its notifications", manager.areNotificationsEnabled())
    }

    @After
    fun tearDown() {
        notifier.hideWarning()
        notifier.hideDisconnected()
    }

    /** The notification with [title], or null after waiting in vain. */
    private fun shown(title: String): StatusBarNotification? {
        // The system takes a moment to list a notification that was just posted.
        repeat(TRIES) {
            manager.activeNotifications.firstOrNull { it.title() == title }?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun gone(title: String): Boolean {
        repeat(TRIES) {
            if (manager.activeNotifications.none { it.title() == title }) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    private fun StatusBarNotification.title(): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun theWarningSaysOneMinuteAndItsButtonKeepsTheSession() {
        notifier.showWarning()

        val warning = checkNotNull(shown(WARNING)) { "the warning is not shown" }
        assertEquals("session_notices", warning.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("session_notices").importance)
        val action = warning.notification.actions.single()
        assertEquals("Stay connected", action.title.toString())

        action.actionIntent.send()

        assertTrue("The button did not reach the app", stayed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        notifier.hideWarning()
        assertTrue(gone(WARNING))
    }

    @Test
    fun afterDisconnectingANotificationSaysSoUntilItIsTakenAway() {
        notifier.showDisconnected()

        val disconnected = checkNotNull(shown(DISCONNECTED)) { "the notification is not shown" }
        assertEquals(
            "You were alone in a channel for 30 minutes.",
            disconnected.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertTrue("It has no buttons", disconnected.notification.actions.isNullOrEmpty())

        notifier.hideDisconnected()
        assertTrue(gone(DISCONNECTED))
    }

    private companion object {
        const val WARNING = "Disconnecting in 1 minute"
        const val DISCONNECTED = "Disconnected"
        const val TRIES = 50
        const val POLL_MILLIS = 100L
        const val TIMEOUT_SECONDS = 10L
    }
}
