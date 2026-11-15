// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.bridge.BanResult
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The moderation section of the person sheet (FR-5.90 … FR-5.93): moving, server mute, kick
 * and ban of the person whose sheet is open. It works on the connected screens' own state,
 * where the step that replaced the person sheet is [ConnectedLocal.moderation].
 *
 * Move and server mute act at once; a refusal reaches the screen as the server's refusal event
 * like any other. Kick and ban ask first in a sheet of their own, which also shows a refusal:
 * the engine reports none of those twice.
 *
 * @param now the clock "Ends on" of the ban sheet counts from.
 */
class ModerationController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val local: MutableStateFlow<ConnectedLocal>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val person: UInt?
        get() = local.value.personSession

    /** Opens the channel picker for the person. */
    fun openMove() = open { ModerationLocal.Move(it) }

    /** Moves the person; the sheets close, the channel list shows where they are. */
    fun move(channelId: UInt) {
        val session = (local.value.moderation as? ModerationLocal.Move)?.session ?: return
        closePerson()
        scope.launch { reportUnlessRefused(repository.movePerson(session, channelId)) }
    }

    /** Server-mutes the person or lifts it; the entry follows once the server echoes it. */
    fun toggleServerMute() {
        val session = person ?: return
        val muted = repository.state.value.server?.people?.get(session)?.serverMuted ?: return
        scope.launch {
            val outcome = repository.setServerMute(session, !muted)
            // The refusal is shown on the screen behind the sheet.
            if (outcome is Outcome.Failed) {
                local.update { if (it.personSession == session) it.copy(personSession = null, moderation = null) else it }
                reportUnlessRefused(outcome)
            }
        }
    }

    fun openKick() = open { ModerationLocal.Kick(it) }

    fun changeKickReason(reason: String) = updateKick { it.copy(reason = reason) }

    fun confirmKick() {
        val kick = local.value.moderation as? ModerationLocal.Kick ?: return
        if (kick.busy) return
        updateKick { it.copy(busy = true, failure = null) }
        scope.launch {
            when (val outcome = repository.kickPerson(kick.session, kick.reason.trim().ifEmpty { null })) {
                is Outcome.Ok -> finished(kick.session)
                is Outcome.Failed -> updateKick(kick.session) { it.copy(busy = false, failure = outcome.moderationFailure()) }
            }
        }
    }

    /** Opens the ban sheet and asks the server what it needs about the person. */
    fun openBan() {
        val session = person ?: return
        open { ModerationLocal.Ban(it, openedAtMs = now()) }
        scope.launch {
            val target = repository.banTarget(session)
            updateBan(session) { ban ->
                when (target) {
                    is Outcome.Ok -> ban.copy(target = target.value, form = ban.form.copy(byCertificate = target.value.hasCertificate))
                    is Outcome.Failed -> ban.copy(failure = target.moderationFailure())
                }
            }
        }
    }

    fun changeBanReason(reason: String) = updateBan { it.copy(form = it.form.copy(reason = reason)) }

    fun selectBanDuration(secs: UInt) = updateBan { it.copy(form = it.form.copy(durationSecs = secs)) }

    fun setBanByCertificate(on: Boolean) = updateBan { it.copy(form = it.form.copy(byCertificate = on)) }

    fun setBanRemoveNow(on: Boolean) = updateBan { it.copy(form = it.form.copy(removeNow = on)) }

    /** "Kick only instead": the kick sheet with the reason typed so far. */
    fun kickInstead() {
        val ban = local.value.moderation as? ModerationLocal.Ban ?: return
        if (ban.busy) return
        local.update { it.copy(moderation = ModerationLocal.Kick(ban.session, reason = ban.form.reason)) }
    }

    fun confirmBan() = sendBan(force = false)

    /** "Save mine anyway" after the server's entry changed meanwhile. */
    fun banAnyway() = sendBan(force = true)

    private fun sendBan(force: Boolean) {
        val ban = local.value.moderation as? ModerationLocal.Ban ?: return
        val target = ban.target ?: return
        if (ban.busy) return
        updateBan { it.copy(busy = true, failure = null, conflict = false) }
        val input = banInput(target, ban.form, force, TestSettings.banSeconds ?: ban.form.durationSecs)
        scope.launch {
            when (val outcome = repository.banPerson(ban.session, input)) {
                is Outcome.Ok -> when (outcome.value) {
                    is BanResult.Saved, BanResult.QuickBanned -> finished(ban.session)
                    BanResult.Conflict -> updateBan(ban.session) { it.copy(busy = false, conflict = true) }
                }
                is Outcome.Failed -> updateBan(ban.session) { it.copy(busy = false, failure = outcome.moderationFailure()) }
            }
        }
    }

    private fun open(step: (UInt) -> ModerationLocal) {
        val session = person ?: return
        local.update { it.copy(moderation = step(session)) }
    }

    /** The kick or ban went through: the person leaves the list, so their sheet goes too. */
    private fun finished(session: UInt) =
        local.update { if (it.moderation?.session == session) it.copy(personSession = null, moderation = null) else it }

    private fun closePerson() = local.update { it.copy(personSession = null, volumeDraft = null, moderation = null) }

    private fun updateKick(session: UInt? = null, change: (ModerationLocal.Kick) -> ModerationLocal.Kick) =
        local.update { state ->
            val kick = state.moderation as? ModerationLocal.Kick
            if (kick == null || (session != null && kick.session != session)) state else state.copy(moderation = change(kick))
        }

    private fun updateBan(session: UInt? = null, change: (ModerationLocal.Ban) -> ModerationLocal.Ban) =
        local.update { state ->
            val ban = state.moderation as? ModerationLocal.Ban
            if (ban == null || (session != null && ban.session != session)) state else state.copy(moderation = change(ban))
        }

    /** A refusal already reached the screen as the server's refusal event; anything else did not. */
    private fun reportUnlessRefused(outcome: Outcome<Boolean>) {
        if (outcome is Outcome.Failed && outcome.error != ConnectionError.PermissionDenied) repository.reportFailure()
    }
}
