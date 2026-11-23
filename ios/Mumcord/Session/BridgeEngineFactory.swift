// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The real engine, with its data in a folder of its own inside the app's private files and
/// its secrets in the Keychain.
enum BridgeEngineFactory {
    static let make: EngineFactory = { listener in
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dataDir = support.appendingPathComponent("engine", isDirectory: true)
        try FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        return try MumcordEngine(
            config: EngineConfig(dataDir: dataDir.path),
            storage: KeychainSecureStorage(),
            listener: listener
        )
    }
}
