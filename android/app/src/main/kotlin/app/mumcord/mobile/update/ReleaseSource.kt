// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The latest published release of the app, as the JSON text GitHub answers with. */
fun interface ReleaseSource {
    /** Blocks until the answer is there; throws [IOException] when there is none. */
    fun latestReleaseJson(): String
}

/** The API address of the latest release: GitHub leaves out drafts and pre-releases there. */
const val LATEST_RELEASE_URL = "https://api.github.com/repos/Mumcord/mumcord-mobile/releases/latest"

/**
 * Asks GitHub for the latest release (FR-5.101). Nothing but the request itself is sent: no
 * identifier of the phone or the user. Redirects are not followed and the answer is cut off at
 * [MAX_ANSWER_BYTES], because it is untrusted.
 */
class GitHubReleaseSource(
    private val url: String = LATEST_RELEASE_URL,
    private val timeoutMillis: Int = TIMEOUT_MILLIS,
) : ReleaseSource {
    override fun latestReleaseJson(): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Mumcord-Mobile")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered $status")
            if (connection.contentLengthLong > MAX_ANSWER_BYTES) throw IOException("answer too large")
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > MAX_ANSWER_BYTES) throw IOException("answer too large")
                    out.write(buffer, 0, read)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** A release answer is a few kilobytes; long release notes stay far below this. */
        const val MAX_ANSWER_BYTES = 256 * 1024
        const val TIMEOUT_MILLIS = 15_000
    }
}
