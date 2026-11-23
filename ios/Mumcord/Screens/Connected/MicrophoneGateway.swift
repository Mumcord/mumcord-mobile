// SPDX-License-Identifier: AGPL-3.0-only

/// What iOS says about the microphone permission.
enum MicrophonePermission: Equatable {
    /// The system has not asked yet; it asks once.
    case undetermined
    case granted
    /// The user said no to the system. iOS does not ask again: only the system settings help.
    case denied
}

/// What the microphone question needs from the system and remembers about the user's answers.
/// Everything platform-specific stays behind it, so the flow can be tested without a device.
protocol MicrophoneGateway: AnyObject {
    func permission() -> MicrophonePermission
    /// The user has put the app's explanation away with "Not now": do not explain again.
    var declined: Bool { get set }
    /// Shows the system's question; the answer arrives on a thread of the system.
    func request(_ completion: @escaping @Sendable (Bool) -> Void)
}

/// What the connected screens show about the microphone.
enum MicrophoneNotice: Equatable {
    case none
    /// Say why the microphone is needed, then let the system ask.
    case explain
    /// The user said "Not now": the others cannot hear them; tapping lets the system ask.
    case denied
    /// As `denied`, but the system will not ask any more: point to the system settings.
    case blocked
}

/// `missing` is true while the engine wants the microphone and the permission is not there. The
/// explanation is shown once; after a "no" only the calm notice stays.
func microphoneNotice(missing: Bool, declined: Bool, permission: MicrophonePermission) -> MicrophoneNotice {
    if !missing { return .none }
    if permission == .denied { return .blocked }
    return declined ? .denied : .explain
}

/// The microphone question as the screens see it; the audio itself runs for the whole process.
protocol MicrophoneAccess: AnyObject {
    /// The current notice and every later one.
    func notices() -> AsyncStream<MicrophoneNotice>
    /// The user put the explanation away without letting the system ask.
    func notNow()
    /// Lets the system ask for the microphone.
    func ask()
    /// The app is on screen again: the permission may have changed in the system settings.
    func recheck()
}
