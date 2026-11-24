// SPDX-License-Identifier: AGPL-3.0-only

import PhotosUI
import SwiftUI
import UIKit

/// Over the edit profile screen: the sheet with the picture's sources, the system's photo
/// library and camera, and the camera explanation before the system asks for it, as for the
/// chat's "Take photo". Every picture is made upright and free of its metadata (`avatarImage`)
/// before the editor gets it.
struct ProfileOverlays: View {
    let state: ProfileEditUiState
    let actions: ProfileController
    @State private var pickingPhoto = false
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var takingPhoto = false
    /// The camera explanation before "Take photo", while the camera is not allowed.
    @State private var photoQuestion: CameraPermission?
    @Environment(\.cameraGateway) private var camera

    var body: some View {
        ZStack {
            // Keeps this layer as large as the screen without taking any touch.
            Color.clear.allowsHitTesting(false)
            if state.edit.sheet == .avatar {
                AvatarSourceSheet(
                    hasAvatar: state.hasAvatar,
                    onLibrary: pickPhoto,
                    onCamera: takePhoto,
                    onRemove: actions.removeAvatar,
                    onDismiss: actions.closeSheet
                )
            }
            if let photoQuestion {
                CameraSheet(
                    reason: Strings.cameraReasonAvatar,
                    blocked: photoQuestion == .denied,
                    onContinue: askForCamera,
                    onOpenSettings: {
                        self.photoQuestion = nil
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    },
                    onDismiss: { self.photoQuestion = nil }
                )
            }
        }
        // The system's photo picker runs outside the app and hands over only the picture the
        // user chose, so it needs no permission.
        .photosPicker(isPresented: $pickingPhoto, selection: $pickedPhoto, matching: .images)
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            pickedPhoto = nil
            Task {
                let bytes = try? await item.loadTransferable(type: Data.self)
                let image = await Task.detached { bytes.flatMap(avatarImage) }.value
                deliver(image)
            }
        }
        .fullScreenCover(isPresented: $takingPhoto) {
            CameraPicker { photo in
                takingPhoto = false
                guard let photo else { return }
                Task {
                    let image = await Task.detached { avatarImage(cameraPhoto: photo) }.value
                    deliver(image)
                }
            }
            .ignoresSafeArea()
        }
    }

    private func pickPhoto() {
        actions.closeSheet()
        pickingPhoto = true
    }

    private func takePhoto() {
        actions.closeSheet()
        if !CameraPicker.available {
            // A device without a camera.
            actions.avatarUnreadable()
            return
        }
        let permission = camera.permission()
        if permission == .granted {
            takingPhoto = true
        } else {
            photoQuestion = permission
        }
    }

    /// Lets the system ask; the photo is taken once the camera is allowed.
    private func askForCamera() {
        photoQuestion = nil
        camera.request { granted in
            if granted {
                Task { @MainActor in takingPhoto = true }
            }
        }
    }

    private func deliver(_ image: Data?) {
        if let image {
            actions.pickAvatar(image)
        } else {
            actions.avatarUnreadable()
        }
    }
}

/// Where a new profile picture comes from, or removing the one there is.
private struct AvatarSourceSheet: View {
    let hasAvatar: Bool
    let onLibrary: () -> Void
    let onCamera: () -> Void
    let onRemove: () -> Void
    let onDismiss: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.profileAvatarTitle, onDismiss: onDismiss) {
            MumcordList(horizontalMargin: 0, background: theme.colors.bgHover) {
                MumcordListRow(title: Strings.imageSourceLibrary, icon: MumcordIcons.image, action: onLibrary)
                    .accessibilityIdentifier("avatar-library")
                MumcordListRow(title: Strings.imageSourceCamera, icon: MumcordIcons.camera, divider: true, action: onCamera)
                    .accessibilityIdentifier("avatar-camera")
                if hasAvatar {
                    MumcordListRow(
                        title: Strings.profileAvatarRemove,
                        icon: MumcordIcons.trash,
                        divider: true,
                        danger: true,
                        action: onRemove
                    )
                    .accessibilityIdentifier("avatar-remove")
                }
            }
        }
    }
}
