// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.ui.camera.CameraGateway

/** The camera permission as a test sets it. */
class FakeCameraGateway(@Volatile var isGranted: Boolean = true) : CameraGateway {
    override fun granted(): Boolean = isGranted

    @Volatile
    override var promptBlocked: Boolean = false
}
