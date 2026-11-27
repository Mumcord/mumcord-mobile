// SPDX-License-Identifier: AGPL-3.0-only

/// What the stream views can ask for, as closures, so the views stay free of the controller.
struct StreamActions {
    let open: (StreamKey) -> Void
    let tileShown: (StreamKey, Bool) -> Void
    let collapse: () -> Void
    let expand: () -> Void
    let fillScreen: () -> Void
    let close: () -> Void
    let toggleLandscape: () -> Void
    let retry: (StreamKey) -> Void
    let changeVolume: (StreamKey, Int) -> Void
    let answerDirectConnections: (Bool) -> Void
}

extension StreamActions {
    @MainActor
    init(controller: StreamsController) {
        self.init(
            open: controller.open,
            tileShown: controller.tileShown,
            collapse: controller.collapse,
            expand: controller.expand,
            fillScreen: controller.fillScreen,
            close: controller.close,
            toggleLandscape: controller.toggleLandscape,
            retry: controller.retry,
            changeVolume: controller.changeVolume,
            answerDirectConnections: controller.answerDirectConnections
        )
    }
}
