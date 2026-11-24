// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import MumcordBridge
import SwiftUI

/// A public server list made up for tests and screenshots, the same as the Android screenshot
/// test's. Every address ends in `.example`: nothing here is a real server, and the fake engine
/// contacts none of them.
enum PublicServersMockup {
    static let servers = [
        listed("Alpine Lounge", "alps.example", "Switzerland", "CH"),
        listed("Berlin Gaming", "mumble.berlin.example", "Germany", "DE"),
        listed("Lantern Guild", "lanternguild.example", "Germany", "DE"),
        listed("Nordic Friends", "voice.nordic.example", "Sweden", "SE"),
        listed("Quiet Harbor", "quietharbor.example", "Netherlands", "NL"),
        listed("Tidepool", "voice.tidepool.example", "Canada", "CA"),
    ]

    /// What the listed servers answer, by "host:port"; Nordic Friends and Tidepool do not answer.
    static let pings: [String: ServerPing] = [
        "alps.example:64738": ServerPing(latencyMs: 34, peopleOnline: 12, maxPeople: 50),
        "mumble.berlin.example:64738": ServerPing(latencyMs: 21, peopleOnline: 87, maxPeople: 100),
        "lanternguild.example:64738": ServerPing(latencyMs: 48, peopleOnline: 2, maxPeople: 20),
        "quietharbor.example:64738": ServerPing(latencyMs: 63, peopleOnline: 0, maxPeople: 25),
    ]

    /// The server list with only Lantern Guild, which is also on the public list.
    static let savedLantern = [savedServer(3, "Lantern Guild")]

    private static func listed(_ name: String, _ host: String, _ country: String, _ code: String) -> PublicServer {
        PublicServer(name: name, host: host, port: 64738, country: country, countryCode: code)
    }
}

/// How far the fake engine's downloads of the public list and pings of listed servers are, for
/// the UI tests, which cannot see inside the app. Set by the engine of the debug launch.
@MainActor
@Observable
final class FakePublicListProbe {
    private(set) var activity: PublicActivity?

    /// Reports come from the engine's threads and may arrive out of order; the counts only grow.
    func take(_ next: PublicActivity) {
        guard let current = activity else {
            activity = next
            return
        }
        activity = PublicActivity(
            downloads: max(current.downloads, next.downloads),
            pings: max(current.pings, next.pings),
            answered: max(current.answered, next.answered)
        )
    }

    var summary: String {
        guard let activity else { return "none" }
        return "downloads \(activity.downloads), pings \(activity.pings), answered \(activity.answered)"
    }
}

/// How often the fake engine downloaded the public list, how many listed servers it was asked to
/// ping and how many of those pings have ended.
struct PublicActivity: Equatable, Sendable {
    let downloads: Int
    let pings: Int
    let answered: Int
}

/// An invisible element whose value is the probe's summary, e.g. "downloads 1, pings 4,
/// answered 4": how often the list was downloaded, how many listed servers were pinged and how
/// many of those pings have ended.
struct FakePublicListProbeView: View {
    let probe: FakePublicListProbe

    var body: some View {
        Color.clear
            .frame(width: 1, height: 1)
            .accessibilityElement()
            .accessibilityLabel("Fake public list")
            .accessibilityValue(probe.summary)
            .accessibilityIdentifier("fake-public-list")
    }
}
#endif
