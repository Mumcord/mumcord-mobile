// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The filter chips of the activity log (mockup `10-activity-log`). The mockup's "Moderation"
/// is left out: the bridge reports kicks and bans as people leaving, so it would always be empty.
enum ActivityFilter: CaseIterable {
    case all
    case joins
    case streams

    func includes(_ kind: ActivityKind) -> Bool {
        let stream = kind == .streamStarted || kind == .streamStopped
        switch self {
        case .all: return true
        case .joins: return !stream
        case .streams: return stream
        }
    }
}

/// The entries of one day under their heading, newest first.
struct ActivityDay: Equatable, Identifiable {
    let label: DayLabel
    let entries: [ActivityEntry]

    /// The log never lists a day without entries.
    var id: UInt64 { entries.first?.id ?? 0 }
}

/// The log as the screen lists it: the entries of `filter`, grouped by the day they happened
/// on in the calendar's time zone. `entries` come newest first and stay in that order.
func activityDays(_ entries: [ActivityEntry], filter: ActivityFilter, today: ChatDay, calendar: Calendar) -> [ActivityDay] {
    var days: [(day: ChatDay, entries: [ActivityEntry])] = []
    for entry in entries where filter.includes(entry.kind) {
        let day = ChatDay(Date(timeIntervalSince1970: Double(entry.atMs) / 1000), calendar: calendar)
        if let index = days.firstIndex(where: { $0.day == day }) {
            days[index].entries.append(entry)
        } else {
            days.append((day, [entry]))
        }
    }
    return days.map { ActivityDay(label: dayLabel($0.day, today: today, calendar: calendar), entries: $0.entries) }
}

/// The number on the Activity row of the More tab: what others did since the log was last
/// looked at (`seenId`, the newest entry then; nil when it was not opened in this connection).
func unseenActivity(_ entries: [ActivityEntry], seenId: UInt64?) -> Int {
    entries.filter { entry in !entry.isSelf && (seenId.map { entry.id > $0 } ?? true) }.count
}

private let namePlaceholder = "%1$@"
private let placePlaceholder = "%2$@"

/// A sentence of the log from its `template`: `%1$@` becomes the `name` in bold, `%2$@` the
/// `place` (a channel or the server). Both are the server's text and stay plain characters.
func activitySentence(template: String, name: String, place: String) -> AttributedString {
    var sentence = AttributedString()
    var rest = Substring(template)
    while !rest.isEmpty {
        let nameAt = rest.range(of: namePlaceholder)
        let placeAt = rest.range(of: placePlaceholder)
        let nameFirst: Bool
        switch (nameAt, placeAt) {
        case (nil, nil):
            sentence += AttributedString(String(rest))
            return sentence
        case (.some, nil): nameFirst = true
        case (nil, .some): nameFirst = false
        case (.some(let name), .some(let place)): nameFirst = name.lowerBound < place.lowerBound
        }
        guard let next = nameFirst ? nameAt : placeAt else { return sentence }
        sentence += AttributedString(String(rest[..<next.lowerBound]))
        var word = AttributedString(nameFirst ? name : place)
        if nameFirst { word.inlinePresentationIntent = .stronglyEmphasized }
        sentence += word
        rest = rest[next.upperBound...]
    }
    return sentence
}
