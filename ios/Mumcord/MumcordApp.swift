// SPDX-License-Identifier: AGPL-3.0-only

import os
import SwiftUI
import UIKit

@main
struct MumcordApp: App {
    // Says which screen orientations are allowed (the stream's landscape button).
    @UIApplicationDelegateAdaptor(MumcordAppDelegate.self) private var appDelegate
    // Decided once at start: the engine must exist only once per process.
    private let model: AppModel?
    private let theme: MumcordThemeMode
    #if DEBUG
    private let designLaunch: DesignDebugLaunch?
    private let splitWidth: CGFloat?
    #endif

    init() {
        MumcordFonts.register()
        #if DEBUG
        // Debug builds can be started on a screen of the design system (see DesignDebugLaunch)
        // or against the fake engine (see AppDebugLaunch).
        designLaunch = DesignDebugLaunch.fromLaunchArguments()
        let launch = designLaunch == nil ? AppDebugLaunch.fromLaunchArguments() : nil
        splitWidth = launch?.splitWidth
        if designLaunch != nil {
            model = nil
            theme = .system
        } else if let launch {
            model = launch.makeModel()
            theme = launch.theme
        } else {
            model = .live
            theme = .system
        }
        #else
        model = .live
        theme = .system
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if let designLaunch {
                DesignDebugRoot(launch: designLaunch)
            } else if let model {
                AppRoot(model: model, theme: theme)
                    .modifier(DebugSplitWidth(width: splitWidth))
                    .overlay(alignment: .topLeading) {
                        if let probe = AppDebugLaunch.cameraProbe { FakeCameraProbeView(probe: probe) }
                        if let probe = AppDebugLaunch.publicListProbe { FakePublicListProbeView(probe: probe) }
                    }
                    .onAppear {
                        if AccessibilityProbe.requested { AccessibilityProbe.install() }
                    }
            }
            #else
            if let model {
                AppRoot(model: model, theme: theme)
            }
            #endif
        }
    }
}

extension AppModel {
    /// The app on the real engine. Created once per process: the engine must exist only once.
    static let live: AppModel = {
        let store = SessionStore(engineFactory: BridgeEngineFactory.make, log: AppLog.write)
        let voiceSession = SystemVoiceSession(
            // A phone has an earpiece, a tablet has not.
            hasEarpiece: UIDevice.current.userInterfaceIdiom == .phone,
            log: AppLog.write
        )
        let outputs = SessionOutputs(device: voiceSession)
        let audio = VoiceAudio(
            store: store,
            gateway: SystemMicrophoneGateway(),
            backend: NativeAudio(),
            session: voiceSession,
            scheduler: QueueAudioScheduler(),
            log: AppLog.write
        )
        let call = VoiceCall(
            store: store,
            audio: audio,
            callSystem: CallSystems.make(log: AppLog.write),
            prepareAudio: { voiceSession.prepare() },
            markers: SessionMarkerStore(),
            tones: SystemFeedbackTones(log: AppLog.write),
            currentOutput: { outputs.state.current },
            log: AppLog.write
        )
        voiceSession.onSignal = { [weak call] in call?.onAudioSignal($0) }
        let notificationGateway = SystemNotificationGateway()
        let messageSettings = EngineMessageNotificationSettings(store: store)
        // Created at the start of the app: a tap on a notification that started the app is
        // delivered right after launch.
        let notifier = SystemMessageNotifier(newMessage: Strings.messageNew, log: AppLog.write)
        let network = SystemNetworkMonitor()
        networkSession = NetworkSession(store: store, monitor: network)
        let idle = IdleDisconnect(
            store: store,
            notifier: SystemIdleNotifier(texts: Strings.idleNotificationTexts, log: AppLog.write)
        )
        notifier.onResponse(category: SystemIdleNotifier.warningCategory) { action in
            if action == SystemIdleNotifier.stayAction { idle.stayConnected() }
        }
        // The shared camera's pictures go to the engine's C function.
        let cameraFrames = EngineCameraFrames(handle: { store.cameraHandle() })
        let cameraShare = CameraShare(store: store) { onTrouble in
            AVCameraSource(frames: cameraFrames, onTrouble: onTrouble, log: AppLog.write)
        }
        let model = AppModel(
            store: store,
            setupStore: UserDefaultsSetupStore(),
            microphone: audio,
            localNetwork: SystemLocalNetworkGateway(log: AppLog.write),
            openSettings: openSystemSettings,
            call: call,
            sessionEnded: call.takeSessionEndedNotice(),
            notifications: NotificationPrompt(gateway: notificationGateway),
            messageSettings: messageSettings,
            outputs: VoiceOutput(store: store, outputs: outputs),
            idle: idle,
            mediaPolicy: { loadMedia(network.status, store.state.mobileSettings) },
            camera: SystemCameraGateway(),
            qrSource: CameraQrSource(),
            network: network,
            streamVideo: StreamVideo(frames: EngineVideoFrames(handle: { store.videoHandle() })),
            cameraShare: cameraShare,
            languages: .system
        )
        model.more.openLanguageSettings = openSystemSettings
        notifier.onOpen { [weak model] key in
            if let chat = chatFromNotificationKey(key) { model?.openChat(chat) }
        }
        messageNotifications = MessageNotificationCenter(
            store: store,
            chats: model.chatStore,
            notifier: notifier,
            settings: messageSettings,
            permitted: { notificationGateway.granted() }
        )
        return model
    }()

    /// The app's page in the system settings: permissions and the app's language.
    static func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    /// Lives as long as the process: it turns the engine's message events into notifications.
    @MainActor private static var messageNotifications: MessageNotificationCenter?

    /// Lives as long as the process: it follows the phone's network for the session.
    @MainActor private static var networkSession: NetworkSession?
}

/// The app's technical log. Messages never contain names, addresses or other personal data.
enum AppLog {
    private static let logger = Logger(subsystem: "app.mumcord.mobile", category: "session")

    static let write: @Sendable (String) -> Void = { message in
        logger.error("\(message, privacy: .public)")
    }
}
