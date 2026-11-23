// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connected

/** At most one announcement in this time, so a lively channel does not drown out everything else. */
const val TALKING_ANNOUNCEMENT_GAP_MILLIS = 5_000L

/** A person who keeps talking in short bursts is named again only after this time. */
const val TALKING_REPEAT_MILLIS = 30_000L

/**
 * Decides when the screen reader says who started talking in the user's channel (spec section 5:
 * "talking state is announced politely, not on every change"). Only the start of talking is
 * announced, never the end; people who start within one gap are named together; the user is
 * never announced. Pure logic: the caller passes the clock and asks again at [nextUpdateAt].
 */
class TalkingAnnouncer(
    private val gapMillis: Long = TALKING_ANNOUNCEMENT_GAP_MILLIS,
    private val repeatMillis: Long = TALKING_REPEAT_MILLIS,
) {
    private var talking: Set<UInt> = emptySet()
    private val waiting = LinkedHashSet<UInt>()
    private val lastNamed = HashMap<UInt, Long>()
    private var lastAnnouncedAt: Long? = null

    /** The time the caller has to call [update] again, also without a change; null when nothing waits. */
    var nextUpdateAt: Long? = null
        private set

    /**
     * Takes who talks now ([others]: session to name, without the user) and returns the names to
     * announce now, in the order they started; empty when nothing is to be said.
     */
    fun update(now: Long, others: Map<UInt, String>): List<String> {
        for (session in others.keys - talking) {
            val named = lastNamed[session]
            if (named == null || now - named >= repeatMillis) waiting += session
        }
        talking = others.keys
        // Somebody who stopped before their turn came is not announced any more.
        waiting.retainAll(talking)
        val last = lastAnnouncedAt
        if (waiting.isEmpty()) {
            nextUpdateAt = null
            return emptyList()
        }
        if (last != null && now - last < gapMillis) {
            nextUpdateAt = last + gapMillis
            return emptyList()
        }
        val names = waiting.mapNotNull { others[it] }
        for (session in waiting) lastNamed[session] = now
        waiting.clear()
        lastAnnouncedAt = now
        nextUpdateAt = null
        return names
    }
}
