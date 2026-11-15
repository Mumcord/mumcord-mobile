// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import app.mumcord.mobile.bridge.engineVersion
import app.mumcord.mobile.platform.audio.AndroidFeedbackTones
import app.mumcord.mobile.platform.audio.AndroidVoiceSession
import app.mumcord.mobile.platform.audio.AudioController
import app.mumcord.mobile.platform.audio.NativeAudio
import app.mumcord.mobile.platform.network.AndroidNetworkMonitor
import app.mumcord.mobile.platform.network.NetworkMonitor
import app.mumcord.mobile.platform.notify.AndroidIdleNotifier
import app.mumcord.mobile.platform.notify.AndroidMessageNotifier
import app.mumcord.mobile.session.BridgeEngineFactory
import app.mumcord.mobile.session.EngineCalls
import app.mumcord.mobile.session.EngineMessageNotificationSettings
import app.mumcord.mobile.session.IdleDisconnect
import app.mumcord.mobile.session.MediaPolicy
import app.mumcord.mobile.session.MessageNotificationCenter
import app.mumcord.mobile.session.MessageNotificationSettings
import app.mumcord.mobile.session.NetworkSession
import app.mumcord.mobile.session.PreferencesStoredSenderOnly
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.session.VoiceAudio
import app.mumcord.mobile.session.VoiceCall
import app.mumcord.mobile.session.VoiceOutput
import app.mumcord.mobile.session.loadMedia
import app.mumcord.mobile.ui.camera.AndroidCameraGateway
import app.mumcord.mobile.ui.camera.CameraGateway
import app.mumcord.mobile.ui.chat.BridgeImageLoader
import app.mumcord.mobile.ui.chat.ChatImageLoader
import app.mumcord.mobile.ui.connect.AndroidLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkGateway
import app.mumcord.mobile.ui.connected.AndroidMicrophoneGateway
import app.mumcord.mobile.ui.connected.AndroidNotificationGateway
import app.mumcord.mobile.update.AppInfo
import app.mumcord.mobile.update.GitHubReleaseSource
import app.mumcord.mobile.update.PreferencesUpdateStore
import app.mumcord.mobile.update.UpdateChecker
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
            engineDispatcher = EngineCalls.newDispatcher(),
            log = log,
            mediaPolicy = MediaPolicy { loadMedia(networkMonitor.status.value, sessionRepository.state.value.mobileSettings) },
            checkThreads = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    val chatImageLoader: ChatImageLoader by lazy {
        BridgeImageLoader(sessionRepository.chat, log = { message -> Log.w(LOG_TAG, message) })
    }

    /** The versions the About screen names (FR-5.100); the engine's is read from the bridge library. */
    val appInfo: AppInfo by lazy {
        AppInfo(
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            engineVersion = engineVersion(),
            debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    /** The daily Android update check (FR-5.101); debug and test builds never ask. */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(appInfo, GitHubReleaseSource(), PreferencesUpdateStore(this), log = log)
    }

    val localNetworkGateway: LocalNetworkGateway by lazy { AndroidLocalNetworkGateway(this) }

    val cameraGateway: CameraGateway by lazy { AndroidCameraGateway(this) }

    private val log: (String) -> Unit = { message -> Log.w(LOG_TAG, message) }

    private val microphone by lazy { AndroidMicrophoneGateway(this) }

    private val audioController: AudioController by lazy {
        AudioController(
            backend = NativeAudio,
            session = AndroidVoiceSession(this, log) { change -> voiceCall.onAudioFocus(change) },
            microphoneGranted = microphone::granted,
            // One thread: opening and closing streams never overlap, and never run on the
            // main thread.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
            log = log,
        )
    }

    /** The phone's audio for voice. It follows the session by itself once it has been created. */
    val voiceAudio: VoiceAudio by lazy {
        VoiceAudio(
            repository = sessionRepository,
            controller = audioController,
            gateway = microphone,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /** The session in the background and as a call. It follows the session by itself too. */
    val voiceCall: VoiceCall by lazy {
        VoiceCall(
            context = this,
            repository = sessionRepository,
            audio = audioController,
            notificationGateway = notificationGateway,
            content = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_VOICE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            log = log,
            tones = AndroidFeedbackTones(log),
            treatOutputAsHeadset = { TestSettings.treatOutputAsHeadset },
        )
    }

    /** Where the voice is heard and the first-connect question. It follows the session by itself too. */
    val voiceOutput: VoiceOutput by lazy {
        VoiceOutput(
            repository = sessionRepository,
            outputs = voiceCall.outputs,
            // One thread: the outputs are followed in the order they changed.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        )
    }

    private val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(this) }

    /** The bitrate limit on mobile data and getting back in when a network returns. It follows the session by itself too. */
    val networkSession: NetworkSession by lazy {
        NetworkSession(sessionRepository, networkMonitor, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    /** "Disconnect when alone" with its two notifications. It follows the session by itself too. */
    val idleDisconnect: IdleDisconnect by lazy {
        IdleDisconnect(
            repository = sessionRepository,
            notifier = AndroidIdleNotifier(
                context = this,
                open = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
                onStay = { idleDisconnect.stayConnected() },
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // Goes on while the phone sleeps.
            clock = SystemClock::elapsedRealtime,
            times = { TestSettings.idleTimes },
        )
    }

    /** Notifications for private messages and mentions. It follows the session by itself too. */
    val messageNotifications: MessageNotificationCenter by lazy {
        MessageNotificationCenter(
            repository = sessionRepository,
            notifier = AndroidMessageNotifier(this) { key ->
                PendingIntent.getActivity(
                    this,
                    0,
                    // The identifier makes the intents of two conversations different ones.
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_CHAT)
                        .setIdentifier(key)
                        .putExtra(MainActivity.EXTRA_CHAT, key),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            },
            settings = messageNotificationSettings,
            permitted = notificationGateway::granted,
            // One thread: notifications are shown and removed in the order things happened.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        )
    }

    val messageNotificationSettings: MessageNotificationSettings by lazy {
        EngineMessageNotificationSettings(
            repository = sessionRepository,
            stored = PreferencesStoredSenderOnly(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private val notificationGateway by lazy { AndroidNotificationGateway(this) }

    private companion object {
        const val LOG_TAG = "Mumcord"
    }
}
