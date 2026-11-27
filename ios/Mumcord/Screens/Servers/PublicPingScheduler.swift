// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// Pings waiting for their answer at the same time, as on desktop; the engine paces them further.
let publicPingConcurrency = 4

/// Pings the servers whose rows are on screen, each once per load, a few at a time (FR-5.9).
/// Rows that scroll away before their turn are not pinged. Nothing is sent while stopped: the
/// browser stops it when it closes and when the app leaves the screen, so no ping runs in the
/// background (FR-5.43).
@MainActor
final class PublicPingScheduler {
    private let ping: @MainActor (PublicServer) async -> PublicPing
    private let onResult: (_ key: String, _ ping: PublicPing) -> Void
    private let concurrency: Int

    private var visible: [PublicServer] = []
    /// Servers that were asked in this load and either answered or are being asked.
    private var asked: Set<String> = []
    private var running: [String: Task<Void, Never>] = [:]
    private var stopped = true

    init(
        concurrency: Int = publicPingConcurrency,
        ping: @escaping @MainActor (PublicServer) async -> PublicPing,
        onResult: @escaping (_ key: String, _ ping: PublicPing) -> Void
    ) {
        self.concurrency = concurrency
        self.ping = ping
        self.onResult = onResult
    }

    /// The servers on screen now, top to bottom.
    func setVisible(_ servers: [PublicServer]) {
        visible = servers
        pump()
    }

    /// Forgets which servers were pinged, so they are asked again (a new load of the list).
    func reset() {
        cancelRunning()
        asked.removeAll()
        pump()
    }

    func start() {
        stopped = false
        pump()
    }

    /// Nothing new is sent after this; the answers of pings on their way are dropped, and those
    /// servers are asked again after `start`.
    func stop() {
        stopped = true
        visible = []
        cancelRunning()
    }

    private func cancelRunning() {
        for task in running.values { task.cancel() }
        asked.subtract(running.keys)
        running.removeAll()
    }

    private func pump() {
        while !stopped && running.count < concurrency {
            guard let next = visible.first(where: { !asked.contains($0.key) }) else { return }
            let key = next.key
            asked.insert(key)
            // The task starts after this method returns, so it is registered before it can finish.
            running[key] = Task { [weak self, ping] in
                let answer = await ping(next)
                // A cancelled ping is no longer in `running`, and a new ping of the same server
                // may be: only the task's own answer counts.
                guard let self, !Task.isCancelled, self.running.removeValue(forKey: key) != nil else { return }
                self.onResult(key, answer)
                self.pump()
            }
        }
    }
}
