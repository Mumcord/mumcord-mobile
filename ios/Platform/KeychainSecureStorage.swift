// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge
import Security

/// The engine's secure storage on iOS: one generic-password Keychain item per account.
///
/// Items are readable after the first unlock following a restart, because voice must keep
/// working while the screen is locked. They stay on this device: never synced to iCloud and
/// not restored onto another device from a backup.
final class KeychainSecureStorage: SecureStorage {
    static let defaultService = "app.mumcord.mobile.secure-storage"

    private let service: String

    init(service: String = KeychainSecureStorage.defaultService) {
        self.service = service
    }

    func set(account: String, secret: Data) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: secret,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let item = query(account).merging(attributes) { _, new in new }
            status = SecItemAdd(item as CFDictionary, nil)
        }
        try check(status, operation: "set")
    }

    func get(account: String) throws -> Data? {
        var search = query(account)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        try check(status, operation: "get")
        guard let secret = result as? Data else {
            throw SecureStorageError.Failed(message: "Keychain get returned no data")
        }
        return secret
    }

    func delete(account: String) throws {
        let status = SecItemDelete(query(account) as CFDictionary)
        if status == errSecItemNotFound {
            return
        }
        try check(status, operation: "delete")
    }

    private func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }

    /// The message goes into logs, so it names only the operation and the system's status.
    private func check(_ status: OSStatus, operation: String) throws {
        guard status != errSecSuccess else {
            return
        }
        let reason = SecCopyErrorMessageString(status, nil) as String? ?? "unknown error"
        throw SecureStorageError.Failed(
            message: "Keychain \(operation) failed: \(reason) (\(status))"
        )
    }
}
