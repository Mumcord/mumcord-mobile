// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// The activity log of mockup `10-activity-log` for the fake engine: what happened on Nordlicht
/// yesterday evening and today. A kick is reported as leaving for now, as the bridge does.
enum ActivityMockup {
    /// The entries a connection starts with, oldest first.
    static func entries(now: Date = Date(), calendar: Calendar = .current) -> [ActivityEntry] {
        func at(_ daysBefore: Int, _ hour: Int, _ minute: Int) -> Int64 {
            let day = calendar.date(byAdding: .day, value: -daysBefore, to: now) ?? now
            let time = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
            return Int64(time.timeIntervalSince1970 * 1000)
        }
        return [
            entry(1, .connected, Mockup.lea, "Lea", atMs: at(1, 20, 5), isSelf: true),
            entry(2, .streamStarted, 4, "Mira", channel: (Mockup.music, "Music"), atMs: at(1, 20, 10)),
            entry(3, .joined, Mockup.jonas, "Jonas", channel: (Mockup.lobby, "Lobby"), atMs: at(1, 21, 40)),
            entry(4, .left, Mockup.jonas, "Jonas", atMs: at(1, 21, 45)),
            entry(5, .left, Mockup.ben, "Ben", atMs: at(0, 17, 32)),
            entry(6, .joined, 1, "Anna", channel: (Mockup.lobby, "Lobby"), atMs: at(0, 18, 40)),
            entry(7, .moved, Mockup.jonas, "Jonas", channel: (Mockup.gaming, "Gaming"), atMs: at(0, 18, 55)),
            entry(8, .streamStarted, 4, "Mira", channel: (Mockup.gaming, "Gaming"), atMs: at(0, 19, 2)),
        ]
    }

    /// What happens while the log is open: Ben comes back.
    static func benJoins(now: Date = Date()) -> ActivityEntry {
        entry(9, .joined, Mockup.ben, "Ben", channel: (Mockup.lobby, "Lobby"), atMs: Int64(now.timeIntervalSince1970 * 1000))
    }

    /// What happens after the log was looked at: Mira ends her screen share.
    static func miraStops(now: Date = Date()) -> ActivityEntry {
        entry(10, .streamStopped, 4, "Mira", atMs: Int64(now.timeIntervalSince1970 * 1000))
    }

    private static func entry(
        _ id: UInt64,
        _ kind: ActivityKind,
        _ session: UInt32,
        _ name: String,
        channel: (id: UInt32, name: String)? = nil,
        atMs: Int64,
        isSelf: Bool = false
    ) -> ActivityEntry {
        ActivityEntry(
            id: id,
            kind: kind,
            session: session,
            name: name,
            isSelf: isSelf,
            channelId: channel?.id,
            channelName: channel?.name,
            atMs: atMs
        )
    }
}
#endif
