// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import UserNotifications

/// The words of the "disconnect when alone" notifications.
struct IdleNotificationTexts {
    let warningTitle: String
    let warningText: String
    let stayConnected: String
    let disconnectedTitle: String
    let disconnectedText: String
}

/// The notifications of "disconnect when alone" on iOS: local notifications with fixed
/// identifiers, so each replaces its earlier one. The warning carries the action "Stay
/// connected", which runs in the background without opening the app; its answer arrives at the
/// app's one notification delegate (`SystemMessageNotifier.onResponse`) under `warningCategory`.
final class SystemIdleNotifier: IdleNotifier, @unchecked Sendable {
    static let warningCategory = "idle-warning"
    static let stayAction = "stay-connected"
    private static let warningId = "idle-warning"
    private static let disconnectedId = "idle-disconnected"

    private let center: UNUserNotificationCenter
    private let texts: IdleNotificationTexts
    private let log: @Sendable (String) -> Void

    init(center: UNUserNotificationCenter = .current(), texts: IdleNotificationTexts, log: @escaping @Sendable (String) -> Void) {
        self.center = center
        self.texts = texts
        self.log = log
        // No options: the action works without unlocking the phone or opening the app.
        let stay = UNNotificationAction(identifier: Self.stayAction, title: texts.stayConnected, options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.warningCategory, actions: [stay], intentIdentifiers: [], options: []),
        ])
    }

    func showWarning() {
        add(Self.warningId, title: texts.warningTitle, body: texts.warningText, category: Self.warningCategory)
    }

    func hideWarning() {
        remove(Self.warningId)
    }

    func showDisconnected() {
        add(Self.disconnectedId, title: texts.disconnectedTitle, body: texts.disconnectedText, category: "")
    }

    func hideDisconnected() {
        remove(Self.disconnectedId)
    }

    private func add(_ id: String, title: String, body: String, category: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = category
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil)) { [log] error in
            // Without the permission the system drops it; the app still disconnects as announced
            // and says so in the app.
            if let error { log("idle notification failed: \(type(of: error))") }
        }
    }

    private func remove(_ id: String) {
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.removePendingNotificationRequests(withIdentifiers: [id])
    }
}
