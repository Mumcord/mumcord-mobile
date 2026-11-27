// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge
import SwiftUI

/// Height of the camera picture in the preview sheet, as in the mockup.
private let previewHeight: CGFloat = 340
/// Inset of the camera chip and the switch button from the picture's edges.
private let previewInset: CGFloat = 12

/// The preview sheet "Share your camera" (mockup `13-camera-share`): the camera's picture with
/// the camera's name and the switch between front and back, the note that the camera pauses in
/// the background, "Start sharing" and "Cancel". Nothing is sent before "Start sharing";
/// putting the sheet away cancels. The mockup's "Blur background" row is left out: it could not
/// be used on phones (no dead controls).
struct CameraShareSheet: View {
    let sheet: CameraSheetState
    let controller: CameraShareController
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        SheetOverlay(title: Strings.cameraShareTitle, onDismiss: controller.stop) {
            CameraPreviewCard(sheet: sheet, picture: controller.camera.source?.preview(), onSwitch: controller.switchCamera)
            if let problem = sheet.problem {
                MumcordBanner(text: problem.text, icon: MumcordIcons.alert, tone: .danger, horizontalMargin: 0)
                    .accessibilityIdentifier("camera-share-problem")
            }
            HStack(alignment: .top, spacing: MumcordSpace.s2) {
                MumcordIcon(icon: MumcordIcons.info, label: nil, tint: theme.colors.accent, size: MumcordSize.iconMd)
                ParagraphText(Strings.cameraSharePauses, style: .small, color: theme.colors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .combine)
            MumcordButton(
                text: sheet.starting ? Strings.cameraShareStarting : Strings.cameraShareStart,
                fullWidth: true,
                action: controller.start
            )
            .disabled(sheet.starting)
            .accessibilityIdentifier("camera-share-start")
            MumcordButton(text: Strings.cameraShareCancel, style: .secondary, fullWidth: true, action: controller.stop)
                .accessibilityIdentifier("camera-share-cancel")
        }
    }
}

/// The camera's picture (`.cam`) with the camera's name and, on a phone with two, the switch.
private struct CameraPreviewCard: View {
    let sheet: CameraSheetState
    /// Nothing while the camera is not open yet.
    let picture: AnyView?
    let onSwitch: () -> Void
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.lg)
        ZStack {
            colors.mediaBg
            picture
        }
        .overlay(alignment: .topLeading) {
            HStack(spacing: 6) {
                MumcordIcon(icon: MumcordIcons.camera, label: nil, tint: colors.mediaText, size: MumcordSize.iconSm)
                LineText(sheet.facing.label, style: .captionStrong, color: colors.mediaText)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(colors.mediaScrim, in: Capsule())
            .padding(previewInset)
            .accessibilityElement(children: .combine)
        }
        .overlay(alignment: .bottomTrailing) {
            if sheet.canSwitch {
                MumcordStageButton(icon: MumcordIcons.switchCamera, label: Strings.cameraShareSwitch, action: onSwitch)
                    .padding(previewInset)
                    .accessibilityIdentifier("camera-share-switch")
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: previewHeight)
        .clipShape(shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Strings.cameraSharePreview)
        .accessibilityIdentifier("camera-share-preview")
    }
}

/// Above the voice bar and the voice view's buttons: while the camera is shared, that it is on
/// and how many watch, with the switch and Stop; after it stopped by itself, why. No mockup.
struct CameraBar: View {
    let state: CameraShareUiState
    let controller: CameraShareController
    var horizontalMargin: CGFloat = MumcordSpace.s2

    var body: some View {
        if let sharing = state.sharing {
            SharingBar(sharing: sharing, controller: controller, horizontalMargin: horizontalMargin)
        } else if let notice = state.notice {
            HStack(spacing: 0) {
                MumcordBanner(
                    text: notice.text,
                    title: Strings.cameraStopped,
                    icon: MumcordIcons.cameraOff,
                    tone: .warning,
                    horizontalMargin: horizontalMargin
                )
                MumcordIconButton(icon: MumcordIcons.x, label: Strings.cameraNoticeDismiss, action: controller.dismissNotice)
                    .accessibilityIdentifier("camera-notice-dismiss")
            }
            .padding(.trailing, horizontalMargin)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("camera-notice")
        }
    }
}

private struct SharingBar: View {
    let sharing: CameraSharingState
    let controller: CameraShareController
    let horizontalMargin: CGFloat
    @Environment(\.mumcordTheme) private var theme

    var body: some View {
        let colors = theme.colors
        let shape = RoundedRectangle(cornerRadius: MumcordRadius.card)
        HStack(spacing: MumcordSpace.s2) {
            MumcordIcon(icon: MumcordIcons.camera, label: nil, tint: colors.live)
            ParagraphText(Strings.cameraOnWatching(sharing.viewers), style: .smallStrong, color: colors.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            if sharing.canSwitch {
                MumcordIconButton(icon: MumcordIcons.switchCamera, label: Strings.cameraShareSwitch, action: controller.switchCamera)
                    .accessibilityIdentifier("camera-bar-switch")
            }
            MumcordButton(text: Strings.cameraStop, style: .danger, small: true, action: controller.stop)
                .accessibilityIdentifier("camera-bar-stop")
        }
        .padding(EdgeInsets(top: MumcordSpace.s1, leading: MumcordSpace.s3, bottom: MumcordSpace.s1, trailing: MumcordSpace.s2))
        .background(colors.bgSelected, in: shape)
        .overlay { shape.strokeBorder(colors.border, lineWidth: 1) }
        .padding(.horizontal, horizontalMargin)
        .padding(.bottom, MumcordSpace.s2)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("camera-bar")
    }
}

extension CameraFacing {
    var label: String {
        switch self {
        case .front: Strings.cameraShareFront
        case .back: Strings.cameraShareBack
        }
    }
}

extension CameraProblem {
    var text: String {
        switch self {
        case .notConnected: Strings.cameraProblemNotConnected
        case .busy: Strings.cameraProblemBusy
        case .permissionDenied: Strings.cameraProblemPermission
        case .cameraFailed: Strings.cameraProblemFailed
        case .directConnectionsOff: Strings.cameraProblemDirectOff
        case .noCertificate: Strings.cameraProblemNoCertificate
        case .full: Strings.cameraProblemFull
        case .relayLost: Strings.cameraProblemRelayLost
        case .refused: Strings.cameraProblemRefused
        }
    }
}
