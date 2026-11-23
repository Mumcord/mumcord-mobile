// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

private let rowHeight: CGFloat = 60
private let iconBox: CGFloat = 36

/// The activity log of the connection (mockup `10-activity-log`, FR-5.94): what people did,
/// newest first under the day it happened, with filter chips. The empty states have no mockup.
struct ActivityScreen: View {
    let state: MoreUiState
    let actions: MoreController
    let clock: ChatClock

    var body: some View {
        let days = activityDays(state.activity, filter: state.filter, today: clock.today, calendar: clock.calendar)
        let filters = ActivityFilter.allCases
        VStack(spacing: 0) {
            MumcordAppBar(
                title: Strings.activityTitle,
                border: true,
                onBack: actions.back,
                backLabel: Strings.activityBack,
                leading: { EmptyView() },
                subtitle: { MumcordAppBarSubtitle(text: state.serverName) },
                actions: { EmptyView() }
            )
            MumcordChipRow(
                options: filters.map(filterText),
                selectedIndex: filters.firstIndex(of: state.filter) ?? 0,
                onSelect: { actions.selectFilter(filters[$0]) }
            )
            .padding(.top, MumcordSpace.s2)
            if days.isEmpty {
                EmptyActivity(filtered: !state.activity.isEmpty)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(days) { day in
                            MumcordSectionHeader(title: dayText(day.label))
                            MumcordList {
                                ForEach(Array(day.entries.enumerated()), id: \.element.id) { index, entry in
                                    ActivityRow(entry: entry, serverName: state.serverName, clock: clock, divider: index > 0)
                                }
                            }
                        }
                    }
                    .padding(.bottom, MumcordSpace.s4)
                }
            }
        }
    }

    private func filterText(_ filter: ActivityFilter) -> String {
        switch filter {
        case .all: Strings.activityFilterAll
        case .joins: Strings.activityFilterJoins
        case .streams: Strings.activityFilterStreams
        }
    }

    private func dayText(_ label: DayLabel) -> String {
        switch label {
        case .today: Strings.chatDayToday
        case .yesterday: Strings.chatDayYesterday
        case .date(let day): clock.date(day)
        }
    }
}

private struct ActivityRow: View {
    let entry: ActivityEntry
    let serverName: String
    let clock: ChatClock
    let divider: Bool
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        // "Connected" names the server; the other kinds name a channel.
        let sentence = activitySentence(
            template: template,
            name: entry.isSelf ? Strings.activityYou : entry.name,
            place: entry.kind == .connected ? serverName : entry.channelName ?? ""
        )
        HStack(spacing: MumcordSpace.s3) {
            MumcordIcon(icon: icon, label: nil, tint: tint)
                .frame(width: iconBox, height: iconBox)
                .background(colors.bgHover, in: RoundedRectangle(cornerRadius: MumcordRadius.lg))
            ParagraphText(sentence, style: .body, color: colors.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            MumcordListRowValue(text: clock.time(entry.atMs))
                .layoutPriority(1)
        }
        // The divider line is part of the row's height, as in `MumcordListRow`.
        .padding(EdgeInsets(
            top: divider ? MumcordSpace.s2 + 1 : MumcordSpace.s2,
            leading: MumcordSpace.s4,
            bottom: MumcordSpace.s2,
            trailing: MumcordSpace.s4
        ))
        .frame(minHeight: rowHeight)
        .overlay(alignment: .top) {
            if divider {
                Rectangle().fill(colors.border).frame(height: 1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("activity-entry")
    }

    /// Icon and colour as in the mockup.
    private var icon: MumcordIconData {
        switch entry.kind {
        case .connected, .joined: MumcordIcons.users
        case .moved: MumcordIcons.chevronRight
        case .left: MumcordIcons.phoneOff
        case .streamStarted, .streamStopped: MumcordIcons.monitor
        }
    }

    private var tint: Color {
        let colors = theme.colors
        switch entry.kind {
        case .connected, .joined: return colors.success
        case .moved: return colors.textSecondary
        case .left, .streamStopped: return colors.textMuted
        case .streamStarted: return colors.accent
        }
    }

    private var template: String {
        let known = entry.channelName != nil
        switch entry.kind {
        case .connected: return Strings.activityConnected
        case .joined: return known ? Strings.activityJoined : Strings.activityJoinedServer
        case .moved: return known ? Strings.activityMoved : Strings.activityMovedElsewhere
        case .left: return Strings.activityLeft
        case .streamStarted: return known ? Strings.activityStreamStarted : Strings.activityStreamStartedElsewhere
        case .streamStopped: return Strings.activityStreamStopped
        }
    }
}

/// Nothing happened yet, or nothing of the kind the chosen filter shows.
private struct EmptyActivity: View {
    let filtered: Bool
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        VStack(spacing: MumcordSpace.s2) {
            if filtered {
                Paragraph(Strings.activityEmptyFilter, color: colors.textMuted)
            } else {
                MumcordIcon(icon: MumcordIcons.activity, label: nil, tint: colors.textMuted)
                ParagraphText(Strings.activityEmpty, style: .bodyStrong, color: colors.text)
                ParagraphText(Strings.activityEmptyText, style: .small, color: colors.textMuted)
            }
        }
        .multilineTextAlignment(.center)
        .padding(MumcordSpace.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("activity-empty")
    }
}
