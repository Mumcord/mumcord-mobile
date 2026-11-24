// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import Observation

/// The user's own profile on the shown server (FR-5.80): read whenever the server says it may
/// have changed, and edited on the edit profile screen. `onClosed` leaves that screen.
@MainActor
@Observable
final class ProfileController {
    /// Nil while no server is shown or the profile has not been read yet.
    private(set) var own: OwnProfile?
    /// The open edit, or nil while the editor is closed.
    private(set) var edit: ProfileEdit?

    /// Set by the More tab, whose page the editor is.
    @ObservationIgnored var onClosed: () -> Void = {}
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private var reading: Task<Void, Never>?

    private struct ProfileKey: Equatable {
        let session: UInt32
        let name: String
        let changes: Int
    }

    init(store: SessionStore) {
        self.store = store
        let states = store.states()
        Task { [weak self] in
            var last: ProfileKey?
            for await state in states {
                guard let self else { return }
                let key = state.showsServer ? state.server.map {
                    ProfileKey(session: $0.selfSession, name: $0.people[$0.selfSession]?.name ?? "", changes: state.ownProfileChanges)
                } : nil
                if key == last { continue }
                last = key
                self.read(key)
            }
        }
    }

    private func read(_ key: ProfileKey?) {
        reading?.cancel()
        guard let key else {
            own = nil
            edit = nil
            return
        }
        reading = Task { [store] in
            let read = await store.personProfile(key.session)
            if Task.isCancelled { return }
            // A failed read leaves the profile as it was; the next change reads it again.
            if case .ok(let profile) = read {
                let next = ownProfile(name: key.name, profile: profile)
                if own != next { own = next }
            }
        }
    }

    /// Opens the editor on the profile as it is and reads the server's limit for the comment.
    func open() {
        let comment = own?.comment ?? ""
        edit = ProfileEdit(comment: comment, initialComment: comment)
        Task {
            guard let limit = await store.commentLimit() else { return }
            edit?.limit = limit
        }
    }

    /// Leaves the editor; what was not saved is dropped.
    func close() {
        onClosed()
    }

    /// Drops the open edit; called when the editor is left.
    func discard() {
        edit = nil
    }

    func changeComment(_ comment: String) {
        edit?.comment = comment
        edit?.error = nil
    }

    func openAvatarSheet() {
        edit?.sheet = .avatar
    }

    func closeSheet() {
        edit?.sheet = nil
    }

    /// A new picture, already re-encoded without its metadata on the phone.
    func pickAvatar(_ image: Data) {
        edit?.avatar = .set(image)
        edit?.sheet = nil
        edit?.error = nil
    }

    func avatarUnreadable() {
        edit?.sheet = nil
        edit?.error = .avatarUnreadable
    }

    /// Shows no picture: removes the server's one; with none there, only the new one is dropped.
    func removeAvatar() {
        edit?.avatar = own?.avatar != nil ? .remove : .keep
        edit?.sheet = nil
        edit?.error = nil
    }

    /// Saves what changed: the avatar first, then the comment; the editor closes when both are in.
    func save() {
        guard var edit, edit.canSave else { return }
        edit.saving = true
        edit.error = nil
        self.edit = edit
        Task { [store] in
            let avatar: Outcome<Void>
            switch edit.avatar {
            case .keep: avatar = .ok(())
            case .remove: avatar = await store.setOwnAvatar(nil)
            case .set(let image): avatar = await store.setOwnAvatar(image)
            }
            let saved: Outcome<Void>
            if case .ok = avatar, edit.commentChanged {
                saved = await store.setOwnComment(edit.comment)
            } else {
                saved = avatar
            }
            switch saved {
            case .ok:
                // Only while the editor is still open: the user may have left it meanwhile.
                if self.edit != nil { close() }
            case .failed:
                guard self.edit != nil else { return }
                // A saved avatar is not sent again with the next try.
                if case .ok = avatar { self.edit?.avatar = .keep }
                self.edit?.saving = false
                self.edit?.error = .saveFailed
            }
        }
    }
}
