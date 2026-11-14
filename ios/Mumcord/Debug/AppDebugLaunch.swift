// SPDX-License-Identifier: AGPL-3.0-only

#if DEBUG
import Foundation
import MumcordBridge

/// Lets a debug build run against the fake engine with canned data, so UI tests and screenshot
/// tests can walk through the app without a server:
///
///     -mumcordFakeEngine <options>   comma-separated, `plain` for none of them:
///         servers          the server list of the mockup, with ping answers
///         prompt-unknown   connecting asks whether to trust an unknown certificate
///         prompt-changed   connecting warns about a changed certificate
///         unreachable      the first connect fails because the server cannot be reached
///         server-password  the server only accepts the password "open sesame"
///         locks            the Music channel needs the password "secret", AFK refuses entry
///     -mumcordSetupDone YES          skip the first-start setup
///     -mumcordTheme dark|light       force a theme (default: the system's)
///
/// Nothing is stored: the setup flag lives in memory. Release builds do not contain this.
struct AppDebugLaunch {
    let options: Set<String>
    let setupDone: Bool
    let theme: MumcordThemeMode

    /// Launch arguments of the form `-key value` are readable as user defaults.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> AppDebugLaunch? {
        guard let options = defaults.string(forKey: "mumcordFakeEngine") else { return nil }
        return AppDebugLaunch(
            options: Set(options.split(separator: ",").map(String.init)),
            setupDone: defaults.bool(forKey: "mumcordSetupDone"),
            theme: defaults.string(forKey: "mumcordTheme").flatMap(MumcordThemeMode.init(rawValue:)) ?? .system
        )
    }

    var engineFactory: EngineFactory {
        let options = options
        return { listener in
            var prompt: CertificatePrompt?
            if options.contains("prompt-unknown") {
                prompt = CertificatePrompt(fingerprint: Mockup.fingerprint, previousFingerprint: nil)
            } else if options.contains("prompt-changed") {
                prompt = CertificatePrompt(
                    fingerprint: Mockup.fingerprint,
                    previousFingerprint: Mockup.previousFingerprint
                )
            }
            let engine = FakeEngine(
                listener: listener,
                server: mockupServer(),
                prompt: prompt,
                servers: options.contains("servers") ? Mockup.servers : []
            )
            engine.pings = Mockup.pings
            if options.contains("unreachable") {
                engine.connectFailure = .ServerUnreachable(detail: "unreachable")
            }
            if options.contains("server-password") {
                engine.requiredPassword = "open sesame"
            }
            if options.contains("locks") {
                engine.channelPasswords = [Mockup.music: "secret"]
                engine.deniedChannels = [Mockup.afk]
            }
            return engine
        }
    }
}

/// Keeps the first-start flag for one run of the app.
final class InMemorySetupStore: SetupStore {
    private(set) var firstStartDone: Bool

    init(firstStartDone: Bool = false) {
        self.firstStartDone = firstStartDone
    }

    func markFirstStartDone() {
        firstStartDone = true
    }
}
#endif
