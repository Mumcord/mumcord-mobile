// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.components.MumcordBanner
import app.mumcord.mobile.design.components.MumcordBannerTone
import app.mumcord.mobile.design.components.MumcordButton
import app.mumcord.mobile.design.components.MumcordButtonStyle
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.session.CertificatePromptState
import app.mumcord.mobile.ui.common.SheetOverlay

const val FINGERPRINT_TEST_TAG = "certificate-fingerprint"
const val CERTIFICATE_TRUST_TEST_TAG = "certificate-trust"
const val CERTIFICATE_REJECT_TEST_TAG = "certificate-reject"

/**
 * Asks whether to trust the server's certificate: a calm question for a server seen for the
 * first time, a strong warning when a trusted server presents another certificate. It cannot be
 * dismissed by tapping outside or going back: trusting or not is a security decision the user
 * has to make on purpose.
 */
@Composable
fun CertificateSheet(
    prompt: CertificatePromptState,
    onTrust: () -> Unit,
    onReject: () -> Unit,
    topInsets: WindowInsets,
    bottomInsets: WindowInsets,
) {
    val previous = prompt.previousFingerprint
    SheetOverlay(
        title = stringResource(if (previous == null) R.string.certificate_unknown_title else R.string.certificate_changed_title),
        onDismiss = null,
        topInsets = topInsets,
        bottomInsets = bottomInsets,
    ) {
        if (previous == null) {
            MumcordBanner(
                text = stringResource(R.string.certificate_unknown_message),
                icon = MumcordIcons.Shield,
                horizontalMargin = 0.dp,
            )
            Fingerprint(stringResource(R.string.certificate_fingerprint_label), prompt.fingerprint)
            Buttons {
                MumcordButton(
                    text = stringResource(R.string.certificate_unknown_trust),
                    onClick = onTrust,
                    modifier = Modifier.fillMaxWidth().testTag(CERTIFICATE_TRUST_TEST_TAG),
                )
                MumcordButton(
                    text = stringResource(R.string.certificate_reject),
                    onClick = onReject,
                    style = MumcordButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth().testTag(CERTIFICATE_REJECT_TEST_TAG),
                )
            }
        } else {
            MumcordBanner(
                text = stringResource(R.string.certificate_changed_message),
                icon = MumcordIcons.Alert,
                tone = MumcordBannerTone.Danger,
                horizontalMargin = 0.dp,
            )
            Fingerprint(stringResource(R.string.certificate_changed_previous_label), previous)
            Fingerprint(stringResource(R.string.certificate_changed_new_label), prompt.fingerprint)
            // Not connecting is the safe answer and therefore the prominent one.
            Buttons {
                MumcordButton(
                    text = stringResource(R.string.certificate_reject),
                    onClick = onReject,
                    modifier = Modifier.fillMaxWidth().testTag(CERTIFICATE_REJECT_TEST_TAG),
                )
                MumcordButton(
                    text = stringResource(R.string.certificate_changed_trust),
                    onClick = onTrust,
                    style = MumcordButtonStyle.Danger,
                    modifier = Modifier.fillMaxWidth().testTag(CERTIFICATE_TRUST_TEST_TAG),
                )
            }
        }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MumcordSpace.s2)) { content() }
}

@Composable
private fun Fingerprint(label: String, fingerprint: String) {
    val colors = MumcordTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(text = label, style = MumcordTheme.typography.smallStrong.copy(color = colors.textSecondary))
        BasicText(
            text = fingerprint,
            style = MumcordTheme.typography.small.copy(color = colors.text, fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgHover, RoundedCornerShape(MumcordRadius.lg))
                .padding(MumcordSpace.s3)
                .testTag(FINGERPRINT_TEST_TAG),
        )
    }
}
