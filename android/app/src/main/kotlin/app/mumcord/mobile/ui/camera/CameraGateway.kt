// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit

/** The answer of the system's camera prompt, with what the system said before and after it. */
data class CameraAnswer(
    val granted: Boolean,
    val refusedBefore: Boolean,
    val refusedAfter: Boolean,
) {
    /**
     * After a second refusal the system stops showing its prompt, which it only signals by no
     * longer reporting the earlier refusal. A prompt closed without an answer looks different:
     * no refusal was known before it either.
     */
    val blocksPrompt: Boolean
        get() = !granted && refusedBefore && !refusedAfter
}

/**
 * What the camera question needs from the system and remembers about the user's answers.
 * Everything platform-specific stays behind it, so the flow can be tested without a device.
 */
interface CameraGateway {
    fun granted(): Boolean

    /** Whether the system no longer shows its prompt, so only the system settings help. */
    var promptBlocked: Boolean

    /** A phone whose camera is never asked for: the default where nothing scans. */
    object Never : CameraGateway {
        override fun granted(): Boolean = false

        override var promptBlocked: Boolean
            get() = false
            set(_) = Unit
    }
}

/** Remembers what an answer of the system's prompt says about asking again. */
fun CameraGateway.record(answer: CameraAnswer) {
    if (answer.granted) {
        promptBlocked = false
    } else if (answer.blocksPrompt) {
        promptBlocked = true
    }
}

class AndroidCameraGateway(context: Context) : CameraGateway {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun granted(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override var promptBlocked: Boolean
        get() = preferences.getBoolean(KEY_PROMPT_BLOCKED, false)
        set(value) = preferences.edit { putBoolean(KEY_PROMPT_BLOCKED, value) }

    private companion object {
        const val FILE = "camera"
        const val KEY_PROMPT_BLOCKED = "prompt_blocked"
    }
}
