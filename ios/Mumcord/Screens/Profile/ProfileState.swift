// SPDX-License-Identifier: AGPL-3.0-only

import Foundation
import MumcordBridge

/// What the edit is going to do with the avatar when it is saved.
enum AvatarChange: Equatable {
    case keep
    case remove
    /// A new picture, already re-encoded on the phone (see `avatarImage`).
    case set(Data)
}

/// Why the edit profile screen shows an error.
enum ProfileError: Equatable {
    /// The chosen picture could not be read or is no picture.
    case avatarUnreadable
    /// The server refused or the connection failed while saving.
    case saveFailed
    /// The engine refused the comment as too long for the server. The counter counts the typed
    /// characters, as on Android; what is sent is the comment as Mumble HTML (an escaped `<` or
    /// `&`, a `<br>` per line break), which can be longer.
    case commentTooLong
}

/// A sheet over the edit profile screen.
enum ProfileSheet: Equatable {
    /// Where a new picture comes from, or removing it.
    case avatar
    /// The camera explanation before "Take photo", while the camera is not allowed; `denied`
    /// when only the system settings can allow it.
    case camera(CameraPermission)
}

/// The edit profile screen (mockup `20-edit-profile`, FR-5.80): the comment as it is typed and
/// the avatar change, saved together. The name is the user name of the connection and cannot be
/// changed here.
struct ProfileEdit: Equatable {
    var comment: String
    /// The comment as it was read; an unchanged comment is not sent again.
    var initialComment: String
    var avatar = AvatarChange.keep
    /// The server's limit for a comment, in characters; nil until it was read, 0 when the server
    /// has none (Mumble's way of saying so, as for chat messages).
    var limit: UInt32?
    var sheet: ProfileSheet?
    var saving = false
    var error: ProfileError?

    /// The length the server's limit is counted in: UTF-16 units, as on Android.
    var length: Int { comment.utf16.count }

    /// The limit the counter shows; nil while it is unknown or the server has none.
    var shownLimit: Int? {
        guard let limit, limit > 0 else { return nil }
        return Int(limit)
    }

    var tooLong: Bool {
        guard let shownLimit else { return false }
        return length > shownLimit
    }

    var commentChanged: Bool { comment != initialComment }

    var changed: Bool { commentChanged || avatar != .keep }

    var canSave: Bool { changed && !tooLong && !saving }
}

/// The user's own profile on the shown server, as the More tab and the editor show it.
struct OwnProfile: Equatable {
    let name: String
    let comment: String
    /// The avatar's bytes as the server holds them; nil without one.
    let avatar: Data?

    /// The first line of the comment, for the More tab.
    var commentLine: String {
        comment.split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty } ?? ""
    }
}

/// The profile as read from the engine, for the user called `name`.
func ownProfile(name: String, profile: Profile) -> OwnProfile {
    OwnProfile(name: name, comment: profile.commentText, avatar: profile.avatar?.bytes)
}

/// The edit profile screen as a value.
struct ProfileEditUiState: Equatable {
    let name: String
    let edit: ProfileEdit
    /// The picture shown: the new one, none after removing, or the current one.
    let avatar: Data?

    var hasAvatar: Bool { avatar != nil }
}

func profileEditUiState(own: OwnProfile, edit: ProfileEdit) -> ProfileEditUiState {
    let avatar: Data?
    switch edit.avatar {
    case .keep: avatar = own.avatar
    case .remove: avatar = nil
    case .set(let image): avatar = image
    }
    return ProfileEditUiState(name: own.name, edit: edit, avatar: avatar)
}
