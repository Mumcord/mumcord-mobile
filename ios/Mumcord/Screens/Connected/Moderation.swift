// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// The moderation entries of the person sheet (FR-5.90 … FR-5.93). The server decides: an entry
/// is shown only when its answer grants the right, never as a disabled control. Moving and
/// server mute are rights of the person's channel; kick and ban are rights of the root channel,
/// as on the desktop.
struct ModerationRows: Equatable {
    var move = false
    var serverMute = false
    /// The person is muted by the server, so the mute entry lifts it.
    var serverMuted = false
    var kick = false
    var ban = false

    var any: Bool { move || serverMute || kick || ban }
}

extension ServerModel {
    /// What the server lets the user do to `person`; nothing for the user themselves.
    func moderationRows(_ person: Person) -> ModerationRows {
        if person.isSelf { return ModerationRows() }
        let channel = channels[person.channelId]
        let root = channels.values.first { $0.parentId == nil }?.permissions
        return ModerationRows(
            move: channel?.permissions.movePeople == true,
            // A standard server refuses to mute people in a temporary channel, so it is not
            // offered there (as on the desktop).
            serverMute: channel.map { $0.permissions.muteDeafen && !$0.temporary } ?? false,
            serverMuted: person.serverMuted,
            kick: root?.kick == true,
            ban: root?.ban == true
        )
    }
}

/// The ban lengths the sheet offers, as on the desktop (FR-10.12); 0 is permanent.
let banDurationsSecs: [UInt32] = [3_600, 86_400, 604_800, 2_592_000, 0]

/// What the user chose in the ban sheet.
struct BanForm: Equatable {
    var reason = ""
    var durationSecs = banDurationsSecs[0]
    var byCertificate = true
    var removeNow = true
}

extension BanTarget {
    /// Only the server's default ban is possible: its ban list cannot be read, or it did not
    /// tell the address, without which no entry of the user's own can be written (FR-10.13).
    var quick: Bool { !banListReadable || address == nil }

    /// The certificate can be part of the ban: the person has one and the ban is not the quick one.
    var certificateChoice: Bool { !quick && hasCertificate }

    /// "Remove from server now" needs the kick right; the quick ban always removes.
    var removeNowChoice: Bool { !quick && canKick }
}

/// The ban as the bridge takes it. A standard server matches the address, so the address is
/// always part of the entry (as on the desktop); the certificate only when the person has one.
/// The quick ban takes the server's own length and targets.
///
/// - Parameter durationSecs: what is sent as the length: the chosen one, unless a test
///   shortens it.
func banInput(target: BanTarget, form: BanForm, force: Bool, durationSecs: UInt32? = nil) -> BanInput {
    let trimmed = form.reason.trimmed
    let reason = trimmed.isEmpty ? nil : trimmed
    if target.quick {
        return BanInput(
            reason: reason,
            durationSecs: 0,
            byCertificate: true,
            byAddress: true,
            removeNow: true,
            force: false,
            quick: true
        )
    }
    return BanInput(
        reason: reason,
        durationSecs: durationSecs ?? form.durationSecs,
        byCertificate: target.hasCertificate && form.byCertificate,
        byAddress: true,
        removeNow: target.canKick && form.removeNow,
        force: force,
        quick: false
    )
}

/// The moderation step that replaced the person sheet, for the person whose sheet it was.
enum ModerationLocal: Equatable {
    /// The channel picker of "Move to channel…".
    case move(session: UInt32)
    case kick(KickStep)
    case ban(BanStep)

    var session: UInt32 {
        switch self {
        case .move(let session): session
        case .kick(let kick): kick.session
        case .ban(let ban): ban.session
        }
    }
}

struct KickStep: Equatable {
    let session: UInt32
    var reason = ""
    var busy = false
    var failure: ModerationFailure?
}

struct BanStep: Equatable {
    let session: UInt32
    /// When the sheet opened, from which "Ends on" counts (measured once).
    let openedAt: Date
    /// What the server said about the person; nil while it is asked for.
    var target: BanTarget?
    var form = BanForm()
    var busy = false
    var failure: ModerationFailure?
    /// The entry changed on the server meanwhile: ask before saving anyway.
    var conflict = false
}

/// A channel a person can be moved to, indented like the channel list.
struct MoveChoice: Equatable, Identifiable {
    let id: UInt32
    let name: String
    let depth: Int
}

/// The moderation sheet as it is drawn. Names are untrusted text.
enum ModerationSheet: Equatable {
    case move(name: String, choices: [MoveChoice])
    case kick(KickSheetState)
    case ban(BanSheetState)
}

struct KickSheetState: Equatable {
    let name: String
    let channelName: String
    let reason: String
    let busy: Bool
    let failure: ModerationFailure?
}

struct BanSheetState: Equatable {
    let name: String
    let channelName: String
    /// Nil while the server is asked about the person.
    var target: BanTarget?
    var form: BanForm
    let openedAt: Date
    let busy: Bool
    var failure: ModerationFailure?
    let conflict: Bool

    var loading: Bool { target == nil && failure == nil }

    /// When the chosen ban ends; nil for a permanent one and for the server's default.
    var endsAt: Date? {
        guard let target, !target.quick, form.durationSecs != 0 else { return nil }
        return openedAt.addingTimeInterval(TimeInterval(form.durationSecs))
    }
}

/// The sheet of `local` for `person`, who is on `server`.
func moderationSheet(server: ServerModel, person: Person, local: ModerationLocal) -> ModerationSheet {
    let channelName = server.channels[person.channelId]?.name ?? ""
    switch local {
    case .move:
        var choices: [MoveChoice] = []
        for case .channel(let item) in channelRows(root: server.root, selfChannelId: nil, collapsed: [])
        where item.id != person.channelId {
            choices.append(MoveChoice(id: item.id, name: item.name, depth: item.depth))
        }
        return .move(name: person.name, choices: choices)
    case .kick(let kick):
        return .kick(KickSheetState(
            name: person.name,
            channelName: channelName,
            reason: kick.reason,
            busy: kick.busy,
            failure: kick.failure
        ))
    case .ban(let ban):
        return .ban(BanSheetState(
            name: person.name,
            channelName: channelName,
            target: ban.target,
            form: ban.form,
            openedAt: ban.openedAt,
            busy: ban.busy,
            failure: ban.failure,
            conflict: ban.conflict
        ))
    }
}
