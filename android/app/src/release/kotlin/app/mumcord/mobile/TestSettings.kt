// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile

/**
 * What only the automated tests may change. This is the product's side of it: nothing can be
 * changed. The debug build has the same object with values a test sets.
 */
object TestSettings {
    /** See `endRequestMeans`: never in the product. */
    const val treatOutputAsHeadset: Boolean = false
}
