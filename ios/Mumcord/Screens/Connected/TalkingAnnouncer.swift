// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

/// At most one announcement in this time (seconds), so a lively channel does not drown out
/// everything else.
let talkingAnnouncementGap: TimeInterval = 5

/// A person who keeps talking in short bursts is named again only after this time (seconds).
let talkingRepeatTime: TimeInterval = 30

/// Decides when VoiceOver says who started talking in the user's channel (specification section 5:
/// "talking state is announced politely, not on every change"). Only the start of talking is
/// announced, never the end; people who start within one gap are named together; the user is
/// never announced. Pure logic, the same rules as Android's `TalkingAnnouncer`: the caller passes
/// the clock and asks again at `nextUpdateAt`.
struct TalkingAnnouncer {
    /// A person of the channel who talks now: the session that identifies them and their name.
    struct Speaker: Equatable {
        let session: UInt32
        let name: String
    }

    private let gap: TimeInterval
    private let repeatTime: TimeInterval
    private var talking: Set<UInt32> = []
    /// In the order the people started talking.
    private var waiting: [UInt32] = []
    private var lastNamed: [UInt32: TimeInterval] = [:]
    private var lastAnnouncedAt: TimeInterval?

    /// The time the caller has to call `update` again, also without a change; nil when nothing waits.
    private(set) var nextUpdateAt: TimeInterval?

    init(gap: TimeInterval = talkingAnnouncementGap, repeatTime: TimeInterval = talkingRepeatTime) {
        self.gap = gap
        self.repeatTime = repeatTime
    }

    /// Takes who talks now (`others`, without the user, in the order of the voice view) and returns
    /// the names to announce now, in the order they started; empty when nothing is to be said.
    mutating func update(now: TimeInterval, others: [Speaker]) -> [String] {
        for speaker in others where !talking.contains(speaker.session) {
            if let named = lastNamed[speaker.session], now - named < repeatTime { continue }
            waiting.append(speaker.session)
        }
        talking = Set(others.map(\.session))
        // Somebody who stopped before their turn came is not announced any more.
        waiting.removeAll { !talking.contains($0) }
        if waiting.isEmpty {
            nextUpdateAt = nil
            return []
        }
        if let last = lastAnnouncedAt, now - last < gap {
            nextUpdateAt = last + gap
            return []
        }
        let names = waiting.compactMap { session in others.first { $0.session == session }?.name }
        for session in waiting { lastNamed[session] = now }
        waiting.removeAll()
        lastAnnouncedAt = now
        nextUpdateAt = nil
        return names
    }
}

/// The sentence VoiceOver says for the names `TalkingAnnouncer` returned, nil for none.
func talkingAnnouncement(_ names: [String]) -> String? {
    switch names.count {
    case 0: nil
    case 1: Strings.talkingAnnouncementOne(names[0])
    case 2: Strings.talkingAnnouncementTwo(names[0], names[1])
    default: Strings.talkingAnnouncementMore(names[0], names.count - 1)
    }
}
