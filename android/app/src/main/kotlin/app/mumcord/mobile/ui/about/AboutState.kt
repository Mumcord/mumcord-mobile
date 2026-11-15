// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.about

import android.content.res.AssetManager
import app.mumcord.mobile.update.AppInfo
import app.mumcord.mobile.update.AvailableUpdate

/** The third-party notices of the build (`THIRD_PARTY_NOTICES.txt` in the APK's assets). */
fun interface NoticesSource {
    /** Blocks while the text is read. */
    fun read(): String

    companion object {
        const val ASSET = "THIRD_PARTY_NOTICES.txt"

        fun assets(assets: AssetManager): NoticesSource =
            NoticesSource { assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    }
}

/** The licences screen: read once when it is first opened. */
sealed interface Licences {
    data object NotRead : Licences
    data object Loading : Licences
    data object Failed : Licences

    /** The notices in blocks between empty lines, so that the list draws only what is visible. */
    data class Loaded(val blocks: List<NoticeBlock>) : Licences
}

/** One block of the notices; a [heading] is a title the file set between rule lines. */
data class NoticeBlock(val text: String, val heading: Boolean = false)

private val RULE = Regex("[-=]{10,}")

/**
 * Splits the notices at empty lines into blocks. The rule lines of the plain-text file (80
 * dashes, which would wrap on a phone) are left out; a block that had them is a heading.
 */
fun noticeBlocks(text: String): List<NoticeBlock> =
    text.replace("\r\n", "\n").split(Regex("\n[^\\S\n]*\n+")).mapNotNull { block ->
        val lines = block.trimEnd().lines()
        val content = lines.filterNot { RULE.matches(it.trim()) }
        content.joinToString("\n").takeIf { it.isNotBlank() }?.let { NoticeBlock(it, heading = content.size < lines.size) }
    }

/** What the About screen and the update notices show besides the More tab's own state. */
data class AboutLocal(
    val info: AppInfo,
    /** The newer release to offer, or null; see [app.mumcord.mobile.update.updateNotice]. */
    val update: AvailableUpdate? = null,
    val licences: Licences = Licences.NotRead,
)

/** The About screen (no mockup). */
data class AboutUiState(
    val appVersion: String,
    val engineVersion: String,
    val sourceUrl: String,
    val update: AvailableUpdate?,
)

fun aboutUiState(local: AboutLocal): AboutUiState = AboutUiState(
    appVersion = local.info.appVersion,
    engineVersion = local.info.engineVersion,
    sourceUrl = local.info.sourceUrl,
    update = local.update,
)
