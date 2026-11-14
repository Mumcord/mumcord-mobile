// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The short sounds that answer a headset's button (FR-5.35). */
enum class FeedbackTone(internal val firstHz: Double, internal val secondHz: Double) {
    /** Two notes going down: the microphone is off. */
    Muted(660.0, 440.0),

    /** Two notes going up: the microphone is on. */
    Unmuted(440.0, 660.0),
}

internal const val TONE_SAMPLE_RATE = 48_000
private const val NOTE_MILLIS = 70
private const val FADE_MILLIS = 8

/** Quiet next to voice: a tenth of full scale. */
private const val AMPLITUDE = 0.1

/**
 * The samples of a tone: two notes of [NOTE_MILLIS] each, faded in and out so that they do
 * not click.
 */
fun feedbackToneSamples(tone: FeedbackTone, sampleRate: Int = TONE_SAMPLE_RATE): ShortArray {
    val note = sampleRate * NOTE_MILLIS / 1000
    val fade = sampleRate * FADE_MILLIS / 1000
    return ShortArray(note * 2) { index ->
        val inNote = index % note
        val hz = if (index < note) tone.firstHz else tone.secondHz
        val envelope = min(1.0, min(inNote, note - 1 - inNote).toDouble() / fade)
        (sin(2 * PI * hz * inNote / sampleRate) * envelope * AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort()
    }
}

/** Plays a [FeedbackTone] to the user only; the others never hear it. */
fun interface FeedbackTones {
    fun play(tone: FeedbackTone)
}

/**
 * Plays the tones as part of the call's own audio (voice communication), so they come out
 * where the voice does, also on a Bluetooth headset. A tone is a tenth of a second long; it
 * has a player of its own that is given back as soon as it has played. Nothing here touches
 * the voice streams.
 */
class AndroidFeedbackTones(private val log: (String) -> Unit) : FeedbackTones {
    override fun play(tone: FeedbackTone) {
        val samples = feedbackToneSamples(tone)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TONE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(played: AudioTrack) = played.release()

                override fun onPeriodicNotification(played: AudioTrack) = Unit
            })
            track.play()
        } catch (failure: RuntimeException) {
            // No sound is no reason to fail what the button did.
            log("the feedback sound could not be played: ${failure.javaClass.simpleName}")
        }
    }
}
