// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import android.content.Context
import app.mumcord.mobile.bridge.EngineConfig
import app.mumcord.mobile.bridge.EngineListener
import app.mumcord.mobile.bridge.MumcordEngine
import app.mumcord.mobile.bridge.MumcordEngineInterface
import app.mumcord.mobile.platform.securestore.EngineBootstrap
import java.io.File

/** Creates the engine. A seam so tests can run the repository against a fake engine. */
fun interface EngineFactory {
    /** Reads the database and the secure storage and may throw: call it off the main thread. */
    fun create(listener: EngineListener): MumcordEngineInterface
}

/** The real engine, with its data in a folder of its own inside the app's private files. */
class BridgeEngineFactory(context: Context) : EngineFactory {
    private val applicationContext = context.applicationContext

    override fun create(listener: EngineListener): MumcordEngineInterface {
        val storage = EngineBootstrap.prepare(applicationContext)
        val dataDir = File(applicationContext.filesDir, "engine")
        return MumcordEngine(EngineConfig(dataDir = dataDir.path), storage, listener)
    }
}
