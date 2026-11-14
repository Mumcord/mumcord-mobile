// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.bridge.TalkMode
import app.mumcord.mobile.bridge.WhisperTarget
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.MOCKUP_ANNA
import app.mumcord.mobile.session.MOCKUP_BEN
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LEA
import app.mumcord.mobile.session.MOCKUP_LOBBY
import app.mumcord.mobile.session.ServerModel
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.mockupServer
import app.mumcord.mobile.session.mockupVoiceServer
import app.mumcord.mobile.session.person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice bar and the full voice view as a value. */
class VoiceStateTest {
    private fun session(
        change: (Person) -> Person = { it },
        phase: ConnectionPhase = ConnectionPhase.Connected,
        talkMode: TalkMode = TalkMode.VOICE_ACTIVITY,
    ): SessionState {
        val server = mockupVoiceServer()
        return SessionState(
            phase = phase,
            talkMode = talkMode,
            server = ServerModel(
                selfSession = server.selfSession,
                channels = server.channels.associateBy { it.id },
                people = server.people.map { if (it.isSelf) change(it) else it }.associateBy { it.session },
            ),
        )
    }

    private fun voice(
        session: SessionState = session(),
        local: ConnectedLocal = ConnectedLocal(),
        microphone: MicrophoneNotice = MicrophoneNotice.None,
    ) = connectedUiState(session, local, "Nordlicht", microphone)!!.voice!!

    @Test
    fun theBarNamesTheOwnChannelTheServerAndWhetherTheUserTalks() {
        val quiet = voice()
        assertEquals("Lobby", quiet.channelName)
        assertEquals("Nordlicht", quiet.serverName)
        assertTrue(quiet.connected)
        assertFalse(quiet.talking)
        assertFalse(quiet.open)
        assertTrue(voice(session({ it.copy(talking = true) })).talking)
    }

    @Test
    fun whileTheConnectionIsRestoredTheBarStaysAndSaysSo() {
        assertFalse(voice(session(phase = ConnectionPhase.Reconnecting(1, 5))).connected)
    }

    @Test
    fun withoutTheOwnEntryThereIsNoVoiceBar() {
        val session = session().let { it.copy(server = it.server!!.copy(people = it.server!!.people - MOCKUP_LEA)) }
        assertNull(connectedUiState(session, ConnectedLocal(), "Nordlicht")!!.voice)
    }

    @Test
    fun theMuteButtonFollowsTheOwnMuteAndDeafenImpliesMute() {
        assertEquals(MuteControl.Unmuted, voice().mute)
        assertEquals(MuteControl.Muted, voice(session({ it.copy(selfMuted = true) })).mute)
        val deafened = voice(session({ it.copy(selfDeafened = true) }))
        assertEquals(MuteControl.Muted, deafened.mute)
        assertTrue(deafened.deafened)
        assertTrue(voice(session({ it.copy(serverDeafened = true) })).deafened)
    }

    @Test
    fun aMuteByTheServerOrTheChannelComesBeforeEverythingElse() {
        val muted = session({ it.copy(serverMuted = true, selfMuted = true) })
        assertEquals(MuteControl.ServerMuted, voice(muted, microphone = MicrophoneNotice.Blocked).mute)
        assertEquals(MuteControl.ServerMuted, voice(session({ it.copy(suppressed = true) })).mute)
    }

    @Test
    fun aMissingMicrophoneComesBeforeTheOwnMute() {
        val muted = session({ it.copy(selfMuted = true) })
        assertEquals(MuteControl.NoMicrophone, voice(muted, microphone = MicrophoneNotice.Denied).mute)
        assertEquals(MuteControl.NoMicrophone, voice(microphone = MicrophoneNotice.Blocked).mute)
        assertEquals("The question itself is no refusal yet", MuteControl.Unmuted, voice(microphone = MicrophoneNotice.Explain).mute)
    }

    @Test
    fun theTalkModeAndTheHeldButtonReachTheView() {
        val held = voice(session(talkMode = TalkMode.PUSH_TO_TALK), ConnectedLocal(voiceOpen = true, talkHeld = true))
        assertEquals(TalkMode.PUSH_TO_TALK, held.talkMode)
        assertTrue(held.talkHeld)
        assertTrue(held.open)
        assertEquals(TalkMode.VOICE_ACTIVITY, voice().talkMode)
    }

    @Test
    fun thePeopleOfTheOwnChannelStandInRowsOfThreeInTheOrderTheyCameWithTheUserLast() {
        val rows = voice().tileRows
        assertEquals(listOf(listOf("Anna", "Ben", "Mira"), listOf("Jonas", "Lea")), rows.map { row -> row.map { it.name } })
        val tiles = rows.flatten().associateBy { it.session }
        assertTrue(tiles.getValue(MOCKUP_BEN).talking)
        assertFalse(tiles.getValue(MOCKUP_ANNA).talking)
        assertTrue(tiles.getValue(MOCKUP_JONAS).muted)
        assertFalse(tiles.getValue(MOCKUP_ANNA).muted)
        assertTrue(tiles.getValue(MOCKUP_LEA).isSelf)
    }

    @Test
    fun peopleOfOtherChannelsAreNotInTheView() {
        val server = mockupServer()
        val names = voiceTileRows(server.people, MOCKUP_LOBBY).flatten().map { it.name }
        assertEquals(listOf("Anna", "Ben", "Lea"), names)
        assertEquals(emptyList<List<VoiceTile>>(), voiceTileRows(emptyList(), MOCKUP_LOBBY))
    }

    @Test
    fun everyKindOfMuteShowsTheBadge() {
        val people = listOf(
            person(1u, "a").copy(selfMuted = true),
            person(2u, "b").copy(selfDeafened = true),
            person(3u, "c").copy(serverMuted = true),
            person(4u, "d").copy(suppressed = true),
            person(5u, "e").copy(locallyMuted = true),
        )
        assertEquals(listOf(true, true, true, true, false), voiceTileRows(people, 0u).flatten().map { it.muted })
    }

    @Test
    fun theWhisperBarNamesThePersonOrTheChannelAndGoesWhenTheyAreGone() {
        val toPerson = voice(local = ConnectedLocal(whisperTarget = WhisperTarget.Person(MOCKUP_JONAS), whisperHeld = true))
        assertEquals(WhisperBarState("Jonas", shout = false, held = true), toPerson.whisper)
        val toChannel = voice(local = ConnectedLocal(whisperTarget = WhisperTarget.Channel(MOCKUP_GAMING)))
        assertEquals(WhisperBarState("Gaming", shout = true, held = false), toChannel.whisper)
        assertNull(voice().whisper)
        assertNull(voice(local = ConnectedLocal(whisperTarget = WhisperTarget.Person(99u))).whisper)
    }
}
