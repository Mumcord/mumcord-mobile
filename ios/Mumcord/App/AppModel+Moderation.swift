// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The moderation section of the person sheet (FR-5.90 … FR-5.93): moving, server mute, kick
/// and ban of the person whose sheet is open. The step that replaced the person sheet is
/// `ConnectedLocal.moderation`.
///
/// Move and server mute act at once; a refusal reaches the screen as the server's refusal event
/// like any other. Kick and ban ask first in a sheet of their own, which also shows a refusal:
/// the engine reports none of those twice.
extension AppModel {
    /// Opens the channel picker for the person.
    func openMove() {
        open { .move(session: $0) }
    }

    /// Moves the person; the sheets close, the channel list shows where they are.
    func movePerson(to channelId: UInt32) {
        guard case .move(let session) = connectedLocal.moderation else { return }
        closePerson()
        Task { reportUnlessRefused(await store.movePerson(session, to: channelId)) }
    }

    /// Server-mutes the person or lifts it; the entry follows once the server echoes it.
    func toggleServerMute() {
        guard let session = connectedLocal.personSession,
              let muted = store.state.server?.people[session]?.serverMuted else { return }
        Task {
            let outcome = await store.setServerMute(session, muted: !muted)
            guard case .failure = outcome else { return }
            // The refusal is shown on the screen behind the sheet.
            if connectedLocal.personSession == session { closePerson() }
            reportUnlessRefused(outcome)
        }
    }

    func openKick() {
        open { .kick(KickStep(session: $0)) }
    }

    func changeKickReason(_ reason: String) {
        updateKick { $0.reason = reason }
    }

    func confirmKick() {
        guard case .kick(let kick) = connectedLocal.moderation, !kick.busy else { return }
        updateKick {
            $0.busy = true
            $0.failure = nil
        }
        let reason = kick.reason.trimmed
        Task {
            switch await store.kickPerson(kick.session, reason: reason.isEmpty ? nil : reason) {
            case .success: finished(kick.session)
            case .failure(let failure):
                updateKick(kick.session) {
                    $0.busy = false
                    $0.failure = failure
                }
            }
        }
    }

    /// Opens the ban sheet and asks the server what it needs about the person.
    func openBan() {
        guard let session = connectedLocal.personSession else { return }
        open { .ban(BanStep(session: $0, openedAt: Date())) }
        Task {
            let target = await store.banTarget(session)
            updateBan(session) { ban in
                switch target {
                case .success(let target):
                    ban.target = target
                    ban.form.byCertificate = target.hasCertificate
                case .failure(let failure):
                    ban.failure = failure
                }
            }
        }
    }

    func changeBanReason(_ reason: String) {
        updateBan { $0.form.reason = reason }
    }

    func selectBanDuration(_ secs: UInt32) {
        updateBan { $0.form.durationSecs = secs }
    }

    func setBanByCertificate(_ on: Bool) {
        updateBan { $0.form.byCertificate = on }
    }

    func setBanRemoveNow(_ on: Bool) {
        updateBan { $0.form.removeNow = on }
    }

    /// "Kick only instead": the kick sheet with the reason typed so far.
    func kickInstead() {
        guard case .ban(let ban) = connectedLocal.moderation, !ban.busy else { return }
        connectedLocal.moderation = .kick(KickStep(session: ban.session, reason: ban.form.reason))
    }

    func confirmBan() {
        sendBan(force: false)
    }

    /// "Save mine anyway" after the server's entry changed meanwhile.
    func banAnyway() {
        sendBan(force: true)
    }

    private func sendBan(force: Bool) {
        guard case .ban(let ban) = connectedLocal.moderation, let target = ban.target, !ban.busy else { return }
        updateBan {
            $0.busy = true
            $0.failure = nil
            $0.conflict = false
        }
        let input = banInput(target: target, form: ban.form, force: force, durationSecs: TestSettings.banSeconds)
        Task {
            switch await store.banPerson(ban.session, input) {
            case .success(.saved), .success(.quickBanned): finished(ban.session)
            case .success(.conflict):
                updateBan(ban.session) {
                    $0.busy = false
                    $0.conflict = true
                }
            case .failure(let failure):
                updateBan(ban.session) {
                    $0.busy = false
                    $0.failure = failure
                }
            }
        }
    }

    private func open(_ step: (UInt32) -> ModerationLocal) {
        guard let session = connectedLocal.personSession else { return }
        connectedLocal.moderation = step(session)
    }

    /// The kick or ban went through: the person leaves the list, so their sheet goes too.
    private func finished(_ session: UInt32) {
        if connectedLocal.moderation?.session == session { closePerson() }
    }

    /// Changes the open kick step; with `session`, only when it still is that person's.
    private func updateKick(_ session: UInt32? = nil, _ change: (inout KickStep) -> Void) {
        guard case .kick(var kick) = connectedLocal.moderation, session == nil || kick.session == session else { return }
        change(&kick)
        connectedLocal.moderation = .kick(kick)
    }

    /// Changes the open ban step; with `session`, only when it still is that person's.
    private func updateBan(_ session: UInt32? = nil, _ change: (inout BanStep) -> Void) {
        guard case .ban(var ban) = connectedLocal.moderation, session == nil || ban.session == session else { return }
        change(&ban)
        connectedLocal.moderation = .ban(ban)
    }

    /// A refusal already reached the screen as the server's refusal event; anything else did not.
    private func reportUnlessRefused(_ outcome: Result<Bool, ModerationFailure>) {
        guard case .failure(let failure) = outcome else { return }
        if case .refused = failure { return }
        store.reportFailure()
    }
}
