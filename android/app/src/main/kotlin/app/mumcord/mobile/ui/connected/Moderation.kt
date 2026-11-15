// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.BanInput
import app.mumcord.mobile.bridge.BanTarget
import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.ServerModel

/**
 * The moderation entries of the person sheet (FR-5.90 … FR-5.93). The server decides: an entry
 * is shown only when its answer grants the right, never as a disabled control. Moving and
 * server mute are rights of the person's channel; kick and ban are rights of the root channel,
 * as on the desktop.
 *
 * @param serverMuted the person is muted by the server, so the mute entry lifts it.
 */
data class ModerationRows(
    val move: Boolean = false,
    val serverMute: Boolean = false,
    val serverMuted: Boolean = false,
    val kick: Boolean = false,
    val ban: Boolean = false,
) {
    val any: Boolean
        get() = move || serverMute || kick || ban
}

/** What the server lets the user do to [person]; nothing for the user themselves. */
fun ServerModel.moderationRows(person: Person): ModerationRows {
    if (person.isSelf) return ModerationRows()
    val channel = channels[person.channelId]
    val root = channels.values.firstOrNull { it.parentId == null }?.permissions
    return ModerationRows(
        move = channel?.permissions?.movePeople == true,
        // A standard server refuses to mute people in a temporary channel, so it is not offered
        // there (as on the desktop).
        serverMute = channel != null && channel.permissions.muteDeafen && !channel.temporary,
        serverMuted = person.serverMuted,
        kick = root?.kick == true,
        ban = root?.ban == true,
    )
}

/** Why a kick or a ban did not happen, as the open sheet says it. */
sealed interface ModerationFailure {
    /** The server refused; [serverText] is its own words (untrusted, may be empty). */
    data class Refused(val serverText: String) : ModerationFailure

    /** The person left the server meanwhile. */
    data object Gone : ModerationFailure

    data object Disconnected : ModerationFailure

    data object Failed : ModerationFailure
}

/** The longest server text a sheet shows; the rest is cut off. */
private const val MAX_SERVER_TEXT = 200

private fun hidden(c: Char): Boolean {
    val type = Character.getType(c).toByte()
    return type == Character.CONTROL || type == Character.FORMAT
}

fun Outcome.Failed.moderationFailure(): ModerationFailure = when (error) {
    // Server text is shown as plain characters; control and format characters could hide or
    // reorder it.
    ConnectionError.PermissionDenied ->
        ModerationFailure.Refused(refusal.orEmpty().filterNot(::hidden).trim().take(MAX_SERVER_TEXT))
    ConnectionError.NotFound -> ModerationFailure.Gone
    ConnectionError.NotConnected -> ModerationFailure.Disconnected
    else -> ModerationFailure.Failed
}

/** The ban lengths the sheet offers, as on the desktop (FR-10.12); 0 is permanent. */
val BAN_DURATIONS_SECS: List<UInt> = listOf(3_600u, 86_400u, 604_800u, 2_592_000u, 0u)

/** What the user chose in the ban sheet. */
data class BanForm(
    val reason: String = "",
    val durationSecs: UInt = BAN_DURATIONS_SECS.first(),
    val byCertificate: Boolean = true,
    val removeNow: Boolean = true,
)

/**
 * Only the server's default ban is possible: its ban list cannot be read, or it did not tell
 * the address, without which no entry of the user's own can be written (FR-10.13).
 */
val BanTarget.quick: Boolean
    get() = !banListReadable || address == null

/** The certificate can be part of the ban: the person has one and the ban is not the quick one. */
val BanTarget.certificateChoice: Boolean
    get() = !quick && hasCertificate

/** "Remove from server now" needs the kick right; the quick ban always removes. */
val BanTarget.removeNowChoice: Boolean
    get() = !quick && canKick

/**
 * The ban as the bridge takes it. A standard server matches the address, so the address is
 * always part of the entry (as on the desktop); the certificate only when the person has one.
 * The quick ban takes the server's own length and targets.
 *
 * @param durationSecs what is sent as the length: the chosen one, unless a test shortens it.
 */
fun banInput(target: BanTarget, form: BanForm, force: Boolean, durationSecs: UInt = form.durationSecs): BanInput {
    val reason = form.reason.trim().ifEmpty { null }
    return if (target.quick) {
        BanInput(reason, durationSecs = 0u, byCertificate = true, byAddress = true, removeNow = true, force = false, quick = true)
    } else {
        BanInput(
            reason = reason,
            durationSecs = durationSecs,
            byCertificate = target.hasCertificate && form.byCertificate,
            byAddress = true,
            removeNow = target.canKick && form.removeNow,
            force = force,
            quick = false,
        )
    }
}

/** The moderation step that replaced the person sheet, for the person whose sheet it was. */
sealed interface ModerationLocal {
    val session: UInt

    /** The channel picker of "Move to channel…". */
    data class Move(override val session: UInt) : ModerationLocal

    data class Kick(
        override val session: UInt,
        val reason: String = "",
        val busy: Boolean = false,
        val failure: ModerationFailure? = null,
    ) : ModerationLocal

    /**
     * @param target what the server said about the person; null while it is asked for.
     * @param openedAtMs when the sheet opened, from which "Ends on" counts (measured once).
     * @param conflict the entry changed on the server meanwhile: ask before saving anyway.
     */
    data class Ban(
        override val session: UInt,
        val openedAtMs: Long,
        val target: BanTarget? = null,
        val form: BanForm = BanForm(),
        val busy: Boolean = false,
        val failure: ModerationFailure? = null,
        val conflict: Boolean = false,
    ) : ModerationLocal
}

/** A channel a person can be moved to, indented like the channel list. */
data class MoveChoice(val id: UInt, val name: String, val depth: Int)

/** The moderation sheet as it is drawn. Names are untrusted text. */
sealed interface ModerationSheet {
    val name: String

    data class Move(override val name: String, val choices: List<MoveChoice>) : ModerationSheet

    data class Kick(
        override val name: String,
        val channelName: String,
        val reason: String,
        val busy: Boolean,
        val failure: ModerationFailure?,
    ) : ModerationSheet

    /** [target] is null while the server is asked about the person. */
    data class Ban(
        override val name: String,
        val channelName: String,
        val target: BanTarget?,
        val form: BanForm,
        val openedAtMs: Long,
        val busy: Boolean,
        val failure: ModerationFailure?,
        val conflict: Boolean,
    ) : ModerationSheet {
        val loading: Boolean
            get() = target == null && failure == null

        /** When the chosen ban ends; null for a permanent one and for the server's default. */
        val endsAtMs: Long?
            get() = if (target == null || target.quick || form.durationSecs == 0u) {
                null
            } else {
                openedAtMs + form.durationSecs.toLong() * MILLIS_PER_SECOND
            }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

/** The sheet of [local] for [person], who is on [server]. */
fun moderationSheet(server: ServerModel, person: Person, local: ModerationLocal): ModerationSheet {
    val channelName = server.channels[person.channelId]?.name.orEmpty()
    return when (local) {
        is ModerationLocal.Move -> ModerationSheet.Move(
            name = person.name,
            choices = channelRows(server.root, selfChannelId = null, collapsed = emptySet())
                .filterIsInstance<ChannelListRow.ChannelItem>()
                .filter { it.id != person.channelId }
                .map { MoveChoice(it.id, it.name, it.depth) },
        )
        is ModerationLocal.Kick -> ModerationSheet.Kick(person.name, channelName, local.reason, local.busy, local.failure)
        is ModerationLocal.Ban -> ModerationSheet.Ban(
            name = person.name,
            channelName = channelName,
            target = local.target,
            form = local.form,
            openedAtMs = local.openedAtMs,
            busy = local.busy,
            failure = local.failure,
            conflict = local.conflict,
        )
    }
}
