// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile

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
}
