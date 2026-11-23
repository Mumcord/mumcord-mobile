// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** At most one check a day (FR-5.101). */
const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

/**
 * A check is due a day after the last one. A clock set back before the last check counts as due,
 * so a wrong date cannot stop the checks for good.
 */
fun isCheckDue(nowMillis: Long, lastCheckMillis: Long?): Boolean =
    lastCheckMillis == null || nowMillis < lastCheckMillis || nowMillis - lastCheckMillis >= CHECK_INTERVAL_MILLIS

/**
 * The release the notice offers: only with the switch on, only in a release build, only when it
 * is newer than the installed version and only until the user closed its notice.
 */
fun updateNotice(
    installed: ReleaseVersion?,
    enabled: Boolean,
    available: AvailableUpdate?,
    dismissed: ReleaseVersion?,
): AvailableUpdate? = available?.takeIf {
    enabled && installed != null && it.version > installed && it.version != dismissed
}

/**
 * The Android update check (FR-5.101): when the app is opened, at most once a day and only with
 * the switch on, it asks GitHub for the latest release. It runs in the open app only, so nothing
 * wakes the phone for it (FR-5.43); a newer release is shown as a notice whose action opens the
 * download in the browser. Nothing is downloaded or installed by the app.
 *
 * Debug and test builds never ask: [AppInfo.releaseVersion] is null for them.
 */
class UpdateChecker(
    private val info: AppInfo,
    private val source: ReleaseSource,
    private val store: UpdateStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableAvailable = MutableStateFlow(store.available)
    private val mutableDismissed = MutableStateFlow(store.dismissed)
    private val running = Mutex()

    /** The newest release found so far, newer or not; see [updateNotice]. */
    val available: StateFlow<AvailableUpdate?> = mutableAvailable.asStateFlow()

    /** The version whose notice the user closed. */
    val dismissed: StateFlow<ReleaseVersion?> = mutableDismissed.asStateFlow()

    val installed: ReleaseVersion? get() = info.releaseVersion

    /**
     * Asks GitHub when a check is due. A failed check counts as a check: a phone without a
     * connection does not try again on every start, and the next try is the next day.
     */
    suspend fun checkIfDue(enabled: Boolean) {
        if (!enabled || installed == null) return
        // A second resume while a check runs does not start another one.
        if (!running.tryLock()) return
        try {
            val now = clock()
            if (!isCheckDue(now, store.lastCheckMillis)) return
            store.lastCheckMillis = now
            val found = try {
                withContext(io) { parseLatestRelease(source.latestReleaseJson()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the kind of failure: an address or an answer could name the network.
                log("update check failed: ${e.javaClass.simpleName}")
                return
            }
            store.available = found
            mutableAvailable.value = found
        } finally {
            running.unlock()
        }
    }

    /** The user closed the notice of [version]; it comes back only for a newer one. */
    fun dismiss(version: ReleaseVersion) {
        store.dismissed = version
        mutableDismissed.value = version
    }
}
