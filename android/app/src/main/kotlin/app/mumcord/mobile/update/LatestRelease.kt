// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/**
 * A newer release the notice offers. Its addresses are built from the version, never taken from
 * the answer of GitHub: only the release page and the APK of this repository can be opened.
 *
 * @property hasApk the release has the signed APK attached; otherwise the notice opens its page.
 */
data class AvailableUpdate(val version: ReleaseVersion, val hasApk: Boolean) {
    val pageUrl: String get() = "$REPOSITORY_URL/releases/tag/${version.tag}"

    /** The signed APK as the release workflow names it (`mumcord-mobile-<version>-arm64.apk`). */
    val apkName: String get() = "mumcord-mobile-$version-arm64.apk"

    val apkUrl: String get() = "$REPOSITORY_URL/releases/download/${version.tag}/$apkName"

    /** What "Download" opens in the browser (FR-5.101). */
    val downloadUrl: String get() = if (hasApk) apkUrl else pageUrl
}

/** The answer of GitHub is not a release this app can offer. */
class InvalidReleaseException(message: String) : Exception(message)

/**
 * Reads the answer of `GET /repos/Mumcord/mumcord-mobile/releases/latest`. The answer is untrusted:
 * it is read as strict JSON, only the few fields used are looked at, and the release must be a
 * published, final release of this repository with a `vX.Y.Z` tag and the expected page address.
 * The APK counts only with exactly the name and address the release workflow gives it.
 */
fun parseLatestRelease(json: String): AvailableUpdate {
    var tag: String? = null
    var draft: Boolean? = null
    var prerelease: Boolean? = null
    var page: String? = null
    var assets: List<Pair<String?, String?>> = emptyList()
    try {
        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "tag_name" -> tag = reader.stringOrNull()
                    "draft" -> draft = reader.booleanOrNull()
                    "prerelease" -> prerelease = reader.booleanOrNull()
                    "html_url" -> page = reader.stringOrNull()
                    "assets" -> assets = reader.assets()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw InvalidReleaseException("data after the release")
        }
    } catch (e: InvalidReleaseException) {
        throw e
    } catch (e: Exception) {
        // IOException and IllegalStateException of the reader: not valid JSON of the expected shape.
        throw InvalidReleaseException("not a release: ${e.javaClass.simpleName}")
    }
    if (draft != false || prerelease != false) throw InvalidReleaseException("not a published final release")
    val version = tag?.let(ReleaseVersion::fromTag) ?: throw InvalidReleaseException("no vX.Y.Z tag")
    val update = AvailableUpdate(version, hasApk = false)
    if (page != update.pageUrl) throw InvalidReleaseException("unexpected release page")
    val hasApk = assets.any { (name, url) -> name == update.apkName && url == update.apkUrl }
    return update.copy(hasApk = hasApk)
}

private fun JsonReader.stringOrNull(): String? =
    if (peek() == JsonToken.STRING) nextString() else null.also { skipValue() }

private fun JsonReader.booleanOrNull(): Boolean? =
    if (peek() == JsonToken.BOOLEAN) nextBoolean() else null.also { skipValue() }

/** The name and download address of every attached file. */
private fun JsonReader.assets(): List<Pair<String?, String?>> {
    if (peek() != JsonToken.BEGIN_ARRAY) return emptyList<Pair<String?, String?>>().also { skipValue() }
    val assets = mutableListOf<Pair<String?, String?>>()
    beginArray()
    while (hasNext()) {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            continue
        }
        var name: String? = null
        var url: String? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "name" -> name = stringOrNull()
                "browser_download_url" -> url = stringOrNull()
                else -> skipValue()
            }
        }
        endObject()
        assets += name to url
    }
    endArray()
    return assets
}
