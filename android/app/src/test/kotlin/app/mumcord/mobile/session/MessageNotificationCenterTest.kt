// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.ImageRef
import app.mumcord.mobile.platform.notify.MessageNotice
import app.mumcord.mobile.platform.notify.MessageNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a message becomes a system notification and when the notification goes away again. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageNotificationCenterTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private lateinit var repository: SessionRepository
    private val settings = InMemoryMessageNotificationSettings()
    private var permitted = true
    private val annaChat = ChatId.Person("cert-anna")
    private val benChat = ChatId.Person("cert-ben")
    private val lobby = ChatId.Channel(MOCKUP_LOBBY)

    /** What the system shows: the notices by key, and how often everything was removed. */
    private class Shade : MessageNotifier {
        val shown = LinkedHashMap<String, MessageNotice>()
        var posts = 0
        var clearedAll = 0

        override fun show(notice: MessageNotice) {
            posts++
            shown[notice.key] = notice
        }

        override fun cancel(key: String) {
            shown.remove(key)
        }

        override fun cancelAll() {
            clearedAll++
            shown.clear()
        }
    }

    private val shade = Shade()

    private fun TestScope.connected(): MessageNotificationCenter {
        repository = SessionRepository(
            engineFactory = { listener ->
                FakeEngine(listener, mockupServer(), servers = listOf(savedServer(1, "Nordlicht"))).also { engine = it }
            },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )
        val center = MessageNotificationCenter(repository, shade, settings, { permitted }, backgroundScope, now = { 5_000 })
        backgroundScope.launch {
            repository.loadServers()
            repository.connectServer(1, null, emptyList())
        }
        runCurrent()
        return center
    }

    private fun privateMessage(id: String, text: String, chat: ChatId = annaChat, sender: String = "Anna") =
        chatMessage(id, chat, sender, MOCKUP_ANNA, content = messageContent(text))

    private fun line(text: String?, sender: String = "Anna") = MessageNotice.Line(sender, text, 5_000)

    @Test
    fun aPrivateMessageIsHandedToTheSystemWithSenderAndText() = runTest(dispatcher) {
        connected()

        engine.receive(privateMessage("m1", "are you joining tonight?"))
        runCurrent()

        assertEquals(
            MessageNotice(annaChat.notificationKey(), "Anna", group = false, lines = listOf(line("are you joining tonight?"))),
            shade.shown.values.single(),
        )
    }

    @Test
    fun withShowSenderOnlyTheTextIsLeftOut() = runTest(dispatcher) {
        connected()
        settings.senderOnly = true

        engine.receive(privateMessage("m1", "nobody else should read this"))
        runCurrent()

        val notice = shade.shown.values.single()
        assertEquals("Anna", notice.title)
        assertEquals(listOf(line(null)), notice.lines)
    }

    @Test
    fun aPictureWithoutWordsHasNoText() = runTest(dispatcher) {
        connected()
        val image = ContentBlock.Image(ImageRef.Embedded("i", "image/png", 1u, 1u, 1u, false))

        engine.receive(chatMessage("m1", annaChat, "Anna", MOCKUP_ANNA, content = messageContent(blocks = listOf(image))))
        runCurrent()

        assertEquals(listOf(line(null)), shade.shown.values.single().lines)
    }

    @Test
    fun theMessagesOfAConversationShareOneNotificationAndEachConversationHasItsOwn() = runTest(dispatcher) {
        connected()

        engine.receive(privateMessage("m1", "one"))
        engine.receive(privateMessage("m2", "two"))
        engine.receive(privateMessage("m3", "from Ben", benChat, "Ben"))
        runCurrent()

        assertEquals(setOf(annaChat.notificationKey(), benChat.notificationKey()), shade.shown.keys)
        assertEquals(listOf(line("one"), line("two")), shade.shown[annaChat.notificationKey()]?.lines)
        assertEquals(listOf(line("from Ben", "Ben")), shade.shown[benChat.notificationKey()]?.lines)
    }

    @Test
    fun aNotificationListsOnlyTheLatestMessages() = runTest(dispatcher) {
        connected()

        repeat(9) { engine.receive(privateMessage("m$it", "text $it")) }
        runCurrent()

        assertEquals((3..8).map { "text $it" }, shade.shown.values.single().lines.map { it.text })
    }

    @Test
    fun aMentionInTheChannelChatIsNotifiedUnderTheChannelsNameAndOtherChannelMessagesAreNot() = runTest(dispatcher) {
        connected()

        engine.receive(chatMessage("c1", lobby, "Ben", MOCKUP_BEN, content = messageContent("just chatting")))
        engine.receive(
            chatMessage("c2", lobby, "Ben", MOCKUP_BEN, content = messageContent("@Lea can you host?")).copy(mentionsMe = true),
        )
        runCurrent()

        assertEquals(
            MessageNotice(lobby.notificationKey(), "Lobby", group = true, lines = listOf(line("@Lea can you host?", "Ben"))),
            shade.shown.values.single(),
        )
    }

    @Test
    fun theChatThatIsOnScreenIsNotNotifiedButAnotherOneIsAlsoWhileTheAppIsInFront() = runTest(dispatcher) {
        connected()
        // The conversation with Anna is on screen: the engine reports nothing for it.
        repository.chat.setOpenChat(this, annaChat)

        engine.receive(privateMessage("m1", "seen at once"))
        engine.receive(privateMessage("m2", "from Ben", benChat, "Ben"))
        runCurrent()

        assertEquals(setOf(benChat.notificationKey()), shade.shown.keys)
    }

    @Test
    fun openingTheChatRemovesItsNotification() = runTest(dispatcher) {
        connected()
        engine.receive(privateMessage("m1", "one"))
        engine.receive(privateMessage("m2", "from Ben", benChat, "Ben"))
        runCurrent()

        repository.chat.setOpenChat(this, annaChat)
        runCurrent()

        assertEquals(setOf(benChat.notificationKey()), shade.shown.keys)
    }

    @Test
    fun readingTheChatRemovesItsNotificationAndALaterMessageStartsAFreshOne() = runTest(dispatcher) {
        connected()
        engine.receive(privateMessage("m1", "one"))
        runCurrent()

        repository.chat.markRead(annaChat)
        runCurrent()
        assertTrue(shade.shown.isEmpty())

        engine.receive(privateMessage("m2", "two"))
        runCurrent()
        assertEquals(listOf(line("two")), shade.shown.values.single().lines)
    }

    @Test
    fun withoutThePermissionNothingIsHandedToTheSystem() = runTest(dispatcher) {
        connected()
        permitted = false

        engine.receive(privateMessage("m1", "one"))
        runCurrent()

        assertEquals(0, shade.posts)
        assertEquals("the badge still counts", 1, repository.chat.unread.value[annaChat])
    }

    @Test
    fun disconnectingRemovesAllNotificationsAndNothingIsShownAfterwards() = runTest(dispatcher) {
        connected()
        engine.receive(privateMessage("m1", "one"))
        engine.receive(privateMessage("m2", "from Ben", benChat, "Ben"))
        runCurrent()
        assertEquals(2, shade.shown.size)
        val clearedBefore = shade.clearedAll

        repository.disconnect()
        runCurrent()
        assertTrue(shade.shown.isEmpty())
        assertEquals(clearedBefore + 1, shade.clearedAll)

        // A message that was still on its way when the connection ended.
        val posts = shade.posts
        engine.receive(privateMessage("m3", "too late"))
        runCurrent()
        assertEquals(posts, shade.posts)
        assertTrue(shade.shown.isEmpty())
    }

    @Test
    fun whatAnEarlierRunLeftBehindIsRemovedAtTheStart() = runTest(dispatcher) {
        shade.shown["p:stale"] = MessageNotice("p:stale", "Anna", group = false, lines = listOf(line("old")))
        repository = SessionRepository(
            engineFactory = { listener -> FakeEngine(listener, mockupServer()).also { engine = it } },
            scope = backgroundScope,
            engineDispatcher = dispatcher,
            log = {},
        )

        MessageNotificationCenter(repository, shade, settings, { permitted }, backgroundScope)
        runCurrent()

        assertTrue(shade.shown.isEmpty())
    }

    @Test
    fun aNotificationKeyNamesItsChat() {
        listOf(lobby, annaChat, ChatId.Session(12u)).forEach { chat ->
            assertEquals(chat, chatFromNotificationKey(chat.notificationKey()))
        }
        assertEquals(3, setOf(lobby, annaChat, ChatId.Session(MOCKUP_LOBBY)).map { it.notificationKey() }.toSet().size)
        assertNull(chatFromNotificationKey("x:1"))
        assertNull(chatFromNotificationKey("c:not-a-number"))
        assertNull(chatFromNotificationKey("p:"))
        assertNull(chatFromNotificationKey(""))
    }
}
