// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// What a listed server answered to its ping. A server that was not asked yet has none.
enum PublicPing: Equatable {
    case answered(people: Int, maxPeople: Int, latencyMs: Int)
    case noAnswer

    init(_ ping: ServerPing?) {
        if let ping {
            self = .answered(people: Int(ping.peopleOnline), maxPeople: Int(ping.maxPeople), latencyMs: Int(ping.latencyMs))
        } else {
            self = .noAnswer
        }
    }
}

extension PublicServer {
    /// One server of the list, the same however often it is listed or asked: host (any case)
    /// and port.
    var key: String { "\(host.lowercased()):\(port)" }

    /// Kept when `search` is part of the name, the address or the country, whatever the case,
    /// and the server is in the country `countryFilter` (any country when nil). The same rule as
    /// the bridge's query.
    func matches(_ search: String, countryFilter: String?) -> Bool {
        let text = search.trimmingCharacters(in: .whitespaces)
        let inCountry = countryFilter.map { countryCode.caseInsensitiveCompare($0) == .orderedSame } ?? true
        return inCountry && (
            text.isEmpty
                || name.range(of: text, options: .caseInsensitive) != nil
                || host.range(of: text, options: .caseInsensitive) != nil
                || country.range(of: text, options: .caseInsensitive) != nil
        )
    }
}

/// A country of the list, for the country filter.
struct PublicCountry: Equatable {
    let code: String
    let name: String
}

/// The browser's own state. The list stays while the app runs, also when the browser is closed;
/// the engine only downloads it again on "Refresh".
struct PublicServersLocal: Equatable {
    /// The list as last loaded, or nil before the first successful load.
    var servers: [PublicServer]?
    var loading = false
    /// The first load failed: there is no list to show.
    var loadFailed = false
    /// A refresh failed: the last list is still shown.
    var refreshFailed = false
    var search = ""
    /// The country code of the country filter, or nil for all countries.
    var country: String?
    /// By `PublicServer.key`.
    var pings: [String: PublicPing] = [:]
    /// The servers being added to the server list, by `PublicServer.key`.
    var adding: Set<String> = []
    var addFailed = false
}

/// One row of the browser, ready to draw. Name and country are untrusted text, shown as plain
/// text.
struct PublicServerRow: Equatable, Identifiable {
    let key: String
    let name: String
    let initials: String
    let address: String
    let country: String
    let ping: PublicPing?
    /// The saved server with this address, once it is in the server list.
    let savedId: Int64?
    let adding: Bool

    var id: String { key }
}

/// What the browser shows below its search and filters.
enum PublicListStatus: Equatable {
    case loading
    case failed
    case empty
    case noMatch
    case ready
}

struct PublicServersUiState: Equatable {
    let status: PublicListStatus
    let rows: [PublicServerRow]
    /// How many servers the list has in all.
    let total: Int
    let search: String
    let countries: [PublicCountry]
    /// The chosen country, or nil for all countries.
    let country: String?
    /// True while a refresh runs over a list that is already shown.
    let refreshing: Bool
    let refreshFailed: Bool
    let addFailed: Bool
}

/// The countries of `servers` with a code, each once, sorted by name.
func countriesOf(_ servers: [PublicServer]) -> [PublicCountry] {
    var seen: Set<String> = []
    var countries: [PublicCountry] = []
    for server in servers where !server.countryCode.isEmpty {
        let code = server.countryCode.uppercased()
        guard seen.insert(code).inserted else { continue }
        let name = server.country.trimmingCharacters(in: .whitespaces).isEmpty ? code : server.country
        countries.append(PublicCountry(code: code, name: name))
    }
    return countries.sorted { $0.name.caseInsensitiveCompare($1.name) == .orderedAscending }
}

/// The saved server with the address and port of `server`, as the desktop app matches them.
func savedMatch(_ saved: [SavedServer], _ server: PublicServer) -> SavedServer? {
    saved.first { $0.address.caseInsensitiveCompare(server.host) == .orderedSame && $0.port == server.port }
}

/// The servers the search and the country filter keep, sorted by name as on desktop.
func shownServers(_ local: PublicServersLocal) -> [PublicServer] {
    (local.servers ?? [])
        .filter { $0.matches(local.search, countryFilter: local.country) }
        .sorted { $0.name.caseInsensitiveCompare($1.name) == .orderedAscending }
}

func publicServersUiState(_ local: PublicServersLocal, saved: [SavedServer]) -> PublicServersUiState {
    let servers = local.servers
    let shown = shownServers(local)
    let status: PublicListStatus
    if let servers {
        if servers.isEmpty {
            status = .empty
        } else {
            status = shown.isEmpty ? .noMatch : .ready
        }
    } else {
        status = local.loadFailed && !local.loading ? .failed : .loading
    }
    return PublicServersUiState(
        status: status,
        rows: shown.map { server in
            let key = server.key
            return PublicServerRow(
                key: key,
                name: server.name,
                initials: initials(server.name),
                address: displayAddress(server.host, port: server.port),
                country: server.country,
                ping: local.pings[key],
                savedId: savedMatch(saved, server)?.id,
                adding: local.adding.contains(key)
            )
        },
        total: servers?.count ?? 0,
        search: local.search,
        countries: countriesOf(servers ?? []),
        country: local.country,
        refreshing: servers != nil && local.loading,
        refreshFailed: local.refreshFailed,
        addFailed: local.addFailed
    )
}

/// The keys of the rows that are on screen, top to bottom: those whose frame overlaps the
/// visible height of the list. `frames` are in the list's own coordinates.
func visibleRowKeys(_ frames: [String: CGRect], visibleHeight: CGFloat) -> [String] {
    frames
        .filter { $0.value.maxY > 0 && $0.value.minY < visibleHeight && $0.value.height > 0 }
        .sorted { $0.value.minY < $1.value.minY }
        .map(\.key)
}
