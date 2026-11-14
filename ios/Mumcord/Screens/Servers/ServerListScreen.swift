// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI

/// Room to scroll the last row out from under the add button.
private let fabClearance: CGFloat = 84

/// The saved servers with people online and latency, and the way to add, edit, remove and
/// connect. While `editing`, every row offers Edit and Remove instead of Connect.
struct ServerListScreen: View {
    let rows: [ServerRow]
    let loaded: Bool
    let editing: Bool
    let failure: ConnectFailure?
    let onConnect: (Int64) -> Void
    let onCancelConnect: () -> Void
    let onAdd: () -> Void
    let onToggleEditing: () -> Void
    let onEdit: (Int64) -> Void
    let onRemove: (Int64) -> Void
    let onRetry: () -> Void
    let onDismissError: () -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        MumcordScreen {
            MumcordAppBar(title: Strings.serversTitle, large: true)
            if let failure {
                FailureBanner(failure: failure, onRetry: onRetry, onDismiss: onDismissError, onOpenSettings: onOpenSettings)
            }
            // Nothing to show before the list is read; an empty state would flash by.
            if loaded {
                if rows.isEmpty {
                    EmptyState(onAdd: onAdd)
                } else {
                    list
                }
            }
        }
    }

    private var list: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                VStack(spacing: 0) {
                    MumcordSectionHeader(
                        title: Strings.serversFavourites,
                        action: editing ? Strings.serversDone : Strings.serversEdit,
                        onAction: onToggleEditing
                    )
                    MumcordList {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                            ServerListRow(
                                row: row,
                                divider: index > 0,
                                editing: editing,
                                onConnect: onConnect,
                                onCancelConnect: onCancelConnect,
                                onEdit: onEdit,
                                onRemove: onRemove
                            )
                        }
                    }
                    Color.clear.frame(height: fabClearance)
                }
            }
            MumcordFab(icon: MumcordIcons.plus, label: Strings.serversAdd, action: onAdd)
                .padding(.trailing, MumcordSpace.s4)
                .padding(.bottom, MumcordSpace.s3)
                .accessibilityIdentifier("server-add")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ServerListRow: View {
    let row: ServerRow
    let divider: Bool
    let editing: Bool
    let onConnect: (Int64) -> Void
    let onCancelConnect: () -> Void
    let onEdit: (Int64) -> Void
    let onRemove: (Int64) -> Void

    var body: some View {
        MumcordListRow(
            title: row.name,
            subtitle: row.address,
            status: status,
            divider: divider,
            tall: true,
            leading: { MumcordTile(initials: row.initials, brand: row.highlighted) },
            trailing: {
                if row.connecting {
                    MumcordButton(text: Strings.actionCancel, style: .secondary, small: true, action: onCancelConnect)
                        .accessibilityLabel(Strings.serversCancelDescription(row.name))
                } else if editing {
                    MumcordIconButton(
                        icon: MumcordIcons.edit,
                        label: Strings.serversEditDescription(row.name),
                        action: { onEdit(row.id) }
                    )
                    MumcordIconButton(
                        icon: MumcordIcons.trash,
                        label: Strings.serversRemoveDescription(row.name),
                        action: { onRemove(row.id) }
                    )
                } else {
                    MumcordButton(
                        text: Strings.serversConnect,
                        style: row.highlighted ? .primary : .secondary,
                        small: true,
                        action: { onConnect(row.id) }
                    )
                    .accessibilityLabel(Strings.serversConnectDescription(row.name))
                    .accessibilityIdentifier("server-connect")
                }
            }
        )
        // The row is found as a whole and its buttons stay reachable inside it.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("server-row")
        .onChange(of: row.connecting) { _, connecting in
            // "Connecting…" replaces the ping line without the user looking at it.
            if connecting {
                AccessibilityNotification.Announcement(Strings.serversConnecting).post()
            }
        }
    }

    /// The third line of a row: connecting, or what the server answered to the ping.
    private var status: AnyView? {
        if row.connecting {
            return AnyView(MumcordListRowValue(text: Strings.serversConnecting))
        }
        switch row.ping {
        case .some(.online(let peopleOnline, let latencyMs)):
            return AnyView(
                HStack(spacing: 6) {
                    MumcordDot()
                    MumcordListRowValue(text: Strings.serversOnline(peopleOnline, latencyMs))
                }
            )
        case .some(.offline):
            return AnyView(
                HStack(spacing: 6) {
                    MumcordDot(off: true)
                    MumcordListRowValue(text: Strings.serversOffline)
                }
            )
        case .none:
            return nil
        }
    }
}

/// A failed connection above the list: what went wrong, and the ways on from there.
private struct FailureBanner: View {
    let failure: ConnectFailure
    let onRetry: () -> Void
    let onDismiss: () -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MumcordBanner(text: message, title: title, icon: MumcordIcons.alert, tone: .danger)
            HStack(spacing: MumcordSpace.s2) {
                if failure.localNetwork != nil {
                    // iOS only lets the user change local network access in its settings.
                    MumcordButton(text: Strings.localNetworkOpenSettings, small: true, action: onOpenSettings)
                        .accessibilityIdentifier("local-network-settings")
                }
                MumcordButton(
                    text: retryText,
                    style: failure.localNetwork == nil ? .primary : .secondary,
                    small: true,
                    action: onRetry
                )
                .accessibilityIdentifier("connect-retry")
                MumcordButton(text: Strings.errorDismiss, style: .ghost, small: true, action: onDismiss)
            }
            .padding(.horizontal, MumcordSpace.s4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("connect-error")
        // The error appears without the user looking at it, so it is spoken.
        .onAppear(perform: announce)
        .onChange(of: failure) { _, _ in announce() }
    }

    private var title: String {
        if let serverName = failure.serverName {
            return Strings.errorTitleServer(serverName)
        }
        return Strings.errorTitle
    }

    private var message: String {
        switch failure.localNetwork {
        case .denied: Strings.localNetworkDenied
        case .suspected: Strings.localNetworkSuspected
        case nil: failure.error.message
        }
    }

    private var retryText: String {
        // A wrong password is not fixed by trying again: the button asks for it instead.
        failure.error == .wrongPassword && failure.serverName != nil ? Strings.errorEnterPassword : Strings.errorRetry
    }

    private func announce() {
        let spoken: String = title + " " + message
        AccessibilityNotification.Announcement(spoken).post()
    }
}

private struct EmptyState: View {
    let onAdd: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        VStack(spacing: MumcordSpace.s4) {
            ParagraphText(Strings.serversEmptyTitle, style: .title, color: theme.colors.text)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            ParagraphText(Strings.serversEmptyText, style: .body, color: theme.colors.textSecondary)
                .multilineTextAlignment(.center)
            MumcordButton(text: Strings.serversAdd, icon: MumcordIcons.plus, action: onAdd)
                .accessibilityIdentifier("server-add")
        }
        .padding(MumcordSpace.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
