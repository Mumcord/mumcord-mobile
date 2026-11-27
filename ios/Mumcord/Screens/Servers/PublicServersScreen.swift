// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// What the user can do on the public server browser.
struct PublicServersActions {
    let back: () -> Void
    let refresh: () -> Void
    let search: (String) -> Void
    let selectCountry: (String?) -> Void
    /// The rows on screen, by key, top to bottom; only they are pinged.
    let showing: ([String]) -> Void
    let add: (String) -> Void
    let connect: (Int64) -> Void
}

/// The public server browser (FR-5.9): search, a country filter and the list with people online
/// and latency for the rows on screen. It has no mockup; it follows the Android screen, in the
/// look of the server list. Names and countries come from the list and are shown as plain text.
struct PublicServersScreen: View {
    let state: PublicServersUiState
    let actions: PublicServersActions

    var body: some View {
        MumcordScreen {
            MumcordAppBar(title: Strings.publicTitle, onBack: actions.back, backLabel: Strings.publicBack)
            switch state.status {
            case .loading:
                PublicMessage(text: Strings.publicLoading)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                PublicLoadFailed(onRetry: actions.refresh)
            case .empty, .noMatch, .ready:
                PublicFilters(state: state, actions: actions)
                PublicBanners(refreshFailed: state.refreshFailed, addFailed: state.addFailed)
                PublicServerList(state: state, actions: actions)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("public-servers")
    }
}

private struct PublicFilters: View {
    let state: PublicServersUiState
    let actions: PublicServersActions

    var body: some View {
        MumcordTextField(
            value: state.search,
            onValueChange: actions.search,
            label: Strings.publicSearch,
            submitLabel: .search
        )
        .padding(.horizontal, MumcordSpace.s4)
        .padding(.bottom, MumcordSpace.s2)
        if !state.countries.isEmpty {
            MumcordChipRow(
                options: [Strings.publicAnyCountry] + state.countries.map(\.name),
                selectedIndex: (state.countries.firstIndex { $0.code == state.country } ?? -1) + 1,
                onSelect: { index in
                    actions.selectCountry(index > 0 ? state.countries[index - 1].code : nil)
                }
            )
        }
    }
}

private struct PublicBanners: View {
    let refreshFailed: Bool
    let addFailed: Bool

    var body: some View {
        if refreshFailed {
            MumcordBanner(text: Strings.publicRefreshFailed, tone: .warning)
                .padding(.top, MumcordSpace.s2)
                .accessibilityIdentifier("public-servers-refresh-failed")
                .onAppear { announce(Strings.publicRefreshFailed) }
        }
        if addFailed {
            MumcordBanner(text: Strings.publicAddFailed, icon: MumcordIcons.alert, tone: .danger)
                .padding(.top, MumcordSpace.s2)
                .onAppear { announce(Strings.publicAddFailed) }
        }
    }
}

/// Where each row lies in the list's visible area, by key.
private struct RowFramesKey: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue()) { _, next in next }
    }
}

private let listSpace = "public-servers-list"

private struct PublicServerList: View {
    let state: PublicServersUiState
    let actions: PublicServersActions

    var body: some View {
        GeometryReader { viewport in
            ScrollView {
                LazyVStack(spacing: 0) {
                    MumcordSectionHeader(title: header, action: state.refreshing ? nil : Strings.publicRefresh, onAction: actions.refresh)
                    switch state.status {
                    case .empty:
                        PublicMessage(text: Strings.publicEmpty)
                    case .noMatch:
                        PublicMessage(text: Strings.publicNoMatch)
                    case .loading, .failed, .ready:
                        ForEach(Array(state.rows.enumerated()), id: \.element.id) { index, row in
                            PublicServerListRow(row: row, divider: index > 0, actions: actions)
                                .background {
                                    GeometryReader { proxy in
                                        Color.clear.preference(
                                            key: RowFramesKey.self,
                                            value: [row.key: proxy.frame(in: .named(listSpace))]
                                        )
                                    }
                                }
                        }
                    }
                    PublicNote()
                }
            }
            .coordinateSpace(name: listSpace)
            .scrollDismissesKeyboard(.immediately)
            // Only the rows on screen are pinged; the list tells which they are whenever it moves.
            .onPreferenceChange(RowFramesKey.self) { frames in
                actions.showing(visibleRowKeys(frames, visibleHeight: viewport.size.height))
            }
        }
    }

    private var header: String {
        if state.refreshing { return Strings.publicRefreshing }
        if state.rows.count == state.total { return Strings.publicCountAll(state.total) }
        return Strings.publicCountShown(state.rows.count, state.total)
    }
}

private struct PublicServerListRow: View {
    let row: PublicServerRow
    let divider: Bool
    let actions: PublicServersActions

    var body: some View {
        MumcordListRow(
            title: row.name,
            subtitle: row.country.isEmpty ? row.address : Strings.publicAddressCountry(row.address, row.country),
            status: status,
            divider: divider,
            tall: true,
            // The initials only repeat the name.
            leading: { MumcordTile(initials: row.initials).accessibilityHidden(true) },
            trailing: {
                if let savedId = row.savedId {
                    MumcordButton(text: Strings.serversConnect, style: .secondary, small: true) {
                        actions.connect(savedId)
                    }
                    .accessibilityLabel(Strings.serversConnectDescription(row.name))
                    .accessibilityIdentifier("public-server-connect")
                } else {
                    MumcordButton(text: Strings.publicAdd, style: .secondary, small: true, icon: MumcordIcons.plus) {
                        actions.add(row.key)
                    }
                    .disabled(row.adding)
                    .accessibilityLabel(Strings.publicAddDescription(row.name))
                    .accessibilityIdentifier("public-server-add")
                }
            }
        )
        // Name, address and country, and the ping are read as one; the button stays its own.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("public-server-row")
    }

    /// The third line of a row: what the server answered to its ping, nothing before it was asked.
    private var status: AnyView? {
        switch row.ping {
        case .some(.answered(let people, let maxPeople, let latencyMs)):
            return AnyView(
                HStack(spacing: 6) {
                    MumcordDot()
                    MumcordListRowValue(text: Strings.publicPing(people, maxPeople, latencyMs))
                        .accessibilityLabel(Strings.publicPingDescription(people, maxPeople, latencyMs))
                }
            )
        case .some(.noAnswer):
            return AnyView(
                HStack(spacing: 6) {
                    MumcordDot(off: true)
                    MumcordListRowValue(text: Strings.publicNoAnswer)
                }
            )
        case .none:
            return nil
        }
    }
}

private struct PublicNote: View {
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ParagraphText(Strings.publicNote, style: .small, color: theme.colors.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(MumcordSpace.s4)
    }
}

private struct PublicLoadFailed: View {
    let onRetry: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        VStack(spacing: MumcordSpace.s4) {
            ParagraphText(Strings.publicLoadFailedTitle, style: .title, color: theme.colors.text)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            ParagraphText(Strings.publicLoadFailed, style: .body, color: theme.colors.textSecondary)
                .multilineTextAlignment(.center)
            MumcordButton(text: Strings.publicRetry, action: onRetry)
                .accessibilityIdentifier("public-servers-retry")
        }
        .padding(MumcordSpace.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { announce(Strings.publicLoadFailedTitle) }
    }
}

/// A calm line in the middle of the screen or the list: loading, an empty list, no match.
private struct PublicMessage: View {
    let text: String
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        ParagraphText(text, style: .body, color: theme.colors.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(MumcordSpace.s5)
            .onAppear { announce(text) }
    }
}

/// Says a change the user did not look at, as Android's polite live regions do.
private func announce(_ text: String) {
    AccessibilityNotification.Announcement(text).post()
}
