// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connected

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import kotlinx.coroutines.delay

const val TALKING_ANNOUNCEMENT_TEST_TAG = "talking-announcement"

/** True while a screen reader that explores by touch (TalkBack) is on; follows changes. */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * Says through the screen reader who starts talking in the user's channel, as decided by
 * [TalkingAnnouncer]. A polite live region of one dp carries the sentence and is emptied again
 * after the gap, so the same sentence later is a change TalkBack reads again. Without a screen
 * reader nothing runs at all, which keeps the phone idle while nobody listens (FR-5.43).
 */
@Composable
fun TalkingAnnouncements(
    tiles: List<VoiceTile>,
    screenReaderOn: Boolean = rememberScreenReaderOn(),
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    if (!screenReaderOn) return
    val others = tiles.filter { !it.isSelf && it.talking }.associate { it.session to it.name }
    val announcer = remember { TalkingAnnouncer() }
    var names by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(others) {
        while (true) {
            val now = clock()
            announcer.update(now, others).takeIf { it.isNotEmpty() }?.let { names = it }
            val next = announcer.nextUpdateAt ?: break
            delay(next - now)
        }
    }
    LaunchedEffect(names) {
        if (names.isNotEmpty()) {
            delay(TALKING_ANNOUNCEMENT_GAP_MILLIS)
            names = emptyList()
        }
    }
    val text = when (names.size) {
        0 -> ""
        1 -> stringResource(R.string.talking_announcement_one, names[0])
        2 -> stringResource(R.string.talking_announcement_two, names[0], names[1])
        else -> pluralStringResource(R.plurals.talking_announcement_more, names.size - 1, names[0], names.size - 1)
    }
    Box(
        Modifier
            .size(1.dp)
            .testTag(TALKING_ANNOUNCEMENT_TEST_TAG)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (text.isNotEmpty()) contentDescription = text
            },
    )
}
