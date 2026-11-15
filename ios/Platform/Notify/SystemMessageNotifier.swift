// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import UserNotifications

private let chatKeyField = "chat"

/// Message notifications on iOS: local notifications only, one per conversation. The request's
/// identifier is the conversation's key, so a later message replaces the notification instead
/// of adding one, and the thread identifier groups what belongs to one conversation. Sender and
/// text are handed to the system as they are, so the phone's "Show Previews" setting decides
/// what is readable on the lock screen.
final class SystemMessageNotifier: NSObject, MessageNotifier, UNUserNotificationCenterDelegate, @unchecked Sendable {
    private let center: UNUserNotificationCenter
    private let newMessage: String
    private let log: @Sendable (String) -> Void
    private let lock = NSLock()
    private var open: (@MainActor @Sendable (String) -> Void)?
    /// What the actions of the app's other notifications do, by their category.
    private var responses: [String: @MainActor @Sendable (String) -> Void] = [:]

    /// - Parameters:
    ///   - newMessage: what stands in place of a message whose text may not be shown.
    ///   - log: receives short technical messages; never names or message texts.
    init(
        center: UNUserNotificationCenter = .current(),
        newMessage: String,
        log: @escaping @Sendable (String) -> Void
    ) {
        self.center = center
        self.newMessage = newMessage
        self.log = log
        super.init()
        // Set at the start of the app: a tap that started the app is delivered right after.
        center.delegate = self
    }

    /// What a tap on the notification of the conversation with that key does.
    func onOpen(_ open: @escaping @MainActor @Sendable (String) -> Void) {
        lock.withLock { self.open = open }
    }

    /// What a tap on a notification of this category does; it receives the action's identifier.
    /// The app has one notification delegate, so the other notifications are answered here too.
    func onResponse(category: String, _ handle: @escaping @MainActor @Sendable (String) -> Void) {
        lock.withLock { responses[category] = handle }
    }

    func show(_ notice: MessageNotice) {
        guard let newest = notice.lines.last else { return }
        let content = UNMutableNotificationContent()
        if notice.group {
            content.title = notice.title
            content.subtitle = newest.sender
        } else {
            content.title = newest.sender
        }
        content.body = newest.text ?? newMessage
        content.sound = .default
        content.threadIdentifier = notice.key
        content.userInfo = [chatKeyField: notice.key]
        let request = UNNotificationRequest(identifier: notice.key, content: content, trigger: nil)
        center.add(request) { [log] error in
            // Without the permission the system drops it; the app's own badges still count.
            if let error { log("message notification failed: \(type(of: error))") }
        }
    }

    func cancel(_ key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
        center.removePendingNotificationRequests(withIdentifiers: [key])
    }

    /// Only the message notifications: the others (a notice that the app disconnected) stay.
    func cancelAll() {
        center.getDeliveredNotifications { [center] delivered in
            let keys = delivered.map(\.request).filter(Self.isMessage).map(\.identifier)
            center.removeDeliveredNotifications(withIdentifiers: keys)
        }
        center.getPendingNotificationRequests { [center] pending in
            center.removePendingNotificationRequests(withIdentifiers: pending.filter(Self.isMessage).map(\.identifier))
        }
    }

    private static func isMessage(_ request: UNNotificationRequest) -> Bool {
        request.content.userInfo[chatKeyField] != nil
    }

    // The engine leaves out the chat that is on screen, so what arrives here is about a chat
    // the user does not see: it is shown also while the app is in front.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        if let handle = lock.withLock({ responses[content.categoryIdentifier] }) {
            let action = response.actionIdentifier
            Task { @MainActor in
                handle(action)
                completionHandler()
            }
            return
        }
        let key = content.userInfo[chatKeyField] as? String
        let open = lock.withLock { self.open }
        if let key, let open, response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            Task { @MainActor in open(key) }
        }
        completionHandler()
    }
}

/// The notification permission of the phone, and whether the app asked for it.
final class SystemNotificationGateway: NotificationGateway, @unchecked Sendable {
    private static let askedKey = "notificationsAsked"

    private let center: UNUserNotificationCenter
    private let defaults: UserDefaults
    private let lock = NSLock()
    private var allowed = false

    init(center: UNUserNotificationCenter = .current(), defaults: UserDefaults = .standard) {
        self.center = center
        self.defaults = defaults
    }

    func granted() -> Bool {
        lock.withLock { allowed }
    }

    var asked: Bool {
        get { defaults.bool(forKey: Self.askedKey) }
        set { defaults.set(newValue, forKey: Self.askedKey) }
    }

    func request(_ completion: @escaping @Sendable (Bool) -> Void) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            completion(granted)
        }
    }

    func refresh(_ completion: @escaping @Sendable () -> Void) {
        center.getNotificationSettings { [weak self] settings in
            let allowed: Bool
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: allowed = true
            default: allowed = false
            }
            self?.lock.withLock { self?.allowed = allowed }
            completion()
        }
    }
}
