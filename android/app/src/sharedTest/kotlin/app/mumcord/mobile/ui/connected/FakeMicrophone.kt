// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.connected

import kotlinx.coroutines.flow.MutableStateFlow

/** A phone whose microphone permission and remembered answers are set by the test. */
class FakeMicrophoneGateway(var granted: Boolean = false) : MicrophoneGateway {
    override var declined = false
    override var promptBlocked = false

    override fun granted(): Boolean = granted
}

/** The microphone question without any audio behind it; the test sets what the screens show. */
class FakeMicrophoneAccess(notice: MicrophoneNotice = MicrophoneNotice.None) : MicrophoneAccess {
    override val notice = MutableStateFlow(notice)
    val answers = mutableListOf<MicrophoneAnswer>()
    var rechecks = 0

    override fun notNow() {
        notice.value = MicrophoneNotice.Denied
    }

    override fun answer(answer: MicrophoneAnswer) {
        answers += answer
        notice.value = when {
            answer.granted -> MicrophoneNotice.None
            answer.blocksPrompt -> MicrophoneNotice.Blocked
            else -> MicrophoneNotice.Denied
        }
    }

    override fun recheck() {
        rechecks += 1
    }
}
