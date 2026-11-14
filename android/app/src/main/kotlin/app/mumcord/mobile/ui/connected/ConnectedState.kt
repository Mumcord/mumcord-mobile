// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import app.mumcord.mobile.bridge.Person
import app.mumcord.mobile.session.ChannelNode
import app.mumcord.mobile.session.ConnectionPhase
import app.mumcord.mobile.session.SessionState
import app.mumcord.mobile.session.UnsupportedHint

/** The four areas of the connected app, in the order of the bottom tab bar. */
enum class ConnectedTab { Channels, Chat, Messages, More }

/** One line of the channel list: the tree flattened in display order. */
sealed interface ChannelListRow {
    val depth: Int

    /**
     * @param peopleCount the people directly in the channel, also while a search hides some.
     * @param expanded true or false for a channel that can be folded, null when it has nothing
     *   to fold or a search decides what is visible.
     * @param locked the channel is restricted, so joining may ask for a password.
     */
    data class ChannelItem(
        val id: UInt,
        val name: String,
        override val depth: Int,
        val peopleCount: Int,
        val current: Boolean,
        val locked: Boolean,
        val expanded: Boolean?,
    ) : ChannelListRow

    data class PersonItem(
        val session: UInt,
        val name: String,
        override val depth: Int,
        val isSelf: Boolean,
        val talking: Boolean,
        val locallyMuted: Boolean,
        val state: PersonFlags,
    ) : ChannelListRow
}

/** What the state icons of a person show. Being deafened by the server counts as deafened. */
data class PersonFlags(
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val serverMuted: Boolean = false,
    val streaming: Boolean = false,
    val camera: Boolean = false,
)

private fun Person.flags() = PersonFlags(
    muted = selfMuted,
    deafened = selfDeafened || serverDeafened,
    serverMuted = serverMuted,
    streaming = streaming,
    camera = camera,
)

/**
 * The visible lines of the tree. The root channel comes first and its sub-channels follow on
 * the same level, as in the desktop app; deeper channels are indented one step per level.
 * People of a channel come before its sub-channels. A collapsed channel hides both.
 *
 * A non-blank [query] filters by name, ignoring case: a person shows when the name matches, a
 * channel when its name matches or something inside it shows. Collapsing is ignored meanwhile,
 * so a match is never hidden.
 */
fun channelRows(
    root: ChannelNode?,
    selfChannelId: UInt?,
    collapsed: Set<UInt>,
    query: String = "",
): List<ChannelListRow> {
    root ?: return emptyList()
    val needle = query.trim()
    val searching = needle.isNotEmpty()
    val shown = if (searching) root.filtered(needle) ?: return emptyList() else root
    val counts = buildMap { root.collectCounts(this) }

    return buildList {
        fun add(node: ChannelNode, level: Int) {
            val channel = node.channel
            // The root and its sub-channels share the first level.
            val depth = (level - 1).coerceAtLeast(0)
            val foldable = node.people.isNotEmpty() || node.children.isNotEmpty()
            val open = searching || channel.id !in collapsed
            add(
                ChannelListRow.ChannelItem(
                    id = channel.id,
                    name = channel.name,
                    depth = depth,
                    peopleCount = counts[channel.id] ?: 0,
                    current = channel.id == selfChannelId,
                    locked = channel.restricted,
                    expanded = if (foldable && !searching) open else null,
                ),
            )
            if (!open) return
            node.people.forEach {
                add(
                    ChannelListRow.PersonItem(
                        session = it.session,
                        name = it.name,
                        depth = depth + 1,
                        isSelf = it.isSelf,
                        talking = it.talking,
                        locallyMuted = it.locallyMuted,
                        state = it.flags(),
                    ),
                )
            }
            node.children.forEach { add(it, level + 1) }
        }
        add(shown, 0)
    }
}

private fun ChannelNode.collectCounts(into: MutableMap<UInt, Int>) {
    into[channel.id] = people.size
    children.forEach { it.collectCounts(into) }
}

private fun ChannelNode.filtered(needle: String): ChannelNode? {
    val matchingPeople = people.filter { it.name.contains(needle, ignoreCase = true) }
    val matchingChildren = children.mapNotNull { it.filtered(needle) }
    val matches = channel.name.contains(needle, ignoreCase = true)
    return if (matches || matchingPeople.isNotEmpty() || matchingChildren.isNotEmpty()) {
        copy(people = matchingPeople, children = matchingChildren)
    } else {
        null
    }
}

/** What the user did on the connected screens; everything else follows from the session. */
data class ConnectedLocal(
    val tab: ConnectedTab = ConnectedTab.Channels,
    val collapsed: Set<UInt> = emptySet(),
    val searching: Boolean = false,
    val query: String = "",
    /** The person whose sheet is open. */
    val personSession: UInt? = null,
    /** The slider position while it is moved, before the engine reports the new volume. */
    val volumeDraft: Int? = null,
    /** The channel whose menu is open. */
    val menuChannelId: UInt? = null,
    /** The channel password being typed. */
    val password: String = "",
)

/** The sheet over the connected screens. At most one at a time. */
sealed interface ConnectedSheet {
    data class ChannelPassword(val channelName: String, val wrong: Boolean, val password: String) : ConnectedSheet

    data class PersonSheet(
        val session: UInt,
        val name: String,
        val channelName: String,
        val volumePercent: Int,
        val locallyMuted: Boolean,
        val state: PersonFlags,
    ) : ConnectedSheet

    /** [expanded] as in [ChannelListRow.ChannelItem]; [canJoin] is false for the own channel. */
    data class ChannelMenu(val id: UInt, val name: String, val expanded: Boolean?, val canJoin: Boolean) : ConnectedSheet

    /** Why the microphone is needed, before the system asks for it. */
    data object Microphone : ConnectedSheet
}

/** A refusal by the server; [channelName] and [detail] are untrusted text. */
data class DeniedBanner(val channelName: String?, val detail: String)

data class ConnectedUiState(
    val tab: ConnectedTab,
    val serverName: String,
    val peopleOnline: Int,
    val rows: List<ChannelListRow>,
    val searching: Boolean,
    val query: String,
    val reconnecting: ConnectionPhase.Reconnecting?,
    val denied: DeniedBanner?,
    val hints: List<UnsupportedHint>,
    /** [MicrophoneNotice.Denied] or [MicrophoneNotice.Blocked] while the others cannot hear the user. */
    val microphone: MicrophoneNotice,
    val sheet: ConnectedSheet?,
)

/** The connected screens as a value, or null while no server is shown. */
fun connectedUiState(
    session: SessionState,
    local: ConnectedLocal,
    serverName: String,
    microphone: MicrophoneNotice = MicrophoneNotice.None,
): ConnectedUiState? {
    val server = session.server?.takeIf { session.showsServer } ?: return null
    val rows = channelRows(server.root, server.selfChannelId, local.collapsed, if (local.searching) local.query else "")

    val prompt = session.channelPassword
    val person = local.personSession?.let(server.people::get)
    val menuChannel = local.menuChannelId?.let(server.channels::get)
    val sheet = when {
        // The server waits for the password, so this question comes first.
        prompt != null -> ConnectedSheet.ChannelPassword(
            channelName = server.channels[prompt.channelId]?.name.orEmpty(),
            wrong = prompt.wrong,
            password = local.password,
        )
        // Asked when talking first becomes possible; it closes what the user had open, so
        // there is never more than one sheet.
        microphone == MicrophoneNotice.Explain -> ConnectedSheet.Microphone
        person != null -> ConnectedSheet.PersonSheet(
            session = person.session,
            name = person.name,
            channelName = server.channels[person.channelId]?.name.orEmpty(),
            volumePercent = local.volumeDraft ?: person.localVolume.toInt(),
            locallyMuted = person.locallyMuted,
            state = person.flags(),
        )
        menuChannel != null -> ConnectedSheet.ChannelMenu(
            id = menuChannel.id,
            name = menuChannel.name,
            expanded = rows.filterIsInstance<ChannelListRow.ChannelItem>()
                .firstOrNull { it.id == menuChannel.id }?.expanded,
            canJoin = menuChannel.id != server.selfChannelId,
        )
        else -> null
    }
    return ConnectedUiState(
        tab = local.tab,
        serverName = serverName,
        peopleOnline = server.people.size,
        rows = rows,
        searching = local.searching,
        query = local.query,
        reconnecting = session.phase as? ConnectionPhase.Reconnecting,
        denied = session.denied?.let { denied ->
            DeniedBanner(denied.channelId?.let(server.channels::get)?.name, denied.detail)
        },
        hints = session.hints,
        microphone = microphone.takeIf { it == MicrophoneNotice.Denied || it == MicrophoneNotice.Blocked }
            ?: MicrophoneNotice.None,
        sheet = sheet,
    )
}
