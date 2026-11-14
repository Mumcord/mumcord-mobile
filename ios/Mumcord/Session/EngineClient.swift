// SPDX-License-Identifier: AGPL-3.0-only

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
    func connectServer(serverId: Int64, password: String?, channelPath: [String]) async throws
    func answerCertificatePrompt(fingerprint: String, trust: Bool) async throws
    func disconnect() async throws
    func joinChannel(channelId: UInt32, password: String?) async throws
    func setPersonVolume(session: UInt32, percent: UInt16) async throws
    func setPersonLocalMute(session: UInt32, muted: Bool) async throws
    func talkMode() async throws -> TalkMode
    func setTalkMode(mode: TalkMode) async throws
    func audioEndpoints() -> AudioEndpoints
    func setPushToTalkPressed(pressed: Bool)
    func setSelfMute(muted: Bool) async throws
    func setSelfDeaf(deafened: Bool) async throws
    func startWhisper(target: WhisperTarget) async throws
    func stopWhisper()
}

extension MumcordEngine: EngineClient {
    func savedServers() -> [SavedServer] {
        snapshot().servers
    }
}
