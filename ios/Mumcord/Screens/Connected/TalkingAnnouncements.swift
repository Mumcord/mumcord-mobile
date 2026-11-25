// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

/// Feeds the people who talk in the user's channel to a `TalkingAnnouncer` and says its sentences
/// through `post`. Kept apart from the view so that the rules and the texts can be tested with
/// a made-up clock.
@MainActor
final class TalkingAnnouncements {
    private var announcer = TalkingAnnouncer()
    private let clock: () -> TimeInterval
    private let post: (String) -> Void

    init(
        clock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        post: @escaping (String) -> Void = TalkingAnnouncements.postPolitely
    ) {
        self.clock = clock
        self.post = post
    }

    /// Takes the people of the channel, says what is to be said now and returns how many seconds
    /// later `update` must be called again without a change, or nil when nothing waits.
    func update(_ tiles: [VoiceTile]) -> TimeInterval? {
        let now = clock()
        let others = tiles.filter { !$0.isSelf && $0.talking }.map { TalkingAnnouncer.Speaker(session: $0.session, name: $0.name) }
        if let sentence = talkingAnnouncement(announcer.update(now: now, others: others)) {
            post(sentence)
        }
        return announcer.nextUpdateAt.map { max($0 - now, 0) }
    }

    /// Queued behind what VoiceOver is saying instead of cutting it off, like Android's polite
    /// live region.
    static func postPolitely(_ sentence: String) {
        let announcement = NSAttributedString(string: sentence, attributes: [
            .accessibilitySpeechQueueAnnouncement: true,
            .accessibilitySpeechAnnouncementPriority: UIAccessibilityPriority.default,
        ])
        UIAccessibility.post(notification: .announcement, argument: announcement)
    }
}

/// Says through VoiceOver who starts talking in the user's channel, as decided by
/// `TalkingAnnouncer`. Without VoiceOver nothing runs at all, which keeps the phone idle while
/// nobody listens (FR-5.43). It reads the people from the session state on the main thread,
/// never from the audio path. Draws nothing.
struct TalkingAnnouncementsView: View {
    let tiles: [VoiceTile]
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverOn
    @State private var announcements = TalkingAnnouncements()

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .task(id: voiceOverOn ? talkers : nil) {
                guard voiceOverOn else { return }
                while let wait = announcements.update(tiles) {
                    try? await Task.sleep(for: .seconds(wait))
                    if Task.isCancelled { return }
                }
            }
    }

    /// Who talks: a change starts the task again, which hands the new state to the announcer.
    private var talkers: [UInt32] {
        tiles.filter { !$0.isSelf && $0.talking }.map(\.session)
    }
}
