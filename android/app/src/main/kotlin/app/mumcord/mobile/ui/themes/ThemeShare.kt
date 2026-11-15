// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mumcord.mobile.R

/**
 * Hands a theme's share message (`🎨 Mumcord theme "…" – mct1.…`) to the system's share sheet,
 * for a chat, a messenger or the clipboard.
 */
fun shareThemeText(context: Context, message: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.themes_share))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Log.w("MumcordThemes", "no app can take a shared theme", e)
    }
}
