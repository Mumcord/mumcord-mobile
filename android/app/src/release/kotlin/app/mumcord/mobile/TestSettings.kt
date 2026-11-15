// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile

import app.mumcord.mobile.session.IdleTimes
import kotlinx.coroutines.flow.Flow

/**
 * What only the automated tests may change. This is the product's side of it: nothing can be
 * changed. The debug build has the same object with values a test sets.
 */
object TestSettings {
    /** See `endRequestMeans`: never in the product. */
    const val treatOutputAsHeadset: Boolean = false

    /** "Disconnect when alone" always takes its 30 minutes and its one minute of warning. */
    val idleTimes: IdleTimes = IdleTimes()

    /** The scanner always reads from the camera. */
    val qrTexts: Flow<String>? = null
}
