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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The message notifications on a real system: what is handed to it, and that they go away. */
@RunWith(AndroidJUnit4::class)
class MessageNotifierTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val opened = mutableListOf<String>()
    private val notifier = AndroidMessageNotifier(context) { key ->
        opened += key
        PendingIntent.getBroadcast(context, 0, Intent("test").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    }

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertTrue("The test needs the phone to show its notifications", manager.areNotificationsEnabled())
    }

    @After
    fun tearDown() = notifier.cancelAll()

    // The system ignores a notification that claims to be from long ago, so the lines are from now.
    private val now = System.currentTimeMillis()

    private fun line(text: String?, at: Long = now) = MessageNotice.Line("Anna", text, at)

    private fun shown(key: String): StatusBarNotification? {
        // The system takes a moment to list a notification that was just posted.
        repeat(50) {
            manager.activeNotifications.firstOrNull { it.tag == key }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun StatusBarNotification.texts(): List<String> =
        Notification.MessagingStyle.Message
            .getMessagesFromBundleArray(notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES))
            .map { it.text.toString() }

    @Test
    fun aMessageIsHandedToTheSystemWithSenderAndTextInItsOwnChannel() {
        notifier.show(MessageNotice("p:anna", "Anna", group = false, lines = listOf(line("are you joining tonight?"))))

        val posted = checkNotNull(shown("p:anna"))
        assertEquals(listOf("are you joining tonight?"), posted.texts())
        assertEquals("Anna", posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("messages", posted.notification.channelId)
        assertEquals(Notification.CATEGORY_MESSAGE, posted.notification.category)
        // Private: the phone's lock-screen setting decides whether the text is readable there.
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.notification.visibility)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("messages").importance)
        assertEquals(listOf("p:anna"), opened)
    }

    @Test
    fun aLineWithoutTextSaysOnlyThatThereIsAMessage() {
        notifier.show(MessageNotice("p:anna", "Anna", group = false, lines = listOf(line(null))))

        assertEquals(listOf(context.getString(R.string.message_new)), checkNotNull(shown("p:anna")).texts())
    }

    @Test
    fun aConversationHasOneNotificationWithAllItsLines() {
        notifier.show(MessageNotice("p:anna", "Anna", group = false, lines = listOf(line("one"))))
        notifier.show(MessageNotice("p:anna", "Anna", group = false, lines = listOf(line("one"), line("two", now + 1))))
        notifier.show(MessageNotice("c:1", "Lobby", group = true, lines = listOf(line("@Lea hello"))))

        assertEquals(listOf("one", "two"), checkNotNull(shown("p:anna")).texts())
        val mention = checkNotNull(shown("c:1"))
        assertEquals("Lobby", mention.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).toString())
        assertEquals(2, manager.activeNotifications.count { it.tag == "p:anna" || it.tag == "c:1" })
    }

    @Test
    fun oneConversationOrAllOfThemCanBeTakenAway() {
        notifier.show(MessageNotice("p:anna", "Anna", group = false, lines = listOf(line("one"))))
        notifier.show(MessageNotice("p:ben", "Ben", group = false, lines = listOf(line("two"))))
        checkNotNull(shown("p:anna"))
        checkNotNull(shown("p:ben"))

        notifier.cancel("p:anna")
        assertTrue(gone("p:anna"))
        assertFalse(gone("p:ben", tries = 5))

        notifier.cancelAll()
        assertTrue(gone("p:ben"))
    }

    private fun gone(key: String, tries: Int = 50): Boolean {
        repeat(tries) {
            if (manager.activeNotifications.none { it.tag == key }) return true
            Thread.sleep(100)
        }
        return false
    }
}
