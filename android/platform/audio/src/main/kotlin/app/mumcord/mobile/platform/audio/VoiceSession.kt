// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * What the phone has to be told while voice runs. The one place for it: audio routing, call
 * integration and the foreground service of later tasks belong here too.
 */
interface VoiceSession {
    /** Called before the first stream opens. */
    fun begin()

    /** Called after the last stream closed; puts everything back. */
    fun end()
}

/** What the phone says about the audio focus of the voice session. */
enum class AudioFocusChange {
    /** The session has the focus (again). */
    Gained,

    /** Something else plays for a moment (an alarm, a navigation prompt, a phone call). */
    LostTransient,

    /** As [LostTransient], and the session may go on more quietly. */
    LostCanDuck,

    /** Another app plays from now on; the focus does not come back by itself. */
    Lost,
}

/**
 * Puts the phone into its voice-call audio mode, which also selects the echo canceller's
 * tuning and the call volume, and asks other apps to stop playing.
 *
 * @param onFocusChange told on the main thread whenever the focus is gained or lost; what
 *   follows from it is decided elsewhere.
 */
class AndroidVoiceSession(
    context: Context,
    private val log: (String) -> Unit,
    private val onFocusChange: (AudioFocusChange) -> Unit = {},
) : VoiceSession {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusChange(AudioFocusChange.Gained)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusChange(AudioFocusChange.LostTransient)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusChange(AudioFocusChange.LostCanDuck)
            AudioManager.AUDIOFOCUS_LOSS -> onFocusChange(AudioFocusChange.Lost)
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
        // During a phone call the focus is not to be had; it is handed over when the call ends.
        .setAcceptsDelayedFocusGain(true)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    override fun begin() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // Voice runs also without the focus; what an interruption means is decided by the
        // call handling, which hears of every change.
        when (audioManager.requestAudioFocus(focusRequest)) {
            // A pause that began before the streams were closed ends here: no change is
            // reported for a focus that was given up and asked for again.
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> onFocusChange(AudioFocusChange.Gained)
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> log("audio focus will be granted later")
            else -> log("audio focus was not granted")
        }
    }

    override fun end() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        // The system keeps one mode per app and applies a change a moment later, so the mode
        // read back at the next begin could still be the voice mode of the session that just
        // ended, and "putting it back" would leave the phone in voice mode for good. Normal
        // withdraws this app's own wish; what other apps (a phone call) asked for stays.
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
