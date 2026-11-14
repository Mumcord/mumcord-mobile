// SPDX-License-Identifier: AGPL-3.0-only

import AVFAudio
import CallKit

/// The one place that decides whether voice sessions are shown to the phone as calls.
enum CallSystems {
    /// The phone's call service, or nil where sessions are no calls to the phone and their
    /// audio runs as the app's own audio session instead (see `CallSession`). Called once, at
    /// the start of the app: the system wants one call service per process and wants to know
    /// of it before the first call is asked for.
    ///
    /// The simulator gets none. Its call service does take an outgoing call (a test starts
    /// and ends one there), but it is reported to end such calls by itself after a moment for
    /// want of a call screen, which would end every session started in a simulator.
    static func make(log: @escaping (String) -> Void) -> CallSystem? {
        #if targetEnvironment(simulator)
        return nil
        #else
        return CallKitSystem(log: log)
        #endif
    }
}

/// The phone's call service (CallKit). The session is an outgoing audio call that is connected
/// at once, can be held by the system for a phone call, and is left out of the recent calls.
/// The system wants only one of these per process. Used on the main thread only.
final class CallKitSystem: NSObject, CallSystem, CXProviderDelegate, CXCallObserverDelegate {
    /// Calls need a handle; a session has none that would mean anything to the system.
    private static let handle = "Mumcord"

    weak var delegate: CallSystemDelegate?

    private let provider: CXProvider
    private let controller = CXCallController()
    private let observer = CXCallObserver()
    private let log: (String) -> Void
    /// The session's call, from the request to start it until it has ended.
    private var current: UUID?
    private var name = ""
    /// The app itself asked for the end of the call.
    private var endingByApp = false

    /// What the system is told about the app's calls.
    static func configuration() -> CXProviderConfiguration {
        let configuration = CXProviderConfiguration()
        // A session is not a phone call the user would want to call back.
        configuration.includesCallsInRecents = false
        configuration.supportsVideo = false
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.generic]
        return configuration
    }

    init(log: @escaping (String) -> Void) {
        self.log = log
        provider = CXProvider(configuration: Self.configuration())
        super.init()
        provider.setDelegate(self, queue: nil)
        observer.setDelegate(self, queue: nil)
    }

    deinit {
        provider.invalidate()
    }

    // MARK: CallSystem

    func start(_ call: UUID, name: String, completion: @escaping (Bool) -> Void) {
        current = call
        self.name = name
        endingByApp = false
        let action = CXStartCallAction(call: call, handle: CXHandle(type: .generic, value: Self.handle))
        action.isVideo = false
        request(action, "starting") { [weak self] accepted in
            if !accepted, self?.current == call { self?.current = nil }
            completion(accepted)
        }
    }

    func rename(_ call: UUID, name: String) {
        self.name = name
        provider.reportCall(with: call, updated: update())
    }

    func setMuted(_ call: UUID, muted: Bool) {
        request(CXSetMutedCallAction(call: call, muted: muted), "muting") { _ in }
    }

    func unhold(_ call: UUID) {
        request(CXSetHeldCallAction(call: call, onHold: false), "resuming") { _ in }
    }

    func end(_ call: UUID) {
        endingByApp = true
        request(CXEndCallAction(call: call), "ending") { [weak self] accepted in
            guard let self, !accepted else { return }
            // The call must not outlive the session.
            self.provider.reportCall(with: call, endedAt: nil, reason: .remoteEnded)
            if self.current == call { self.current = nil }
        }
    }

    private func request(_ action: CXCallAction, _ what: String, completion: @escaping (Bool) -> Void) {
        controller.request(CXTransaction(action: action)) { [weak self] error in
            DispatchQueue.main.async {
                if let error {
                    self?.log("\(what) the call failed (\((error as NSError).code))")
                }
                completion(error == nil)
            }
        }
    }

    private func update() -> CXCallUpdate {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: Self.handle)
        update.localizedCallerName = name
        update.hasVideo = false
        // Lets the user accept a phone call and keep the session: the system holds it.
        update.supportsHolding = true
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        return update
    }

    // MARK: CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        current = nil
        delegate?.callSystemReset()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
        provider.reportCall(with: action.callUUID, updated: update())
        // A channel is joined at once: there is no ringing on the other side.
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
        provider.reportOutgoingCall(with: action.callUUID, connectedAt: nil)
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        action.fulfill()
        guard action.callUUID == current else { return }
        current = nil
        if !endingByApp { delegate?.callEndedBySystem(action.callUUID) }
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        action.fulfill()
        delegate?.callMutedBySystem(action.callUUID, muted: action.isMuted)
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        action.fulfill()
        delegate?.callHeldBySystem(action.callUUID, held: action.isOnHold)
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        delegate?.callAudioChanged(active: true)
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        delegate?.callAudioChanged(active: false)
    }

    // MARK: CXCallObserverDelegate

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        guard call.hasEnded, call.uuid != current else { return }
        let othersLeft = callObserver.calls.contains { $0.uuid != current && !$0.hasEnded }
        if !othersLeft { delegate?.otherCallsEnded() }
    }
}
