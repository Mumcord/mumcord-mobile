// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.ui.themes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.bridge.Theme
import app.mumcord.mobile.bridge.ThemeColors
import app.mumcord.mobile.bridge.ThemeMode
import app.mumcord.mobile.bridge.ThemeVariant
import app.mumcord.mobile.design.components.MumcordAppBar
import app.mumcord.mobile.design.components.MumcordIcon
import app.mumcord.mobile.design.components.MumcordList
import app.mumcord.mobile.design.components.MumcordListRow
import app.mumcord.mobile.design.components.MumcordSectionHeader
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

const val THEMES_PASTE_TEST_TAG = "themes-paste"
const val THEMES_TILE_TEST_TAG_PREFIX = "themes-tile-"
const val THEMES_MODE_TEST_TAG_PREFIX = "themes-mode-"

/** The test tag of a theme's tile, by the theme's id. */
fun themeTileTestTag(themeId: String): String = THEMES_TILE_TEST_TAG_PREFIX + themeId

/** The test tag of an appearance choice. */
fun themeModeTestTag(mode: ThemeMode): String = THEMES_MODE_TEST_TAG_PREFIX + mode.name.lowercase()

/**
 * Themes (mockup `19-themes`, FR-5.81): the appearance (System, Dark, Light), the built-in
 * themes and the user's own ones as tiles, and "Paste theme code". A tap applies a theme; a long
 * press (or the screen reader's actions) offers sharing it and, for the user's own, deleting
 * it. The mockup's "Use server theme when offered" is left out: there is no theme a server
 * offers; a server's own theme is picked in its settings.
 */
@Composable
fun ThemesScreen(state: ThemesUiState, actions: ThemesController, onBack: () -> Unit, topInsets: WindowInsets) {
    // The tiles show the version of each theme that the app shows now.
    val variant = themeVariant(state.mode, isSystemInDarkTheme())
    Column(Modifier.fillMaxSize()) {
        MumcordAppBar(
            title = stringResource(R.string.themes_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.action_back),
            windowInsets = topInsets,
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = MumcordSpace.s4)) {
            MumcordSectionHeader(stringResource(R.string.themes_appearance))
            ModeChoice(state.mode, actions::setMode)
            MumcordSectionHeader(stringResource(R.string.themes_theme))
            ThemeGrid(state.builtIn, state, variant, actions)
            if (state.custom.isNotEmpty()) {
                MumcordSectionHeader(stringResource(R.string.themes_yours))
                ThemeGrid(state.custom, state, variant, actions)
            }
            state.serverTheme?.let { serverTheme ->
                BasicText(
                    text = stringResource(R.string.themes_server_note, state.serverName, serverTheme.name),
                    style = MumcordTheme.typography.small.copy(color = MumcordTheme.colors.textMuted),
                    modifier = Modifier.padding(start = MumcordSpace.s4, end = MumcordSpace.s4, top = MumcordSpace.s3),
                )
            }
            Spacer(Modifier.height(MumcordSpace.s4))
            MumcordList {
                MumcordListRow(
                    title = stringResource(R.string.themes_paste),
                    icon = MumcordIcons.Link,
                    trailing = {
                        MumcordIcon(MumcordIcons.ChevronRight, contentDescription = null, tint = MumcordTheme.colors.textMuted)
                    },
                    onClick = actions::openPaste,
                    modifier = Modifier.testTag(THEMES_PASTE_TEST_TAG),
                )
            }
        }
    }
}

/** System, Dark and Light as one pill track (`.seg` of the mockup). */
@Composable
private fun ModeChoice(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = MumcordTheme.colors
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.themes_mode_system),
        ThemeMode.DARK to stringResource(R.string.themes_mode_dark),
        ThemeMode.LIGHT to stringResource(R.string.themes_mode_light),
    )
    Row(
        modifier = Modifier
            .padding(horizontal = MumcordSpace.s4)
            .fillMaxWidth()
            .background(colors.bgElevated, CircleShape)
            .border(1.dp, colors.border, CircleShape)
            .padding(MumcordSpace.s1)
            .selectableGroup(),
    ) {
        options.forEach { (option, label) ->
            val selected = option == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = MumcordSize.touchVisual)
                    .background(if (selected) colors.accent else Color.Transparent, CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .testTag(themeModeTestTag(option)),
            ) {
                BasicText(
                    text = label,
                    style = MumcordTheme.typography.smallStrong.copy(color = if (selected) colors.onAccent else colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ThemeGrid(themes: List<Theme>, state: ThemesUiState, variant: ThemeVariant, actions: ThemesController) {
    Column(
        verticalArrangement = Arrangement.spacedBy(MumcordSpace.s3),
        modifier = Modifier.padding(horizontal = MumcordSpace.s4),
    ) {
        themes.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MumcordSpace.s3)) {
                row.forEach { theme ->
                    ThemeTile(
                        theme = theme,
                        selected = theme.id == state.appThemeId,
                        variant = variant,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                    )
                }
                // A last row with one tile keeps the tile at half the width.
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val GRID_COLUMNS = 2

/** One theme (`.theme`): a sample of its colours and its name, ringed when it is in use. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeTile(theme: Theme, selected: Boolean, variant: ThemeVariant, actions: ThemesController, modifier: Modifier) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(TILE_RADIUS)
    val sample = if (variant == ThemeVariant.DARK) theme.dark else theme.light
    val description = stringResource(if (selected) R.string.themes_item_selected else R.string.themes_item, theme.name)
    val shareLabel = stringResource(R.string.themes_share)
    val deleteLabel = stringResource(R.string.themes_delete)
    Column(
        modifier = modifier
            .background(if (selected) colors.bgSelected else colors.bgElevated, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border, shape)
            .combinedClickable(
                onClick = { actions.apply(theme.id) },
                onLongClick = { actions.openActions(theme.id) },
                onLongClickLabel = stringResource(R.string.themes_options, theme.name),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                role = Role.RadioButton
                customActions = buildList {
                    add(CustomAccessibilityAction(shareLabel) { actions.share(theme.id); true })
                    if (!theme.builtIn) add(CustomAccessibilityAction(deleteLabel) { actions.requestDelete(theme.id); true })
                }
            }
            .padding(MumcordSpace.s2)
            .testTag(themeTileTestTag(theme.id)),
    ) {
        ThemeSample(sample, Modifier.fillMaxWidth().height(SAMPLE_HEIGHT))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = MumcordSpace.s1, end = MumcordSpace.s1, top = MumcordSpace.s2, bottom = 2.dp),
        ) {
            BasicText(
                text = theme.name,
                style = MumcordTheme.typography.smallStrong.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clearAndSetSemantics { },
            )
            if (selected) MumcordIcon(MumcordIcons.Check, contentDescription = null, tint = colors.accentText, size = MumcordSize.iconSm)
        }
    }
}

/**
 * A theme's colours in small (`.sw`): its background with dots of its accent, text and talking
 * colours. Also the preview of a pasted theme code.
 */
@Composable
fun ThemeSample(sample: ThemeColors, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
            .background(sampleColor(sample.background), RoundedCornerShape(SAMPLE_RADIUS))
            .padding(MumcordSpace.s2),
    ) {
        listOf(sample.accent, sample.text, sample.talking).forEach { dot ->
            Box(Modifier.size(DOT_SIZE).background(sampleColor(dot), CircleShape))
        }
    }
}

/**
 * A key colour of a theme. The engine checks every theme, imported ones included, and only
 * keeps `#rrggbb` values; anything else is drawn transparent instead of stopping the app.
 */
private fun sampleColor(value: String): Color = runCatching { hexColor(value) }.getOrDefault(Color.Transparent)

private val TILE_RADIUS = 16.dp
private val SAMPLE_RADIUS = 10.dp
private val SAMPLE_HEIGHT = 64.dp
private val DOT_SIZE = 20.dp

/** The variant label under each half of a pasted theme's preview. */
@Composable
internal fun SampleLabel(text: String) {
    BasicText(
        text = text,
        style = MumcordTheme.typography.caption.copy(color = MumcordTheme.colors.textMuted, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth(),
    )
}
