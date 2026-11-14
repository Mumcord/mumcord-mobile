// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

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

/**
 * Puts the phone into its voice-call audio mode, which also selects the echo canceller's
 * tuning and the call volume, and asks other apps to stop playing.
 */
class AndroidVoiceSession(context: Context, private val log: (String) -> Unit) : VoiceSession {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    private var previousMode: Int? = null

    override fun begin() {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // Voice runs also without the focus (for example during a phone call); what happens
        // then is decided by the call handling of a later task.
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            log("audio focus was not granted")
        }
    }

    override fun end() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        previousMode?.let { audioManager.mode = it }
        previousMode = null
    }
}
