// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import app.mumcord.mobile.platform.notify.IdleNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long the user may be alone in a channel before the warning, and how long the warning
 * gives them (FR-5.42). The product's times are fixed; only a test shortens them.
 */
data class IdleTimes(val aloneMillis: Long = 30 * 60_000L, val warningMillis: Long = 60_000L)

/** Where the "disconnect when alone" rule stands. All times are on the clock of the caller. */
sealed interface IdleState {
    /** Not alone, not connected, or the setting is off. */
    data object Resting : IdleState

    data class Alone(val sinceMillis: Long) : IdleState

    /** The warning is shown; at [untilMillis] the app disconnects. */
    data class Warned(val untilMillis: Long) : IdleState
}

enum class IdleEffect { Warn, Unwarn, Disconnect }

/**
 * The rule as a value (FR-5.42): alone for [IdleTimes.aloneMillis] warns, another
 * [IdleTimes.warningMillis] without a reaction disconnects, and anything that ends being
 * alone (someone joins, the user leaves, the setting is switched off) starts over.
 *
 * @param alone the setting is on and the user is connected and alone in their channel.
 */
fun IdleState.step(alone: Boolean, nowMillis: Long, times: IdleTimes): Pair<IdleState, IdleEffect?> = when {
    !alone -> IdleState.Resting to IdleEffect.Unwarn.takeIf { this is IdleState.Warned }
    this is IdleState.Resting -> IdleState.Alone(nowMillis) to null
    this is IdleState.Alone && nowMillis >= sinceMillis + times.aloneMillis ->
        IdleState.Warned(nowMillis + times.warningMillis) to IdleEffect.Warn
    this is IdleState.Warned && nowMillis >= untilMillis -> IdleState.Resting to IdleEffect.Disconnect
    else -> this to null
}

/** "Stay connected": the warning goes away and the time alone starts over. */
fun IdleState.stay(nowMillis: Long): Pair<IdleState, IdleEffect?> =
    if (this is IdleState.Warned) IdleState.Alone(nowMillis) to IdleEffect.Unwarn else this to null

/** When the rule has to be looked at again, or null while there is nothing to wait for. */
fun IdleState.wakeAtMillis(times: IdleTimes): Long? = when (this) {
    IdleState.Resting -> null
    is IdleState.Alone -> sinceMillis + times.aloneMillis
    is IdleState.Warned -> untilMillis
}

/** Whether the user is connected and the only one in their channel. */
fun aloneInChannel(state: SessionState): Boolean {
    val server = state.server?.takeIf { state.phase == ConnectionPhase.Connected } ?: return false
    val channel = server.selfChannelId ?: return false
    return server.people.values.count { it.channelId == channel } == 1
}

/** The session that "disconnect when alone" ended, for the notice in the app. */
data class IdleNotice(val serverId: Long, val serverName: String)

/** What the screens need of "disconnect when alone". */
interface IdleAccess {
    /** Set after the app disconnected by itself, until [dismiss]. */
    val notice: StateFlow<IdleNotice?>

    /** The user has seen the notice; the notification about it goes too. */
    fun dismiss()

    companion object {
        val Never: IdleAccess = object : IdleAccess {
            override val notice = MutableStateFlow<IdleNotice?>(null)

            override fun dismiss() = Unit
        }
    }
}

/**
 * Runs the rule of [step] for the session: warns with a notification, disconnects when nobody
 * reacts and says so (FR-5.42). It follows the session by itself once it has been created.
 *
 * The timer is a wait in the app's own process against [clock], which must go on while the
 * phone sleeps (`SystemClock.elapsedRealtime`). That is enough: a session only exists while
 * its foreground service keeps the process running, and its connection needs the process
 * awake for a keep-alive every few seconds anyway, so a phone on which this wait could not
 * run has lost the session already. System alarms are no alternative: exact ones need a
 * special permission, inexact ones may be minutes late and are held back while the phone
 * dozes, which the single minute of the warning does not survive.
 *
 * Nothing runs while the user is not alone or the setting is off.
 *
 * @param scope lives as long as the process.
 * @param times read whenever a wait begins.
 */
class IdleDisconnect(
    private val repository: SessionRepository,
    private val notifier: IdleNotifier,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val times: () -> IdleTimes = { IdleTimes() },
) : IdleAccess {
    private val mutableNotice = MutableStateFlow<IdleNotice?>(null)
    override val notice: StateFlow<IdleNotice?> = mutableNotice.asStateFlow()

    /** How often "Stay connected" was tapped. */
    private val stays = MutableStateFlow(0)

    // Only the one collector below touches these.
    private var state: IdleState = IdleState.Resting
    private var staysSeen = 0

    init {
        scope.launch {
            val alone = repository.state
                .map { it.mobileSettings?.idleDisconnect == true && aloneInChannel(it) }
                .distinctUntilChanged()
            combine(alone, stays, ::Pair).collectLatest { (alone, stays) ->
                if (stays != staysSeen) {
                    staysSeen = stays
                    apply(state.stay(clock()))
                }
                while (true) {
                    apply(state.step(alone, clock(), times()))
                    val wakeAt = state.wakeAtMillis(times()) ?: break
                    delay((wakeAt - clock()).coerceAtLeast(0))
                }
            }
        }
    }

    /** The "Stay connected" button of the warning. */
    fun stayConnected() = stays.update { it + 1 }

    override fun dismiss() {
        mutableNotice.value = null
        notifier.hideDisconnected()
    }

    private fun apply(step: Pair<IdleState, IdleEffect?>) {
        state = step.first
        when (step.second) {
            IdleEffect.Warn -> notifier.showWarning()
            IdleEffect.Unwarn -> notifier.hideWarning()
            // On its own: the end of the session ends this collector's turn.
            IdleEffect.Disconnect -> scope.launch { disconnect() }
            null -> Unit
        }
    }

    private suspend fun disconnect() {
        val session = repository.state.value
        val serverId = session.activeServerId
        notifier.hideWarning()
        repository.disconnect()
        notifier.showDisconnected()
        if (serverId != null) {
            mutableNotice.value = IdleNotice(serverId, session.servers.firstOrNull { it.id == serverId }?.name.orEmpty())
        }
    }
}
