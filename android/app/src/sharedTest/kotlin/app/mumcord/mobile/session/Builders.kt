// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.ChannelPermissions
import app.mumcord.mobile.bridge.ChatId
import app.mumcord.mobile.bridge.ChatMessage
import app.mumcord.mobile.bridge.ContentBlock
import app.mumcord.mobile.bridge.MessageContent
import app.mumcord.mobile.bridge.ReplyQuote
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.SavedServer
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.bridge.Span

// Builders with defaults, so a new field in the bridge types does not touch every test.

fun channel(
    id: UInt,
    name: String,
    parentId: UInt? = null,
    position: Int = 0,
) = Channel(
    id = id,
    parentId = parentId,
    name = name,
    position = position,
    hasDescription = false,
    maxPeople = null,
    temporary = false,
    restricted = false,
    canEnter = true,
    permissions = ChannelPermissions(
        speak = false,
        whisper = false,
        textMessage = false,
        movePeople = false,
        muteDeafen = false,
        kick = false,
        ban = false,
    ),
)

fun person(
    session: UInt,
    name: String,
    channelId: UInt = 0u,
    isSelf: Boolean = false,
) = Person(
    session = session,
    channelId = channelId,
    name = name,
    isSelf = isSelf,
    talking = false,
    selfMuted = false,
    selfDeafened = false,
    serverMuted = false,
    serverDeafened = false,
    suppressed = false,
    localVolume = 100u,
    locallyMuted = false,
    streaming = false,
    camera = false,
)

fun serverState(
    selfSession: UInt,
    channels: List<Channel>,
    people: List<Person>,
) = ServerState(selfSession = selfSession, channels = channels, people = people)

fun savedServer(
    id: Long,
    name: String,
    address: String = "${name.lowercase().replace(" ", "")}.example",
    port: UShort = 64738u,
    username: String = "Me",
    hasPassword: Boolean = false,
) = SavedServer(
    id = id,
    name = name,
    address = address,
    port = port,
    username = username,
    hasPassword = hasPassword,
    identityId = null,
)

fun span(text: String) = Span(
    text = text,
    bold = false,
    italic = false,
    underline = false,
    strikethrough = false,
    code = false,
    link = null,
    mention = false,
)

fun messageContent(
    text: String = "Hello",
    blocks: List<ContentBlock> = listOf(ContentBlock.Paragraph(listOf(span(text)))),
) = MessageContent(reply = null, blocks = blocks, truncated = false)

fun chatMessage(
    id: String = "m1",
    chat: ChatId = ChatId.Channel(0u),
    senderName: String = "Alice",
    senderSession: UInt? = null,
    own: Boolean = false,
    sentAtMs: Long = 0,
    content: MessageContent = messageContent(),
) = ChatMessage(
    id = id,
    chat = chat,
    senderName = senderName,
    senderSession = senderSession,
    senderCertHash = null,
    own = own,
    sentAtMs = sentAtMs,
    blocked = false,
    mentionsMe = false,
    content = content,
    quote = ReplyQuote(name = senderName, snippet = ""),
)
