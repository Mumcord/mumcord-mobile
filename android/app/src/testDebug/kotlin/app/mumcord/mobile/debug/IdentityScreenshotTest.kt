// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.mumcord.mobile.R
import app.mumcord.mobile.design.theme.MumcordTheme
import app.mumcord.mobile.session.ConnectionError
import app.mumcord.mobile.ui.camera.CameraSheet
import app.mumcord.mobile.ui.identities.CameraNotice
import app.mumcord.mobile.ui.identities.IdentitiesScreen
import app.mumcord.mobile.ui.identities.IdentitiesUiState
import app.mumcord.mobile.ui.identities.IdentityDetails
import app.mumcord.mobile.ui.identities.IdentityRow
import app.mumcord.mobile.ui.identities.IdentitySetupScreen
import app.mumcord.mobile.ui.identities.IdentitySheet
import app.mumcord.mobile.ui.identities.IdentitySheetActions
import app.mumcord.mobile.ui.identities.IdentitySheetHost
import app.mumcord.mobile.ui.identities.IdentitySheetLocal
import app.mumcord.mobile.ui.identities.ImportProblem
import app.mumcord.mobile.ui.identities.ScanNote
import app.mumcord.mobile.ui.identities.ScanScreen
import app.mumcord.mobile.ui.identities.ScanState
import app.mumcord.mobile.ui.identities.ScanStep
import app.mumcord.mobile.ui.identities.SkippedEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the identity screens and the scan of a transfer in both themes at the size of the
 * mockups, with the mockups' sample content where a mockup exists (`17-identity`,
 * `18-qr-scan`). The camera picture is left out: the card shows what is behind it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class IdentityScreenshotTest(private val theme: String) {
    @get:Rule
    val composeRule = createComposeRule()

    // The status bar and home indicator areas of the mockup's phone.
    private val top = WindowInsets(top = 47.dp)
    private val bottom = WindowInsets(bottom = 34.dp)

    private val lea = IdentityRow(1, "Lea", isDefault = true, serverCount = 4, fingerprintStart = "4F:A9:1C:7E:D2:08:B3:6A")
    private val work = IdentityRow(2, "Work", isDefault = false, serverCount = 0, fingerprintStart = "B1:07:5E:22:9C:D4:60:3F")

    private fun details(row: IdentityRow, canDelete: Boolean = true, nextDefault: String? = null) = IdentityDetails(
        id = row.id,
        label = row.label,
        commonName = row.label,
        fingerprint = "4FA9 1C7E D208 B36A 0C55 91E3 77AB 2D40 6F18 C3E9",
        // 14 November 2026, the day of the mockups.
        createdMs = 1_794_614_400_000,
        isDefault = row.isDefault,
        serverCount = row.serverCount,
        canDelete = canDelete,
        nextDefaultLabel = nextDefault,
    )

    private object NoActions : IdentitySheetActions {
        override fun closeSheet() = Unit
        override fun setDefault(identityId: Long) = Unit
        override fun openExport(identityId: Long) = Unit
        override fun openDelete(identityId: Long) = Unit
        override fun changeCreateName(name: String) = Unit
        override fun create() = Unit
        override fun changeImportPassword(password: String) = Unit
        override fun confirmImport() = Unit
        override fun changeExportPassword(password: String) = Unit
        override fun changeExportRepeat(repeat: String) = Unit
        override fun export() = Unit
        override fun changeDeleteConfirmation(typed: String) = Unit
        override fun delete() = Unit
    }

    @Composable
    private fun Identities(rows: List<IdentityRow> = listOf(lea), unreadableFile: Boolean = false) {
        IdentitiesScreen(
            state = IdentitiesUiState(open = true, known = true, rows = rows, unreadableFile = unreadableFile),
            onBack = {},
            onDetails = {},
            onScan = {},
            onImport = {},
            onCreate = {},
            topInsets = top,
            bottomInsets = bottom,
        )
    }

    @Composable
    private fun Sheet(sheet: IdentitySheet, rows: List<IdentityRow> = listOf(lea, work)) {
        Box {
            Identities(rows)
            IdentitySheetHost(sheet, NoActions, top, bottom)
        }
    }

    @Composable
    private fun Scan(state: ScanState) {
        ScanScreen(
            state = state,
            viewfinder = {},
            onClose = {},
            onAllowCamera = {},
            onOpenSettings = {},
            onCode = {},
            onImport = {},
            topInsets = top,
            bottomInsets = bottom,
        )
    }

    private fun capture(name: String, content: @Composable () -> Unit) =
        composeRule.captureScreenshot("android-identities-$name-$theme") {
            MumcordTheme(darkTheme = theme == "dark") { content() }
        }

    // Mockup `17-identity`.
    @Test fun list() = capture("list") { Identities() }

    // No mockup from here on, except where said.
    @Test fun listWithTwoAndAnUnreadableFile() = capture("list-file-unreadable") {
        Identities(listOf(lea, work), unreadableFile = true)
    }

    @Test fun firstStart() = capture("first-start") {
        IdentitySetupScreen(unreadableFile = false, onScan = {}, onImport = {}, onCreate = {}, topInsets = top, bottomInsets = bottom)
    }

    @Test fun detailsOfTheDefault() = capture("details-default") {
        Sheet(IdentitySheet.Details(details(lea, canDelete = false)), rows = listOf(lea))
    }

    @Test fun detailsOfAnother() = capture("details") { Sheet(IdentitySheet.Details(details(work))) }

    @Test fun create() = capture("create") { Sheet(IdentitySheet.Create(IdentitySheetLocal.Create(name = "Lea"))) }

    @Test fun importPassword() = capture("import-password") { Sheet(IdentitySheet.Import(IdentitySheetLocal.Import())) }

    @Test fun importWrongPassword() = capture("import-wrong-password") {
        Sheet(IdentitySheet.Import(IdentitySheetLocal.Import(problem = ImportProblem.WrongPasswordOrFile)))
    }

    @Test fun export() = capture("export") {
        Sheet(IdentitySheet.Export(details(lea), IdentitySheetLocal.Export(1, password = "x".repeat(12), repeat = "x".repeat(11))))
    }

    @Test fun delete() = capture("delete") {
        Sheet(
            IdentitySheet.Delete(
                details(lea, nextDefault = "Work"),
                IdentitySheetLocal.Delete(1, typed = "Le"),
                defaultLabel = "Lea",
            ),
        )
    }

    // Mockup `18-qr-scan`, the part of it that exists while the codes are collected.
    @Test fun scan() = capture("scan") { Scan(ScanState(ScanStep.Scanning(3, 7))) }

    @Test fun scanWaiting() = capture("scan-waiting") { Scan(ScanState()) }

    @Test fun scanCameraExplain() = capture("scan-camera-explain") {
        Box {
            Scan(ScanState(camera = CameraNotice.Explain))
            CameraSheet(
                reason = RuntimeEnvironment.getApplication().getString(R.string.camera_reason_scan),
                blocked = false,
                onContinue = {},
                onOpenSettings = {},
                onDismiss = {},
                topInsets = top,
                bottomInsets = bottom,
            )
        }
    }

    @Test fun scanCameraDenied() = capture("scan-camera-denied") { Scan(ScanState(camera = CameraNotice.Denied)) }

    @Test fun scanCameraBlocked() = capture("scan-camera-blocked") { Scan(ScanState(camera = CameraNotice.Blocked)) }

    @Test fun scanForeignCode() = capture("scan-foreign-code") {
        Scan(ScanState(ScanStep.Scanning(3, 7), note = ScanNote.NotATransfer))
    }

    @Test fun scanExpired() = capture("scan-expired") { Scan(ScanState(note = ScanNote.Expired)) }

    // Mockup `18-qr-scan`, the code entry: eight boxes in two groups instead of six.
    @Test fun scanCode() = capture("scan-code") {
        Scan(ScanState(ScanStep.Code(digits = 8, typed = "482", attemptsLeft = 3)))
    }

    @Test fun scanWrongCode() = capture("scan-wrong-code") {
        Scan(ScanState(ScanStep.Code(digits = 8, attemptsLeft = 2, wrong = true)))
    }

    @Test fun scanChecking() = capture("scan-checking") { Scan(ScanState(ScanStep.Checking(8, 3))) }

    @Test fun scanRescan() = capture("scan-rescan") { Scan(ScanState(note = ScanNote.RescanRequired)) }

    // Mockup `18-qr-scan`, the "Receiving" line: it exists once the code was right.
    @Test fun scanPreview() = capture("scan-preview") {
        Scan(
            ScanState(
                ScanStep.Preview(
                    identities = listOf("Lea"),
                    servers = listOf("Nordlicht", "Tidepool", "Lantern Guild", "Quiet Harbor"),
                ),
            ),
        )
    }

    @Test fun scanImportFailed() = capture("scan-import-failed") {
        Scan(ScanState(ScanStep.Preview(listOf("Lea"), servers = null, error = ConnectionError.SecureStorageUnavailable)))
    }

    @Test fun scanDone() = capture("scan-done") {
        Scan(
            ScanState(
                ScanStep.Done(
                    identitiesImported = 1,
                    serversImported = 3,
                    skipped = listOf(SkippedEntry("Nordlicht", alreadyThere = true)),
                ),
            ),
        )
    }

    companion object {
        /** Every screen is drawn once per theme. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<String> = listOf("dark", "light")
    }
}
