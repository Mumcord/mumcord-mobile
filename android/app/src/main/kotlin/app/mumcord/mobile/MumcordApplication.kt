// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile

import android.app.Application
import android.util.Log
import app.mumcord.mobile.session.BridgeEngineFactory
import app.mumcord.mobile.session.SessionRepository
import app.mumcord.mobile.ui.connect.AndroidLocalNetworkGateway
import app.mumcord.mobile.ui.connect.LocalNetworkGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns what has to live as long as the process: the engine may exist only once per process and
 * a connection must survive the Activity being recreated (rotation, theme change).
 */
class MumcordApplication : Application() {
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            engineFactory = BridgeEngineFactory(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            engineDispatcher = Dispatchers.IO,
            log = { message -> Log.w(LOG_TAG, message) },
        )
    }

    val localNetworkGateway: LocalNetworkGateway by lazy { AndroidLocalNetworkGateway(this) }

    private companion object {
        const val LOG_TAG = "Mumcord"
    }
}
