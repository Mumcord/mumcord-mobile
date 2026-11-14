// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import android.content.Context
import app.mumcord.mobile.bridge.SecureStorage

/**
 * The one start-up step that has to happen before the engine object is created: loads the
 * native library, enables certificate checking against the system store and provides the secure
 * storage the engine is constructed with.
 */
object EngineBootstrap {
    private var storage: SecureStorage? = null

    /**
     * Safe to call more than once and from any thread; the work is done once per process.
     * Throws when the native library cannot be loaded or initialised.
     */
    @Synchronized
    fun prepare(context: Context): SecureStorage {
        storage?.let { return it }
        val applicationContext = context.applicationContext
        System.loadLibrary("mumcord_mobile")
        SystemCertificates.init(applicationContext)
        return KeystoreSecureStorage(applicationContext).also { storage = it }
    }
}
