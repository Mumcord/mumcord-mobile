// SPDX-License-Identifier: AGPL-3.0-only

import MumcordBridge

/// What the camera sharing screens show (FR-5.74, FR-5.75).
struct CameraShareUiState: Equatable {
    /// The preview sheet "Share your camera", while it is open.
    var sheet: CameraSheetState?
    /// The camera is shared with the channel: the "Camera on" bar.
    var sharing: CameraSharingState?
    /// Why the camera stopped by itself or could not be opened, until the user dismisses it.
    var notice: CameraProblem?

    /// Whether the camera is in use for sharing: the voice view's camera button then stops it.
    var on: Bool { sheet != nil || sharing != nil }
}

struct CameraSheetState: Equatable {
    let facing: CameraFacing
    /// The phone has a front and a back camera.
    let canSwitch: Bool
    /// "Start sharing" was tapped and the camera is not shared yet.
    let starting: Bool
    /// Why the last start failed.
    let problem: CameraProblem?
}

struct CameraSharingState: Equatable {
    let viewers: Int
    let facing: CameraFacing
    let canSwitch: Bool
}

/// The screens' state from the engine's report of the own camera and the chosen camera.
func cameraShareUiState(
    camera: OwnCamera,
    facing: CameraFacing,
    facings: Set<CameraFacing>,
    notice: CameraProblem?
) -> CameraShareUiState {
    let canSwitch = facings.count > 1
    switch camera.state {
    case .preview, .starting:
        return CameraShareUiState(
            sheet: CameraSheetState(
                facing: facing,
                canSwitch: canSwitch,
                starting: camera.state == .starting,
                problem: camera.problem
            ),
            notice: notice
        )
    case .sharing:
        return CameraShareUiState(
            sharing: CameraSharingState(viewers: Int(camera.viewers), facing: facing, canSwitch: canSwitch),
            notice: notice
        )
    case .off:
        return CameraShareUiState(notice: notice)
    }
}

/// The notice a change of the own camera leaves: a camera that was starting or shared and is
/// now off with a reason stopped by itself. Nil keeps the notice as it is.
func stoppedNotice(before: OwnCamera, after: OwnCamera) -> CameraProblem? {
    guard after.state == .off, before.state == .starting || before.state == .sharing else { return nil }
    return after.problem
}
