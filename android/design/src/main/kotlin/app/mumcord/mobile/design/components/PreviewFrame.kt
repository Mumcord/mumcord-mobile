// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSpace

/** The frame the component previews are shown in: one theme on the screen background colour. */
@Composable
internal fun PreviewFrame(darkTheme: Boolean, content: @Composable ColumnScope.() -> Unit) {
    MumcordTheme(darkTheme = darkTheme) {
        Column(
            verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
            modifier = Modifier.background(MumcordTheme.colors.bgSidebar).padding(vertical = MumcordSpace.s3),
            content = content,
        )
    }
}
