// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The part of the engine the app uses so far. A protocol of its own instead of the generated
/// one, so that a new command in the bridge does not have to be added to the fake engine before
/// a screen uses it. Every screen that needs a new command adds it here.
protocol EngineClient: AnyObject, Sendable {
    /// The saved servers in their order; later changes arrive as events.
    func savedServers() -> [SavedServer]
    func addServer(input: ServerInput) async throws -> SavedServer
    func updateServer(serverId: Int64, input: ServerInput) async throws -> SavedServer
    func removeServer(serverId: Int64) async throws
    func pingServer(serverId: Int64, refresh: Bool) async throws -> ServerPing?
    /// A page of the public server list (FR-5.9). The list is downloaded on the first call and
    /// with `query.refresh`, never in the background; a failed download keeps the last list.
    func publicServers(query: PublicServerQuery) async throws -> PublicServerPage
    /// People online, user limit and latency of a listed server, or nil when it does not answer.
    func pingPublicServer(host: String, port: UInt16) async throws -> ServerPing?
    func connectServer(serverId: Int64, password: String?, channelPath: [String]) async throws
    func answerCertificatePrompt(fingerprint: String, trust: Bool) async throws
    func disconnect() async throws
    func joinChannel(channelId: UInt32, password: String?) async throws
    func setPersonVolume(session: UInt32, percent: UInt16) async throws
    func setPersonLocalMute(session: UInt32, muted: Bool) async throws
    /// Moderation (FR-5.90 … FR-5.93). The three that change a person return true when the
    /// server confirmed the change and false when it did not answer in time.
    func movePerson(session: UInt32, channelId: UInt32) async throws -> Bool
    func setServerMute(session: UInt32, muted: Bool) async throws -> Bool
    func kickPerson(session: UInt32, reason: String?) async throws -> Bool
    /// What the ban sheet needs about a person; asks the server (up to a few seconds).
    func banTarget(session: UInt32) async throws -> BanTarget
    func banPerson(session: UInt32, ban: BanInput) async throws -> BanResult
    func talkMode() async throws -> TalkMode
    func setTalkMode(mode: TalkMode) async throws
    func audioEndpoints() -> AudioEndpoints
    func setPushToTalkPressed(pressed: Bool)
    func setSelfMute(muted: Bool) async throws
    func setSelfDeaf(deafened: Bool) async throws
    /// Limits the voice bitrate in kbit/s, nil for no limit; forgotten when the engine starts.
    func setBitrateLimitKbps(kbps: UInt32?)
    func startWhisper(target: WhisperTarget) async throws
    func stopWhisper()

    /// Unread messages per chat as they are when asked; later changes arrive as events.
    func chatUnreadSnapshot() -> [ChatUnread]
    func chatPage(chat: ChatId, before: String?, limit: UInt32) async throws -> ChatPage
    func sendMessage(chat: ChatId, text: String, replyTo: ReplyQuote?) async throws -> ChatMessage
    func sendImage(chat: ChatId, bytes: Data, mime: String) async throws -> ChatMessage
    func sendGifFavourite(chat: ChatId, url: String) async throws -> ChatMessage
    func chatImage(chat: ChatId, messageId: String, imageId: String) async throws -> ImageData
    func loadRemoteImage(url: String, loadMedia: Bool) async throws -> ImageData?
    func mentionCandidates(query: String, limit: UInt32) -> [MentionCandidate]
    func chatLimits() async throws -> ChatLimits
    func markChatRead(chat: ChatId) async throws
    func setOpenChat(chat: ChatId?)
    func gifFavourites() async throws -> [GifFavourite]
    func addGifFavourite(url: String) async throws
    func removeGifFavourite(url: String) async throws
    /// The stored private conversations of the connected server, newest first.
    func privateConversations() async throws -> [Conversation]
    func chatWith(session: UInt32) throws -> ChatId
    func messageRequests() async throws -> MessageRequests
    func acceptMessageRequest(id: UInt64) async throws
    func declineMessageRequest(id: UInt64) async throws
    func sendChatRequest(session: UInt32, note: String?) async throws
    func blockedPeople() async throws -> [BlockedPerson]
    func blockPerson(session: UInt32) async throws
    func unblockPerson(id: Int64) async throws
    func mobileSettings() -> MobileSettings
    func setMobileSettings(settings: MobileSettings) async throws
    /// The settings shared with the desktop's settings model (notifications, chat history).
    func appSettings() -> AppSettings
    func setAppSettings(settings: AppSettings) async throws
    /// The voice settings as they apply now; they have no event of their own.
    func voiceSettings() async throws -> VoiceSettings
    func setNoiseSuppression(on: Bool) async throws

    /// Every identity, the default one first, as they are when asked; later changes arrive as
    /// events. Certificates and keys never leave the engine.
    func savedIdentities() -> [Identity]
    func createIdentity(name: String, label: String?) async throws -> Identity
    func importIdentity(data: Data, password: String, label: String?) async throws -> Identity
    func exportIdentity(identityId: Int64, password: String) async throws -> Data
    func setDefaultIdentity(identityId: Int64) async throws
    func deleteIdentity(identityId: Int64) async throws
    /// A new "Send to your phone" scan of the desktop app's QR codes.
    func startTransfer() throws -> TransferSession

    /// Streams (FR-5.70 … FR-5.73). How a watch goes shows in the stream's state; nothing is
    /// fetched before `watchStream`.
    func watchStream(session: UInt32, kind: StreamKind) async throws
    func stopWatchingStream(session: UInt32, kind: StreamKind) async throws
    /// How loud a person's screen share sound plays, 0 to 200 percent.
    func setStreamVolume(session: UInt32, percent: UInt16) async throws
    /// Answers "Allow direct connections?"; the waiting watch goes on with the answer.
    func answerDirectConnections(allow: Bool) async throws
    /// Where the news of new stream pictures goes; nil stops it.
    func setVideoSink(sink: VideoSink?)
    /// The handle for the engine's picture functions (`bridge/include/mumcord_video.h`).
    func videoHandle() -> UInt64

    /// Sharing the own camera (FR-5.74, FR-5.75). The engine starts and stops the app's camera
    /// through `capture`; the state arrives as `OwnCameraChanged`.
    func setCameraCapture(capture: CameraCapture?)
    /// The handle for the engine's camera input (`bridge/include/mumcord_camera.h`).
    func cameraHandle() -> UInt64
    /// Opens the preview: the camera runs on the phone, nothing is sent.
    func openCameraPreview() async throws
    func startCameraSharing() async throws
    /// Cancel in the preview, or Stop while sharing.
    func stopCamera() async throws
    /// The app's camera stopped by itself; sharing ends with the reason.
    func cameraCaptureFailed(failure: CaptureFailure)
}

/// The bridge's side of one "Send to your phone" scan (`TransferScan`), as the app uses it, so
/// tests can follow its rules without the cryptography.
protocol TransferSession: AnyObject, Sendable {
    func addPart(text: String) throws -> TransferProgress
    func progress() -> TransferProgress?
    func codeDigits() -> UInt8
    func attemptsLeft() -> UInt8
    func reset()
    func open(code: String) async throws -> TransferPreview
    func importOpened() async throws -> ImportReport
}

extension TransferScan: TransferSession {}

extension MumcordEngine: EngineClient {
    func savedServers() -> [SavedServer] {
        snapshot().servers
    }

    func chatUnreadSnapshot() -> [ChatUnread] {
        snapshot().chatUnread
    }

    func savedIdentities() -> [Identity] {
        snapshot().identities
    }

    func startTransfer() -> TransferSession {
        newTransfer()
    }
}
