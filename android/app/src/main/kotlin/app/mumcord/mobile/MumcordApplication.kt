// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.app.Application
import android.util.Log
import app.mumcord.mobile.session.BridgeEngineFactory
import app.mumcord.mobile.platform.audio.AndroidVoiceSession
import app.mumcord.mobile.platform.audio.AudioController
import app.mumcord.mobile.platform.audio.NativeAudio
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.VoiceAudio
import app.mumcord.mobile.ui.connect.AndroidLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkGateway
import app.mumcord.mobile.ui.connected.AndroidMicrophoneGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns what has to live as long as the process: the engine may exist only once per process and
 * a connection must survive the Activity being recreated (rotation, theme change).
 */
class MumcordApplication : Application() {
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            engineFactory = BridgeEngineFactory(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            engineDispatcher = Dispatchers.IO,
            log = { message -> Log.w(LOG_TAG, message) },
        )
    }

    val localNetworkGateway: LocalNetworkGateway by lazy { AndroidLocalNetworkGateway(this) }

    /** The phone's audio for voice. It follows the session by itself once it has been created. */
    val voiceAudio: VoiceAudio by lazy {
        val log: (String) -> Unit = { message -> Log.w(LOG_TAG, message) }
        val microphone = AndroidMicrophoneGateway(this)
        VoiceAudio(
            repository = sessionRepository,
            controller = AudioController(
                backend = NativeAudio,
                session = AndroidVoiceSession(this, log),
                microphoneGranted = microphone::granted,
                // One thread: opening and closing streams never overlap, and never run on the
                // main thread.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
                log = log,
            ),
            gateway = microphone,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private companion object {
        const val LOG_TAG = "Mumcord"
    }
}
