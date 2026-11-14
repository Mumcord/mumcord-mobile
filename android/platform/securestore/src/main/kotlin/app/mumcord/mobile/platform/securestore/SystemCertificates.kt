// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import android.content.Context

/**
 * Hands the app context to the native library, which needs it to check server certificates
 * against the system certificate store. Implemented by the bridge (`bridge/src/android.rs`);
 * the native library must be loaded first. Use [EngineBootstrap] instead of calling this.
 */
object SystemCertificates {
    /** Throws a [RuntimeException] when the native side cannot take the context. */
    external fun init(context: Context)
}
