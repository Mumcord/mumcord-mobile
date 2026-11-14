// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.ServerState

const val MOCKUP_ROOT = 0u
const val MOCKUP_LOBBY = 1u
const val MOCKUP_GAMING = 2u
const val MOCKUP_MUSIC = 3u
const val MOCKUP_AFK = 4u
const val MOCKUP_BEN = 2u
const val MOCKUP_LEA = 3u
const val MOCKUP_JONAS = 5u

/**
 * The server of mockup `02-channels`: Nordlicht with Lobby (Anna, Ben talking, Lea = the user),
 * Gaming (Mira sharing her screen, Jonas muted and deafened), the locked Music and AFK.
 */
fun mockupServer(): ServerState = serverState(
    selfSession = MOCKUP_LEA,
    channels = listOf(
        channel(MOCKUP_ROOT, "Nordlicht"),
        channel(MOCKUP_LOBBY, "Lobby", parentId = MOCKUP_ROOT, position = 0),
        channel(MOCKUP_GAMING, "Gaming", parentId = MOCKUP_ROOT, position = 1),
        channel(MOCKUP_MUSIC, "Music", parentId = MOCKUP_ROOT, position = 2).copy(restricted = true, canEnter = false),
        channel(MOCKUP_AFK, "AFK", parentId = MOCKUP_ROOT, position = 3),
    ),
    people = listOf(
        person(1u, "Anna", channelId = MOCKUP_LOBBY),
        person(MOCKUP_BEN, "Ben", channelId = MOCKUP_LOBBY).copy(talking = true),
        person(MOCKUP_LEA, "Lea", channelId = MOCKUP_LOBBY, isSelf = true),
        person(4u, "Mira", channelId = MOCKUP_GAMING).copy(streaming = true),
        person(MOCKUP_JONAS, "Jonas", channelId = MOCKUP_GAMING).copy(selfMuted = true, selfDeafened = true),
    ),
)

const val MOCKUP_ANNA = 1u
const val MOCKUP_MIRA = 4u

/**
 * The server of mockup `03-voice-expanded`: everybody is in the Lobby. Anna, Ben talking, Mira,
 * Jonas muted and Lea, the user.
 */
fun mockupVoiceServer(): ServerState = mockupServer().let { server ->
    server.copy(
        people = server.people.map {
            it.copy(channelId = MOCKUP_LOBBY, streaming = false, selfDeafened = false)
        },
    )
}
