// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import MumcordBridge

// Builders with defaults, so a new field in the bridge types does not touch every test.

func channel(_ id: UInt32, _ name: String, parentId: UInt32? = nil, position: Int32 = 0) -> Channel {
    Channel(
        id: id,
        parentId: parentId,
        name: name,
        position: position,
        hasDescription: false,
        maxPeople: nil,
        temporary: false,
        restricted: false,
        canEnter: true,
        permissions: ChannelPermissions(
            speak: false,
            whisper: false,
            textMessage: false,
            movePeople: false,
            muteDeafen: false,
            kick: false,
            ban: false
        )
    )
}

func person(_ session: UInt32, _ name: String, channelId: UInt32 = 0, isSelf: Bool = false) -> Person {
    Person(
        session: session,
        channelId: channelId,
        name: name,
        isSelf: isSelf,
        talking: false,
        selfMuted: false,
        selfDeafened: false,
        serverMuted: false,
        serverDeafened: false,
        suppressed: false,
        localVolume: 100,
        locallyMuted: false,
        streaming: false,
        camera: false
    )
}

func savedServer(
    _ id: Int64,
    _ name: String,
    address: String? = nil,
    port: UInt16 = 64738,
    username: String = "Me",
    hasPassword: Bool = false
) -> SavedServer {
    SavedServer(
        id: id,
        name: name,
        address: address ?? "\(name.lowercased().replacingOccurrences(of: " ", with: "")).example",
        port: port,
        username: username,
        hasPassword: hasPassword,
        identityId: nil
    )
}

/// The ids of `mockupServer()`.
enum Mockup {
    static let root: UInt32 = 0
    static let lobby: UInt32 = 1
    static let gaming: UInt32 = 2
    static let music: UInt32 = 3
    static let afk: UInt32 = 4
    static let ben: UInt32 = 2
    static let lea: UInt32 = 3
    static let jonas: UInt32 = 5

    static let fingerprint =
        "9F:86:D0:81:88:4C:7D:65:9A:2F:EA:A0:C5:5A:D0:15:A3:BF:4F:1B:2B:0B:82:2C:D1:5D:6C:15:B0:F0:0A:08"
    static let previousFingerprint =
        "2C:F2:4D:BA:5F:B0:A3:0E:26:E8:3B:2A:C5:B9:E2:9E:1B:16:1E:5C:1F:A7:42:5E:73:04:33:62:93:8B:98:24"

    /// The server list of mockup `01-server-list`.
    static let servers = [
        savedServer(1, "Nordlicht"),
        savedServer(2, "Tidepool", address: "voice.tidepool.example"),
        savedServer(3, "Lantern Guild"),
        savedServer(4, "Quiet Harbor"),
    ]

    /// What the servers of `servers` answer; Quiet Harbor is offline.
    static let pings: [Int64: ServerPing] = [
        1: ServerPing(latencyMs: 24, peopleOnline: 5, maxPeople: 50),
        2: ServerPing(latencyMs: 31, peopleOnline: 12, maxPeople: 50),
        3: ServerPing(latencyMs: 48, peopleOnline: 2, maxPeople: 50),
    ]
}

/// The server of mockup `02-channels`: Nordlicht with Lobby (Anna, Ben talking, Lea = the user),
/// Gaming (Mira sharing her screen, Jonas muted and deafened), the locked Music and AFK.
func mockupServer() -> ServerState {
    var music = channel(Mockup.music, "Music", parentId: Mockup.root, position: 2)
    music.restricted = true
    music.canEnter = false
    var ben = person(Mockup.ben, "Ben", channelId: Mockup.lobby)
    ben.talking = true
    var mira = person(4, "Mira", channelId: Mockup.gaming)
    mira.streaming = true
    var jonas = person(Mockup.jonas, "Jonas", channelId: Mockup.gaming)
    jonas.selfMuted = true
    jonas.selfDeafened = true
    return ServerState(
        selfSession: Mockup.lea,
        channels: [
            channel(Mockup.root, "Nordlicht"),
            channel(Mockup.lobby, "Lobby", parentId: Mockup.root, position: 0),
            channel(Mockup.gaming, "Gaming", parentId: Mockup.root, position: 1),
            music,
            channel(Mockup.afk, "AFK", parentId: Mockup.root, position: 3),
        ],
        people: [
            person(1, "Anna", channelId: Mockup.lobby),
            ben,
            person(Mockup.lea, "Lea", channelId: Mockup.lobby, isSelf: true),
            mira,
            jonas,
        ]
    )
}
#endif
