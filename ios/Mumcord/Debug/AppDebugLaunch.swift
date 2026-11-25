// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge
import UIKit

/// Lets a debug build run against the fake engine with canned data, so UI tests and screenshot
/// tests can walk through the app without a server:
///
///     -mumcordFakeEngine <options>   comma-separated, `plain` for none of them:
///         servers          the server list of the mockup, with ping answers
///         prompt-unknown   connecting asks whether to trust an unknown certificate
///         prompt-changed   connecting warns about a changed certificate
///         unreachable      the first connect fails because the server cannot be reached
///         server-password  the server only accepts the password "open sesame"
///         locks            the Music channel needs the password "secret", AFK refuses entry
///         voice-server     everybody is in the Lobby, as in the mockup of the voice view
///         ptt              the saved talk mode is push-to-talk
///         self-muted       the user is muted
///         level            the microphone level is 60 % once connected
///         mic-ask          the microphone has not been asked for; the user allows it
///         mic-refuse       the microphone has not been asked for; the user refuses it
///         mic-declined     the user has put the microphone explanation away with "Not now"
///         localnet         Nordlicht is on the local network and that was not explained yet
///         localnet-refused Nordlicht is on the local network and iOS refuses access to it
///         chat             the Lobby chat of the mockup, GIF favourites and a hint
///         chat-unread      two messages of the Lobby chat have not been read
///         chat-misleading  the link in Mira's message names another address than it opens
///         chat-reply       the Chat tab is replying to Anna
///         chat-mention     the Chat tab shows the `@` suggestions
///         chat-link-warning the Chat tab warns about a misleading link
///         chat-viewer      the Chat tab shows the picture of Ben's message large
///         chat-gifs        the Chat tab shows the GIF favourites
///         session-ended    the last run was closed in the middle of a session with Nordlicht
///         outputs          the phone has a loudspeaker and an earpiece and plays on the earpiece
///         output-bluetooth the Bluetooth headset "Lea's Buds" is connected as well
///         output-ask       no default output is saved, so the first connection asks
///         alone            the user is alone in the Lobby; the others are in Gaming
///         idle-on          "Disconnect when alone for 30 minutes" is switched on
///         idle-short       "Disconnect when alone" warns after 3 s and disconnects 3 s later
///         metered          the phone is on mobile data (a metered network), not on Wi-Fi
///         activity         the activity log of the mockup: yesterday evening and today
///         activity-live    a second after the activity log was opened Ben joins the Lobby; a
///                          second after it was left again Mira stops her screen share
///         no-identity      the phone has no identity yet, so the first start asks for one
///         two-identities   besides the default identity "Lea" there is "Work"
///         file-password    every identity file has the password "secret" (otherwise none)
///         qr-parts         the scanner sees the QR codes of the desktop's fake transfer
///                          (`FakeDesktopTransfer`) instead of a camera picture; without it
///                          the scanner sees nothing
///         transfer-slow    checking the typed code takes half a minute
///         transfer-expired the first QR code says the transfer is older than two minutes
///         transfer-import-fails the import fails once, as with a locked secure storage
///         camera-ask       the camera has not been asked for; the user allows it
///         camera-refuse    the camera has not been asked for; the user refuses it
///         camera-blocked   the camera was refused before; only the system settings help
///         admin            the server grants the user every right on every channel
///         moderation-refused the server refuses the next moderation command
///         ban-quick        the server's ban list cannot be read: only its default ban
///         ban-conflict     the first ban finds the server's entry changed meanwhile
///         streams          Mira shares her screen (with sound, one viewer) once connected
///         stream-camera    Mira shares her camera instead of her screen
///         stream-crowd     three people watch Mira's screen
///         stream-question  the first watch asks whether direct connections are allowed
///         stream-unreachable the first watch ends with "Stream not reachable from this network";
///                          trying again gets through
///         stream-colours   the stream's picture is red on the left half and blue on the right,
///                          instead of the picture of the mockups
///         stream-one-frame the stream sends a single picture and nothing new afterwards
///         camera-refused   "Start sharing" of the own camera is refused: direct connections
///                          are off
///         camera-slow-start "Start sharing" of the own camera takes half a minute
///         camera-viewers   two people watch the own camera as soon as it is shared
///         camera-fails     the camera stops working as soon as it is shared
///         camera-probe     an invisible element "fake-camera" lists what the drawn camera
///                          was asked to do (`FakeCameraProbeView`)
///         public           the public server list of `PublicServersMockup`, with ping answers;
///                          without it the list is empty. Never the real list service.
///         public-lantern   the server list holds only Lantern Guild, which is also listed
///         public-slow      downloading the public list takes half a minute
///         public-down      every download of the public list fails
///         public-down-first the first download of the public list fails
///         public-refresh-fails every download after the first that worked fails
///         public-ping-slow a listed server takes ten seconds to answer a ping
///         public-probe     an invisible element "fake-public-list" says how often the list was
///                          downloaded and the listed servers pinged (`FakePublicListProbeView`)
///         profile          the user's comment on Nordlicht is the one of the mockup
///         profile-avatar   the user has a profile picture on Nordlicht
///         profile-save-fails saving the comment fails once
///         profile-short-limit the server takes comments of at most 20 characters
///         own-theme        the user has a theme of their own, "From the desktop"
///         theme-ocean      the app theme is the built-in "Deep Ocean"
///     -mumcordIdentities <preset>    put the identity screens into a state (`IdentitiesPreset`)
///     -mumcordSetupDone YES          skip the first-start setup
///     -mumcordTheme dark|light       force a theme (default: the system's)
///
/// Nothing is stored: the setup flag lives in memory. Release builds do not contain this.
struct AppDebugLaunch {
    let options: Set<String>
    let identitiesPreset: String?
    let setupDone: Bool
    let theme: MumcordThemeMode

    /// Launch arguments of the form `-key value` are readable as user defaults.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> AppDebugLaunch? {
        guard let options = defaults.string(forKey: "mumcordFakeEngine") else { return nil }
        return AppDebugLaunch(
            options: Set(options.split(separator: ",").map(String.init)),
            identitiesPreset: defaults.string(forKey: "mumcordIdentities"),
            setupDone: defaults.bool(forKey: "mumcordSetupDone"),
            theme: defaults.string(forKey: "mumcordTheme").flatMap(MumcordThemeMode.init(rawValue:)) ?? .system
        )
    }

    var engineFactory: EngineFactory {
        let options = options
        return { listener in
            var prompt: CertificatePrompt?
            if options.contains("prompt-unknown") {
                prompt = CertificatePrompt(fingerprint: Mockup.fingerprint, previousFingerprint: nil)
            } else if options.contains("prompt-changed") {
                prompt = CertificatePrompt(
                    fingerprint: Mockup.fingerprint,
                    previousFingerprint: Mockup.previousFingerprint
                )
            }
            var server = options.contains("voice-server") ? mockupVoiceServer() : mockupServer()
            if options.contains("chat-requests") {
                server.people = server.people.map { person in
                    var changed = person
                    changed.supportsChatRequests = !person.isSelf
                    return changed
                }
            }
            if options.contains("alone") {
                server.people = server.people.map { person in
                    var changed = person
                    if !person.isSelf { changed.channelId = Mockup.gaming }
                    return changed
                }
            }
            if options.contains("self-muted") {
                server.people = server.people.map { person in
                    var changed = person
                    if person.isSelf { changed.selfMuted = true }
                    return changed
                }
            }
            if options.contains("admin") {
                server = server.granting { $0.grantAllRights() }
            }
            if options.contains("stream-camera") {
                server.people = server.people.map { person in
                    var changed = person
                    if person.session == Mockup.mira {
                        changed.streaming = false
                        changed.camera = true
                    }
                    return changed
                }
            }
            var servers = options.contains("servers") ? Mockup.servers : []
            if options.contains("public-lantern") { servers = PublicServersMockup.savedLantern }
            let engine = FakeEngine(
                listener: listener,
                server: server,
                prompt: prompt,
                servers: servers
            )
            engine.pings = Mockup.pings
            Self.fakePublicList(engine, options)
            if options.contains("messages") {
                MessagesMockup.fill(engine)
            }
            if options.contains("unreachable") {
                engine.connectFailure = .ServerUnreachable(detail: "unreachable")
            }
            if options.contains("server-password") {
                engine.requiredPassword = "open sesame"
            }
            if options.contains("ptt") {
                engine.savedTalkMode = .pushToTalk
            }
            if options.contains("output-ask") {
                engine.storedMobileSettings.defaultOutput = .ask
            }
            if options.contains("idle-on") {
                engine.storedMobileSettings.idleDisconnect = true
            }
            if options.contains("activity") {
                engine.activityOnConnect = ActivityMockup.entries()
            }
            if options.contains("level") {
                engine.levelOnConnect = 60
            }
            if options.contains("chat") {
                var messages = ChatMockup.messages()
                if options.contains("chat-misleading") {
                    var run = span("nordlicht.example")
                    run.link = ChatMockup.misleadingLink
                    messages[2].content = messageContent(blocks: [.paragraph(spans: [span("Rules: "), run])])
                }
                engine.messages = [ChatMockup.lobby: messages]
                engine.mentionNames = ChatMockup.mentionNames
                engine.storedGifFavourites = ChatMockup.gifFavourites
                engine.chatImages = ChatMockup.chatImages()
                engine.remoteImages = ChatMockup.remoteImages()
                if options.contains("chat-unread") {
                    engine.unread = [ChatMockup.lobby: 2]
                }
            }
            if options.contains("no-identity") {
                engine.identities = []
            } else if options.contains("two-identities") {
                engine.identities = [fakeIdentity, workIdentity]
            }
            if options.contains("file-password") {
                engine.filePassword = "secret"
            }
            let transfer = FakeDesktopTransfer.scan(for: engine)
            if options.contains("transfer-slow") {
                transfer.openDelay = .seconds(30)
            }
            if options.contains("transfer-expired") {
                transfer.failNext = .Expired
            }
            if options.contains("transfer-import-fails") {
                transfer.importFailure = .SecureStorageUnavailable(detail: "locked")
            }
            engine.transfer = transfer
            if options.contains("moderation-refused") {
                engine.moderationFailure = .PermissionDenied(detail: Mockup.refusal)
            }
            if options.contains("ban-quick") {
                engine.banTargetAnswer.banListReadable = false
            }
            if options.contains("ban-conflict") {
                engine.banResults = [.conflict]
            }
            if options.contains("locks") {
                engine.channelPasswords = [Mockup.music: "secret"]
                engine.deniedChannels = [Mockup.afk]
            }
            if options.contains("streams") {
                engine.streamsOnConnect = [Self.stream(options)]
            }
            if options.contains("stream-question") {
                engine.asksDirectConnections = true
            }
            if options.contains("stream-unreachable") {
                engine.firstWatchOutcome = .unreachable(reason: .noPath)
            }
            if options.contains("camera-refused") {
                engine.cameraStartProblem = .directConnectionsOff
            }
            if options.contains("camera-slow-start") {
                engine.cameraStartDelay = .seconds(30)
            }
            Self.prepareProfileThemes(engine.profileThemes, options)
            if options.contains("profile-short-limit") {
                let limits = engine.limits
                engine.limits = ChatLimits(text: 20, image: limits.image, allowHtml: limits.allowHtml)
            }
            return engine
        }
    }
}

extension AppDebugLaunch {
    /// The public server list and its failures as the options describe them.
    private static func fakePublicList(_ engine: FakeEngine, _ options: Set<String>) {
        if options.contains("public") {
            engine.publicServers = PublicServersMockup.servers
            engine.publicPings = PublicServersMockup.pings
        }
        if options.contains("public-slow") {
            engine.publicDownloadDelay = .seconds(30)
        }
        if options.contains("public-ping-slow") {
            engine.publicPingDelay = .seconds(10)
        }
        // Enough downloads for any test; the numbers count from 1.
        let downloads = 1...100
        var failing: Set<Int> = []
        if options.contains("public-down") {
            failing.formUnion(downloads)
        }
        if options.contains("public-down-first") {
            failing.insert(1)
        }
        if options.contains("public-refresh-fails") {
            let firstWorking = downloads.first { !failing.contains($0) } ?? 1
            failing.formUnion(downloads.filter { $0 > firstWorking })
        }
        engine.failingPublicDownloads = failing
    }

    /// The comment of the edit profile mockup.
    static let mockupComment = "Playing late, always up for a chat. Ask me about the new map!"

    /// The profile and the themes as the options describe them.
    static func prepareProfileThemes(_ fake: FakeProfileThemes, _ options: Set<String>) {
        if options.contains("profile") {
            fake.profile.commentText = mockupComment
        }
        if options.contains("profile-avatar") {
            let picture = mockupAvatar()
            fake.profile.avatar = ImageData(bytes: picture, mime: "image/png", width: 96, height: 96, animated: false)
        }
        if options.contains("profile-save-fails") {
            fake.saveFailure = .Io(detail: "the connection dropped")
        }
        if options.contains("own-theme") {
            _ = fake.addCustom(MockupThemes.fromTheDesktop)
        }
        if options.contains("theme-ocean") {
            fake.settings.appThemeId = MockupThemes.oceanId
        }
    }

    /// A profile picture drawn here: a warm circle on a cool background.
    static func mockupAvatar() -> Data {
        let size = CGSize(width: 96, height: 96)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(red: 0.16, green: 0.42, blue: 0.62, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor(red: 0.98, green: 0.69, blue: 0.47, alpha: 1).setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 24, y: 16, width: 48, height: 48))
        }
        return image.pngData() ?? Data()
    }

    /// Mira's stream as the options describe it.
    static func stream(_ options: Set<String>) -> MumcordBridge.Stream {
        if options.contains("stream-camera") {
            return mockupStream(viewers: nil, hasSound: false, kind: .camera)
        }
        return mockupStream(viewers: options.contains("stream-crowd") ? 3 : 1)
    }

    /// The pictures of watched streams, made here (`FakeVideoFrames`).
    var streamVideo: StreamVideo {
        StreamVideo(frames: FakeVideoFrames(
            options.contains("stream-colours") ? .colours : .mockup,
            once: options.contains("stream-one-frame")
        ))
    }

    /// The microphone as the options describe it; allowed when they say nothing.
    var microphoneGateway: FakeMicrophoneGateway {
        if options.contains("mic-declined") {
            return FakeMicrophoneGateway(permission: .undetermined, declined: true)
        }
        if options.contains("mic-ask") || options.contains("mic-refuse") {
            let gateway = FakeMicrophoneGateway(permission: .undetermined)
            gateway.answer = options.contains("mic-ask")
            return gateway
        }
        return FakeMicrophoneGateway()
    }

    /// Notifications as the options describe them: allowed when they say nothing, never asked
    /// for with "notif-ask", refused with "notif-denied". The system's own question never shows.
    var notificationGateway: FakeNotificationGateway {
        if options.contains("notif-ask") {
            return FakeNotificationGateway(granted: false, asked: false)
        }
        if options.contains("notif-denied") {
            return FakeNotificationGateway(granted: false, asked: true)
        }
        return FakeNotificationGateway(granted: true, asked: true)
    }

    /// The phone's audio outputs as the options describe them.
    var outputDevice: FakeOutputDevice {
        var available = [
            AudioOutput(id: OutputId.speaker, kind: .speaker, name: ""),
            AudioOutput(id: OutputId.earpiece, kind: .earpiece, name: ""),
        ]
        if options.contains("output-bluetooth") {
            available.append(AudioOutput(id: "buds", kind: .bluetooth, name: "Lea's Buds"))
        }
        return FakeOutputDevice(OutputState(available: available, current: available[1]))
    }

    /// The camera as the options describe it; allowed when they say nothing.
    var cameraGateway: FakeCameraGateway {
        if options.contains("camera-blocked") {
            return FakeCameraGateway(permission: .denied)
        }
        if options.contains("camera-ask") || options.contains("camera-refuse") {
            return FakeCameraGateway(permission: .undetermined, answer: options.contains("camera-ask"))
        }
        return FakeCameraGateway()
    }

    /// The QR texts the scanner sees; the simulator has no camera to show a code to.
    var qrSource: ScriptedQrSource {
        ScriptedQrSource(texts: options.contains("qr-parts") ? FakeDesktopTransfer.parts : [])
    }

    /// The local network as the options describe it; every server is on the internet when they
    /// say nothing.
    var localNetworkGateway: FakeLocalNetworkGateway {
        let refused = options.contains("localnet-refused")
        guard refused || options.contains("localnet") else { return FakeLocalNetworkGateway() }
        let address = IpAddress(bytes: [192, 168, 1, 20])
        return FakeLocalNetworkGateway(
            prefixes: [IpPrefix(address: address, length: 24)],
            addresses: [Mockup.localAddress: [address]],
            explained: refused,
            refused: refused
        )
    }

    /// The app against the fake engine. Its audio never touches the phone's speaker or
    /// microphone, and nothing asks the system for a permission.
    /// The network the app is told about.
    var network: NetworkStatus {
        options.contains("metered") ? fakeMobileData : fakeWifi
    }

    /// "Disconnect when alone" with the product's times, or shortened for a test (AC-5.30).
    var idleTimes: IdleTimes {
        options.contains("idle-short") ? IdleTimes(alone: .seconds(3), warning: .seconds(3)) : IdleTimes()
    }

    /// Lives as long as the process, as in the app on the real engine.
    @MainActor private static var networkSession: NetworkSession?
    /// What the drawn camera was asked to do, with the option `camera-probe`.
    @MainActor private(set) static var cameraProbe: FakeCameraProbe?
    /// What the fake engine did with the public list, with the option `public-probe`.
    @MainActor private(set) static var publicListProbe: FakePublicListProbe?

    @MainActor
    func makeModel() -> AppModel {
        let factory = engineFactory
        let engine = Observed<FakeEngine?>(nil)
        let publicProbe = options.contains("public-probe") ? FakePublicListProbe() : nil
        Self.publicListProbe = publicProbe
        let store = SessionStore(
            engineFactory: { listener in
                let created = try factory(listener)
                let fake = created as? FakeEngine
                engine.set(fake)
                if let publicProbe, let fake {
                    fake.onPublicActivity = { activity in
                        Task { @MainActor in publicProbe.take(activity) }
                    }
                    fake.reportPublicActivity()
                }
                return created
            },
            log: AppLog.write
        )
        let monitor = FakeNetworkMonitor(network)
        Self.networkSession = NetworkSession(store: store, monitor: monitor)
        let idleTimes = idleTimes
        // The simulator's camera films nothing a test can check: the shared camera is a drawn one.
        let probe = FakeCameraProbe()
        if options.contains("camera-probe") { Self.cameraProbe = probe }
        let cameraShare = CameraShare(store: store) { onTrouble in
            let source = FakeCameraSource(onTrouble: onTrouble)
            probe.source = source
            return source
        }
        let model = AppModel(
            store: store,
            setupStore: InMemorySetupStore(firstStartDone: setupDone),
            microphone: VoiceAudio(
                store: store,
                gateway: microphoneGateway,
                backend: FakeStreamBackend(),
                session: FakeVoiceSession(),
                scheduler: QueueAudioScheduler(),
                log: AppLog.write
            ),
            localNetwork: localNetworkGateway,
            openSettings: {},
            sessionEnded: options.contains("session-ended")
                ? SessionEndedNotice(serverId: Mockup.servers[0].id, serverName: Mockup.servers[0].name)
                : nil,
            notifications: NotificationPrompt(gateway: notificationGateway),
            outputs: options.contains("outputs")
                ? VoiceOutput(store: store, outputs: SessionOutputs(device: outputDevice))
                : nil,
            idle: IdleDisconnect(
                store: store,
                notifier: SystemIdleNotifier(texts: Strings.idleNotificationTexts, log: AppLog.write),
                times: { idleTimes }
            ),
            mediaPolicy: { loadMedia(monitor.status, store.state.mobileSettings) },
            camera: cameraGateway,
            qrSource: qrSource,
            network: monitor,
            streamVideo: streamVideo,
            cameraShare: cameraShare,
            languages: .system
        )
        // The real settings app, so that a test sees the language row leave the app.
        model.more.openLanguageSettings = AppModel.openSystemSettings
        if options.contains("camera-viewers") || options.contains("camera-fails") {
            let options = options
            whileSharing(model) {
                if options.contains("camera-viewers") { engine.value?.watchCamera(2) }
                if options.contains("camera-fails") { probe.source?.trouble(.failed) }
            }
        }
        if let identitiesPreset {
            IdentitiesPreset.apply(identitiesPreset, to: model.identities)
        }
        if options.contains("chat") {
            presetChat(model, store)
        }
        if options.contains("activity-live") {
            reportLiveActivity(model, engine)
        }
        return model
    }

    /// Runs `change` as soon as the own camera is shared.
    @MainActor
    private func whileSharing(_ model: AppModel, _ change: @escaping @MainActor () -> Void) {
        Task { @MainActor in
            while model.cameraShare?.uiState.sharing == nil {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            change()
        }
    }

    /// Lets the engine report activity at the moments a test waits for it: while the log is
    /// open, and after it was left again.
    @MainActor
    private func reportLiveActivity(_ model: AppModel, _ engine: Observed<FakeEngine?>) {
        Task { @MainActor in
            for (page, entry) in [(MorePage.activity, ActivityMockup.benJoins), (.main, ActivityMockup.miraStops)] {
                while model.more.local.page != page {
                    try? await Task.sleep(nanoseconds: 50_000_000)
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                engine.value?.emit(.activityAdded(entry: entry(Date())))
            }
        }
    }

    /// Puts the Chat tab into the state the options name once its messages are there, through
    /// the same calls the screen makes, so screenshot tests reach states that need no typing.
    @MainActor
    private func presetChat(_ model: AppModel, _ store: SessionStore) {
        let options = options
        Task { @MainActor in
            _ = await store.first { $0.showsServer }
            // Nothing in the engine reports hints yet; the mockup shows one.
            store.showHint(UnsupportedHint(id: 1, personName: "Jonas", kind: .fileTransfer))
            // The sheets cover the tab bar, so they wait until the test has opened the Chat tab.
            while model.chat.local.messages.isEmpty || model.connectedLocal.tab != .chat {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            let chat = model.chat
            if options.contains("chat-reply") {
                chat.startReply(ChatMockup.annaAsks())
                chat.changeDraft("On it", cursor: 5)
            }
            if options.contains("chat-mention") {
                chat.changeDraft("Thanks @", cursor: 8)
            }
            if options.contains("chat-link-warning") {
                chat.tapLink(ChatMockup.misleadingLink)
            }
            if options.contains("chat-viewer") {
                chat.openViewer(.embedded(
                    chat: ChatMockup.lobby,
                    messageId: ChatMockup.mapMessageId,
                    imageId: ChatMockup.mapImageId,
                    width: ChatMockup.mapSize.width,
                    height: ChatMockup.mapSize.height
                ))
            }
            if options.contains("chat-gifs") {
                chat.openGifs()
            }
        }
    }
}

/// Keeps the first-start flag for one run of the app.
final class InMemorySetupStore: SetupStore {
    private(set) var firstStartDone: Bool

    init(firstStartDone: Bool = false) {
        self.firstStartDone = firstStartDone
    }

    func markFirstStartDone() {
        firstStartDone = true
    }
}
#endif
