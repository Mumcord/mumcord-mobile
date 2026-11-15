// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.crash

import app.mumcord.mobile.bridge.CrashReport
import app.mumcord.mobile.bridge.crashDelete
import app.mumcord.mobile.bridge.crashDeleteAll
import app.mumcord.mobile.bridge.crashList
import app.mumcord.mobile.bridge.crashTakeUnseen
import app.mumcord.mobile.bridge.crashUploadPreview
import app.mumcord.mobile.session.Outcome
import app.mumcord.mobile.session.SessionRepository

/**
 * The crash reports the engine saved on the phone (FR-5.103). The app reaches them only through
 * this, so the tests can pass a fake and no test ever sends a report. Every function except
 * [upload] reads or writes files and blocks; they throw when the reports cannot be reached.
 */
interface CrashSource {
    /** All saved reports, newest first. */
    fun list(): List<CrashReport>

    /** The reports of crashes the user has not been told about; each is returned only once. */
    fun takeUnseen(): List<CrashReport>

    /** Exactly the text [upload] sends for this report and comment. */
    fun preview(id: String, comment: String): String

    fun delete(id: String)

    fun deleteAll()

    /** Sends one report to the Mumcord crash server; false when it could not be sent. */
    suspend fun upload(id: String, comment: String): Boolean

    /** No reports at all: screens and tests that do not show any. */
    object None : CrashSource {
        override fun list(): List<CrashReport> = emptyList()
        override fun takeUnseen(): List<CrashReport> = emptyList()
        override fun preview(id: String, comment: String): String = throw NoSuchElementException("no crash reports")
        override fun delete(id: String) = Unit
        override fun deleteAll() = Unit
        override suspend fun upload(id: String, comment: String) = false
    }
}

/**
 * The reports of the bridge. They are in the folder `MumcordApplication` gave `crashInstall`; the
 * upload runs on the engine, which exists only after `EngineBootstrap` enabled the certificate
 * check against the system's trusted certificates.
 */
class BridgeCrashSource(private val repository: SessionRepository) : CrashSource {
    override fun list(): List<CrashReport> = crashList()

    override fun takeUnseen(): List<CrashReport> = crashTakeUnseen()

    override fun preview(id: String, comment: String): String = crashUploadPreview(id, comment)

    override fun delete(id: String) = crashDelete(id)

    override fun deleteAll() = crashDeleteAll()

    override suspend fun upload(id: String, comment: String): Boolean =
        repository.call("crash upload") { it.crashUpload(id, comment) } is Outcome.Ok
}
