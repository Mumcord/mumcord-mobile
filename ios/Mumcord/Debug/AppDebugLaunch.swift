// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

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
///     -mumcordSetupDone YES          skip the first-start setup
///     -mumcordTheme dark|light       force a theme (default: the system's)
///
/// Nothing is stored: the setup flag lives in memory. Release builds do not contain this.
struct AppDebugLaunch {
    let options: Set<String>
    let setupDone: Bool
    let theme: MumcordThemeMode

    /// Launch arguments of the form `-key value` are readable as user defaults.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> AppDebugLaunch? {
        guard let options = defaults.string(forKey: "mumcordFakeEngine") else { return nil }
        return AppDebugLaunch(
            options: Set(options.split(separator: ",").map(String.init)),
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
            if options.contains("self-muted") {
                server.people = server.people.map { person in
                    var changed = person
                    if person.isSelf { changed.selfMuted = true }
                    return changed
                }
            }
            let engine = FakeEngine(
                listener: listener,
                server: server,
                prompt: prompt,
                servers: options.contains("servers") ? Mockup.servers : []
            )
            engine.pings = Mockup.pings
            if options.contains("unreachable") {
                engine.connectFailure = .ServerUnreachable(detail: "unreachable")
            }
            if options.contains("server-password") {
                engine.requiredPassword = "open sesame"
            }
            if options.contains("ptt") {
                engine.savedTalkMode = .pushToTalk
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
            if options.contains("locks") {
                engine.channelPasswords = [Mockup.music: "secret"]
                engine.deniedChannels = [Mockup.afk]
            }
            return engine
        }
    }
}

extension AppDebugLaunch {
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
    @MainActor
    func makeModel() -> AppModel {
        let store = SessionStore(engineFactory: engineFactory, log: AppLog.write)
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
            openSettings: {}
        )
        if options.contains("chat") {
            presetChat(model, store)
        }
        return model
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
