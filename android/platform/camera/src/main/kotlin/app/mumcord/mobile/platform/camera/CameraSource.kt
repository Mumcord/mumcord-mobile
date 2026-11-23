// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

enum class CameraFacing { Front, Back }

/** Why the camera could not run. */
enum class CameraTrouble {
    /** The phone has no camera that can be used. */
    NoCamera,

    /** The camera could not be opened or stopped working. */
    Failed,
}

/**
 * Where the pictures of the shared camera come from: the phone's camera ([CameraXSource]) or,
 * in the end-to-end tests, a test pattern. Every call is made on the main thread. The camera
 * runs only between [start] and [stop]; the caller makes sure that is only while the app is on
 * screen and shows that the camera is on.
 */
interface CameraSource {
    /** The cameras this phone has; empty until the source knows. */
    val facings: StateFlow<Set<CameraFacing>>

    /**
     * Starts the camera facing [facing], or switches to it while it runs; its pictures go to
     * the engine. Without that camera the other one is used.
     */
    fun start(facing: CameraFacing)

    /** Stops the camera and releases it, so the system's camera indicator goes off. */
    fun stop()

    /** The camera's picture as the user sees it (the front camera mirrored). */
    @Composable
    fun Preview(modifier: Modifier)
}
