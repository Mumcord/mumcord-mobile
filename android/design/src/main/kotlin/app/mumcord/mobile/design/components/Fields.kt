// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace

/**
 * Single-line text field of a form (`.label` over `.field`): the [label] above, the field with
 * a focus ring while it is being edited, and below it the [error] in the danger colour or else
 * the [hint]. TalkBack reads the label and the error with the field. [password] hides the
 * typed characters.
 */
@Composable
fun MumcordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    password: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MumcordTheme.colors
    val shape = RoundedCornerShape(MumcordRadius.lg)
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        error != null -> colors.danger
        focused -> colors.focus
        else -> colors.border
    }
    val ringColor = colors.focus.copy(alpha = MumcordTheme.opacities.focusRing)
    // The 2 dp ring around the focused field, as the CSS box shadow of `.field.is-focus`. It is
    // drawn outside the field so that focusing does not move anything.
    val ring = if (focused) {
        Modifier.drawBehind {
            val width = 2.dp.toPx()
            val radius = MumcordRadius.lg.toPx() + width / 2
            drawRoundRect(
                color = ringColor,
                topLeft = Offset(-width / 2, -width / 2),
                size = Size(size.width + width, size.height + width),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width),
            )
        }
    } else {
        Modifier
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
        // Read as part of the field below, not as a separate element.
        LineText(label, MumcordTheme.typography.smallStrong, colors.textSecondary, Modifier.clearAndSetSemantics {})
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MumcordTheme.typography.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    if (error != null) error(error)
                },
            decorationBox = { field ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .then(ring)
                        .defaultMinSize(minHeight = MumcordSize.touchVisual)
                        .background(colors.bgElevated, shape)
                        .border(1.dp, borderColor, shape)
                        .padding(horizontal = MumcordSpace.s4),
                ) {
                    if (value.isEmpty()) LineText(placeholder, MumcordTheme.typography.body, colors.textMuted)
                    field()
                }
            },
        )
        val below = error ?: hint
        if (below != null) {
            BasicText(
                text = below,
                style = MumcordTheme.typography.small.copy(color = if (error != null) colors.danger else colors.textMuted),
                // The error is already read with the field.
                modifier = if (error != null) Modifier.clearAndSetSemantics {} else Modifier,
            )
        }
    }
}

@Preview(name = "Text field dark")
@Composable
private fun FieldsDarkPreview() {
    PreviewFrame(darkTheme = true) { FieldsSample() }
}

@Preview(name = "Text field light")
@Composable
private fun FieldsLightPreview() {
    PreviewFrame(darkTheme = false) { FieldsSample() }
}

@Composable
internal fun FieldsSample() {
    val padded = Modifier.padding(horizontal = MumcordSpace.s4)
    MumcordTextField(value = "nordlicht.example", onValueChange = {}, label = "Address", modifier = padded)
    MumcordTextField(value = "", onValueChange = {}, label = "Name", placeholder = "Optional", hint = "Shown in your server list.", modifier = padded)
    MumcordTextField(value = "99999", onValueChange = {}, label = "Port", error = "Enter a number between 1 and 65535.", modifier = padded)
    MumcordTextField(value = "secret", onValueChange = {}, label = "Server password", password = true, modifier = padded)
}
