// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile

import app.mumcord.mobile.session.IdleTimes
import kotlinx.coroutines.flow.Flow

/**
 * What only the automated tests may change, in debug builds; the product has the same object
 * with fixed values. The end-to-end tests run inside the app and set these directly; nothing
 * is stored, so every start of the app begins with the product's behaviour.
 */
object TestSettings {
    /**
     * The emulator cannot plug in a headset, so this makes the current output count as one for
     * the headset's button (FR-5.35); see `endRequestMeans`.
     */
    @Volatile
    var treatOutputAsHeadset: Boolean = false

    /**
     * How long "disconnect when alone" waits (FR-5.42, AC-5.30): 30 minutes and one minute of
     * warning unless a test shortens them. Read whenever a wait begins.
     */
    @Volatile
    var idleTimes: IdleTimes = IdleTimes()

    /**
     * The emulator's camera cannot be shown a QR code: when set, the scanner reads these texts
     * instead of opening the camera.
     */
    @Volatile
    var qrTexts: Flow<String>? = null

    /**
     * A ban also covers the address, which every person of the local test server shares (the
     * host): when set, a ban lasts this many seconds instead of the chosen length, so the
     * end-to-end test can wait for its end.
     */
    @Volatile
    var banSeconds: UInt? = null
}
