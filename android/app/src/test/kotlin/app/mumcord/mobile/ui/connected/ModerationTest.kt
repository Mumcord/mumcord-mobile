// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BanInput
import app.mumcord.mobile.bridge.BanTarget
import app.mumcord.mobile.bridge.Channel
import app.mumcord.mobile.bridge.ServerState
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.MOCKUP_GAMING
import app.mumcord.mobile.session.MOCKUP_JONAS
import app.mumcord.mobile.session.MOCKUP_LEA
import app.mumcord.mobile.session.MOCKUP_ROOT
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.ServerModel
import app.mumcord.mobile.session.mockupServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which moderation entries show, the ban form and what a failure says. */
class ModerationTest {
    private fun model(state: ServerState = mockupServer()) =
        ServerModel(state.selfSession, state.channels.associateBy { it.id }, state.people.associateBy { it.session })

    private fun ServerModel.rowsFor(session: UInt) = moderationRows(people.getValue(session))

    @Test
    fun withoutAnyRightTheSectionIsAbsent() {
        val rows = model().rowsFor(MOCKUP_JONAS)
        assertEquals(ModerationRows(), rows)
        assertFalse(rows.any)
    }

    @Test
    fun theServerAdminSeesEveryEntry() {
        val rows = model(mockupServer().granting { it.allRights() }).rowsFor(MOCKUP_JONAS)
        assertEquals(ModerationRows(move = true, serverMute = true, serverMuted = false, kick = true, ban = true), rows)
    }

    @Test
    fun moveAndMuteFollowThePersonsChannelKickAndBanTheRoot() {
        // A channel admin of Gaming: move and mute there, nothing on the root.
        val channelAdmin = model(
            mockupServer().granting { if (it.id == MOCKUP_GAMING) it.rights(move = true, mute = true) else it },
        )
        assertEquals(ModerationRows(move = true, serverMute = true), channelAdmin.rowsFor(MOCKUP_JONAS))
        // Kick and ban granted on the root only: they show for a person in any channel.
        val rootOnly = model(
            mockupServer().granting { if (it.id == MOCKUP_ROOT) it.rights(kick = true, ban = true) else it },
        )
        assertEquals(ModerationRows(kick = true, ban = true), rootOnly.rowsFor(MOCKUP_JONAS))
    }

    @Test
    fun aServerMutedPersonGetsTheEntryThatLiftsIt() {
        val server = mockupServer().granting { it.allRights() }
        val muted = server.copy(people = server.people.map { if (it.session == MOCKUP_JONAS) it.copy(serverMuted = true) else it })
        assertTrue(model(muted).rowsFor(MOCKUP_JONAS).serverMuted)
    }

    @Test
    fun muteIsNotOfferedInATemporaryChannel() {
        val server = mockupServer().granting { it.allRights().let { c -> if (c.id == MOCKUP_GAMING) c.copy(temporary = true) else c } }
        val rows = model(server).rowsFor(MOCKUP_JONAS)
        assertFalse(rows.serverMute)
        assertTrue(rows.move && rows.kick && rows.ban)
    }

    @Test
    fun theUserThemselvesHasNoModeration() {
        assertEquals(ModerationRows(), model(mockupServer().granting { it.allRights() }).rowsFor(MOCKUP_LEA))
    }

    @Test
    fun theMovePickerOffersEveryChannelButThePersonsOwn() {
        val server = model()
        val sheet = moderationSheet(server, server.people.getValue(MOCKUP_JONAS), ModerationLocal.Move(MOCKUP_JONAS))
        sheet as ModerationSheet.Move
        assertEquals(listOf("Nordlicht", "Lobby", "Music", "AFK"), sheet.choices.map { it.name })
        assertTrue(sheet.choices.none { it.id == MOCKUP_GAMING })
    }

    @Test
    fun aBanSendsTheAddressAlwaysAndTheCertificateWhenThePersonHasOne() {
        val form = BanForm(reason = "  spam  ", durationSecs = 86_400u, byCertificate = true, removeNow = true)
        assertEquals(
            BanInput("spam", 86_400u, byCertificate = true, byAddress = true, removeNow = true, force = false, quick = false),
            banInput(target(), form, force = false),
        )
        val noCertificate = banInput(target(hasCertificate = false), form, force = true)
        assertFalse(noCertificate.byCertificate)
        assertTrue(noCertificate.byAddress && noCertificate.force)
        assertFalse(banInput(target(), form.copy(byCertificate = false), force = false).byCertificate)
    }

    @Test
    fun aBlankReasonIsNoneAndRemovingNeedsTheKickRight() {
        val input = banInput(target(canKick = false), BanForm(reason = "   "), force = false)
        assertNull(input.reason)
        assertFalse(input.removeNow)
        assertFalse(target(canKick = false).removeNowChoice)
    }

    @Test
    fun withoutAReadableBanListOrAnAddressOnlyTheServersDefaultBanIsPossible() {
        for (quick in listOf(target(banListReadable = false), target(address = null))) {
            assertTrue(quick.quick)
            assertFalse(quick.certificateChoice || quick.removeNowChoice)
            val input = banInput(quick, BanForm(reason = "bye", durationSecs = 3_600u, byCertificate = false, removeNow = false), force = true)
            assertEquals(
                BanInput("bye", 0u, byCertificate = true, byAddress = true, removeNow = true, force = false, quick = true),
                input,
            )
        }
    }

    @Test
    fun aTestMayShortenTheLength() {
        assertEquals(10u, banInput(target(), BanForm(durationSecs = 3_600u), force = false, durationSecs = 10u).durationSecs)
    }

    @Test
    fun theBanEndsAfterTheChosenLengthFromWhenTheSheetOpened() {
        val sheet = ModerationSheet.Ban("Jonas", "Gaming", target(), BanForm(durationSecs = 3_600u), 1_000L, false, null, false)
        assertEquals(3_601_000L, sheet.endsAtMs)
        assertNull(sheet.copy(form = BanForm(durationSecs = 0u)).endsAtMs)
        assertNull(sheet.copy(target = target(banListReadable = false)).endsAtMs)
        assertTrue(sheet.copy(target = null).loading)
        assertFalse(sheet.copy(target = null, failure = ModerationFailure.Failed).loading)
    }

    @Test
    fun aRefusalKeepsTheServersTextAsPlainCharacters() {
        // Control and format characters (a bell, a right-to-left override) could hide or reorder it.
        assertEquals(
            ModerationFailure.Refused("No kicking here"),
            Outcome.Failed(ConnectionError.PermissionDenied, " No\u0007 kicking\u202E here ").moderationFailure(),
        )
        assertEquals(ModerationFailure.Refused(""), Outcome.Failed(ConnectionError.PermissionDenied).moderationFailure())
        assertEquals(200, (Outcome.Failed(ConnectionError.PermissionDenied, "x".repeat(500)).moderationFailure() as ModerationFailure.Refused).serverText.length)
        assertEquals(ModerationFailure.Gone, Outcome.Failed(ConnectionError.NotFound).moderationFailure())
        assertEquals(ModerationFailure.Disconnected, Outcome.Failed(ConnectionError.NotConnected).moderationFailure())
        assertEquals(ModerationFailure.Failed, Outcome.Failed(ConnectionError.Internal).moderationFailure())
    }

    private fun target(
        address: String? = "192.0.2.7",
        banListReadable: Boolean = true,
        hasCertificate: Boolean = true,
        canKick: Boolean = true,
    ) = BanTarget(address, onlineSecs = 90u, banListReadable = banListReadable, hasCertificate = hasCertificate, canKick = canKick, canBan = true)
}

/** The mockup server with the rights [change] gives each channel. */
fun ServerState.granting(change: (Channel) -> Channel) = copy(channels = channels.map(change))

fun Channel.allRights() = rights(move = true, mute = true, kick = true, ban = true)

fun Channel.rights(move: Boolean = false, mute: Boolean = false, kick: Boolean = false, ban: Boolean = false) =
    copy(permissions = permissions.copy(movePeople = move, muteDeafen = mute, kick = kick, ban = ban))
