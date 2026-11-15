// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.util.Base64
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.mumcord.mobile.MainActivity
import app.mumcord.mobile.TestSettings
import app.mumcord.mobile.ui.camera.CAMERA_CONTINUE_TEST_TAG
import app.mumcord.mobile.ui.connected.MORE_IDENTITIES_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_CARD_TEST_TAG
import app.mumcord.mobile.ui.identities.IDENTITY_SCAN_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_CODE_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_DONE_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_IMPORT_TEST_TAG
import app.mumcord.mobile.ui.identities.SCAN_SUMMARY_TEST_TAG
import app.mumcord.mobile.ui.servers.SERVER_CONNECT_TEST_TAG
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * "Scan from desktop app" (FR-5.65, FR-5.66, AC-5.21) in the real app with the real engine: a
 * transfer made on the test machine by the engine's own code, as the desktop app makes it
 * (e2e/transfer-maker, a new identity and a new code in every run), is scanned, opened with the
 * typed code and imported; the identity and the server it brought then connect to the test
 * server. The emulator's camera cannot be shown a QR code, so the texts of the codes reach the
 * scanner through the debug build's test source instead of the camera; everything after the
 * camera is the real thing. Expects fresh app data and no camera permission (the scenario
 * "transfer" of e2e/run.sh).
 */
class TransferFlowTest {
    @get:Rule(order = 0)
    val requireServer = RequireTestServer()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app by lazy { AppDriver(compose, requireServer.server) }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val system = SystemDialog(UiDevice.getInstance(instrumentation))
    private val camera = MutableSharedFlow<String>(extraBufferCapacity = 256)

    /** The transfer of this run: the code, the QR code texts, and one text of an expired transfer. */
    private class MadeTransfer(val code: String, val parts: List<String>, val expiredPart: String)

    private fun madeTransfer(): MadeTransfer? {
        val encoded = InstrumentationRegistry.getArguments().getString(TRANSFER_ARGUMENT) ?: return null
        val lines = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        val split = lines.indexOf(EXPIRED_MARK)
        check(split >= 2 && split == lines.size - 2) { "The transfer of e2e/run.sh has another shape than expected" }
        return MadeTransfer(lines.first(), lines.subList(1, split), lines.last())
    }

    @Before
    fun scanFromTheTest() {
        TestSettings.qrTexts = camera
    }

    @After
    fun scanFromTheCameraAgain() {
        TestSettings.qrTexts = null
    }

    /** Shows the scanner [texts] again and again, as a camera sees a code, until [done]. */
    private fun show(texts: List<String>, what: String, done: () -> Boolean) = app.await(what) {
        texts.forEach { camera.tryEmit(it) }
        done()
    }

    private fun described(description: String): Boolean =
        compose.onAllNodes(hasContentDescription(description, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun text(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTransferFromTheDesktopIsScannedOpenedWithItsCodeAndImportedAndWrongAndOldCodesAreRefused() {
        val transfer = madeTransfer()
        assumeNotNull("Started without a transfer; e2e/run.sh makes one", transfer)
        transfer!!
        val total = transfer.parts.size
        val wrongCode = transfer.code.map { if (it == '9') '0' else it + 1 }.joinToString("")

        // First start: the identity comes from the desktop instead of being created.
        app.awaitOrFail("the identity step of the first start") { app.exists(IDENTITY_SCAN_TEST_TAG) }
        compose.onNodeWithTag(IDENTITY_SCAN_TEST_TAG).performClick()

        // The camera is explained in context, then the system asks.
        app.awaitOrFail("the explanation of the camera") { app.exists(CAMERA_CONTINUE_TEST_TAG) }
        compose.onNodeWithTag(CAMERA_CONTINUE_TEST_TAG).performClick()
        system.answer(SystemDialog.ALLOW_WHILE_IN_USE, PERMISSION)
        app.await("the scanner waiting for its first code") { described("Waiting for the first code") }

        // AC-5.21: a code that is older than two minutes is not accepted.
        show(listOf(transfer.expiredPart), "the notice about codes that are too old") {
            text("older than two minutes")
        }

        // The codes are collected one by one, in any order.
        show(listOf(transfer.parts.last()), "the first code to count") { described("Code 1 of $total") }
        show(transfer.parts.reversed(), "the code field after the last code") { app.exists(SCAN_CODE_TEST_TAG) }

        // AC-5.21: a wrong code is refused three times, then the codes have to be scanned again.
        for (left in listOf(2, 1)) {
            compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
            app.await("the refusal of a wrong code with $left left") { text("Wrong code. $left tr") }
        }
        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(wrongCode)
        app.await("the scan to start over after the third wrong code") {
            text("The code was wrong three times.") && !app.exists(SCAN_CODE_TEST_TAG)
        }

        show(transfer.parts, "the code field after scanning again") { app.exists(SCAN_CODE_TEST_TAG) }
        compose.onNodeWithTag(SCAN_CODE_TEST_TAG).performTextInput(transfer.code)

        // What arrives is listed first; nothing is imported before the user says so.
        app.await("the list of what the transfer holds") { app.exists(SCAN_SUMMARY_TEST_TAG) }
        check(text("1 identity, 1 server")) { "The transfer does not list one identity and one server" }
        check(text(IDENTITY_LABEL) && text(SERVER_NAME)) { "The identity or the server of the transfer is not named" }
        compose.onNodeWithTag(SCAN_IMPORT_TEST_TAG).performClick()
        app.await("the summary of the import") { app.exists(SCAN_DONE_TEST_TAG) }
        compose.onNodeWithTag(SCAN_DONE_TEST_TAG).performClick()

        // The identity is there, so the first start goes on with the talk mode, and the server
        // of the transfer is in the list. It has no password and connects with the identity.
        app.chooseTalkMode()
        app.awaitOrFail("the transferred server in the list") { text(SERVER_NAME) && app.exists(SERVER_CONNECT_TEST_TAG) }
        app.tapConnect()
        app.trustCertificate()
        app.awaitChannels()

        compose.onNodeWithText(TAB_MORE).performClick()
        app.awaitOrFail("the More tab") { app.exists(MORE_IDENTITIES_TEST_TAG) }
        compose.onNodeWithTag(MORE_IDENTITIES_TEST_TAG).performClick()
        app.awaitOrFail("the identity list") { app.exists(IDENTITY_CARD_TEST_TAG) }
        check(described("$IDENTITY_LABEL (default), Used on 1 server")) {
            "The transferred identity is not the default one of the transferred server"
        }
        compose.onNode(hasContentDescription(BACK)).performClick()
        app.awaitOrFail("the More tab after the identity list") { app.exists(MORE_IDENTITIES_TEST_TAG) }
        app.disconnect()
    }

    private companion object {
        const val TRANSFER_ARGUMENT = "transfer"
        const val EXPIRED_MARK = "EXPIRED"
        const val PERMISSION = "the camera"

        // What e2e/transfer-maker puts into the transfer.
        const val IDENTITY_LABEL = "e2e-desktop"
        const val SERVER_NAME = "e2e-transferred"
        const val TAB_MORE = "More"
        const val BACK = "Back"
    }
}
