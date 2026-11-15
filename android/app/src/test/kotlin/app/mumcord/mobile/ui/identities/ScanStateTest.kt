// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.bridge.TransferException
import app.mumcord.mobile.bridge.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStateTest {
    private fun standing(have: Int, total: Int, digits: Int = 8, attempts: Int = 3) =
        ScanStanding(TransferProgress(have.toUByte(), total.toUByte()), digits, attempts)

    private val nothing = ScanStanding(progress = null, codeDigits = 8, attemptsLeft = 3)
    private val scanning = ScanState(ScanStep.Scanning(3, 7))
    private val typing = ScanState(ScanStep.Code(digits = 8, attemptsLeft = 3))

    @Test
    fun everyNewCodeMovesTheProgressOn() {
        val state = ScanState().afterPart(standing(3, 7))
        assertEquals(ScanStep.Scanning(3, 7), state.step)
        assertEquals(43, (state.step as ScanStep.Scanning).percent())
        assertEquals(0, ScanStep.Scanning().percent())
        assertEquals(100, ScanStep.Scanning(7, 7).percent())
    }

    @Test
    fun afterTheLastCodeTheCodeOfTheDesktopIsAskedFor() {
        val state = scanning.copy(note = ScanNote.Unreadable).afterPart(standing(7, 7, digits = 8))
        assertEquals(ScanStep.Code(digits = 8, attemptsLeft = 3), state.step)
        assertNull(state.note)
        // The transfer says how long its code is.
        assertEquals(6, (ScanState().afterPart(standing(1, 1, digits = 6)).step as ScanStep.Code).digits)
    }

    @Test
    fun aForeignOrBadlyReadCodeKeepsWhatWasCollected() {
        assertEquals(scanning.copy(note = ScanNote.NotATransfer), scanning.afterPartRefused(TransferException.NotATransfer()))
        assertEquals(scanning.copy(note = ScanNote.Unreadable), scanning.afterPartRefused(TransferException.BadChecksum()))
        assertEquals(
            scanning.copy(note = ScanNote.NewerVersion),
            scanning.afterPartRefused(TransferException.UnsupportedVersion()),
        )
        // The next good code takes the note away again.
        assertNull(scanning.copy(note = ScanNote.NotATransfer).afterPart(standing(4, 7)).note)
    }

    @Test
    fun anExpiredTransferStartsTheScanOver() {
        assertEquals(ScanState(ScanStep.Scanning(), note = ScanNote.Expired), scanning.afterPartRefused(TransferException.Expired()))
        assertEquals(
            ScanState(ScanStep.Scanning(), note = ScanNote.ClockSkew),
            scanning.afterPartRefused(TransferException.ClockSkew()),
        )
        assertEquals(
            ScanState(ScanStep.Scanning(), note = ScanNote.Expired),
            typing.afterCodeRefused(TransferException.Expired(), nothing),
        )
    }

    @Test
    fun aWrongCodeCanBeTypedAgainWithTheTriesThatAreLeft() {
        val once = typing.afterCodeRefused(TransferException.WrongCode(2u), standing(7, 7, attempts = 2))
        assertEquals(ScanStep.Code(digits = 8, typed = "", attemptsLeft = 2, wrong = true), once.step)
        val twice = once.afterCodeRefused(TransferException.WrongCode(1u), standing(7, 7, attempts = 1))
        assertEquals(1, (twice.step as ScanStep.Code).attemptsLeft)
    }

    @Test
    fun theThirdWrongCodeMeansScanningAgain() {
        val state = typing.afterCodeRefused(TransferException.RescanRequired(), nothing)
        assertEquals(ScanState(ScanStep.Scanning(), note = ScanNote.RescanRequired), state)
    }

    @Test
    fun aDamagedTransferMeansScanningAgain() {
        assertEquals(
            ScanState(ScanStep.Scanning(), note = ScanNote.Damaged),
            typing.afterCodeRefused(TransferException.Damaged(), nothing),
        )
    }

    @Test
    fun aCodeOfTheWrongLengthIsNotAnAttempt() {
        val state = typing.afterCodeRefused(TransferException.InvalidCode(6u), standing(7, 7, attempts = 3))
        assertEquals(ScanStep.Code(digits = 6, attemptsLeft = 3), state.step)
    }

    @Test
    fun aCheckThePhoneCouldNotDoLeavesTheCodeToBeTypedAgain() {
        val state = typing.afterCodeRefused(TransferException.OutOfMemory(), standing(7, 7))
        assertEquals(ScanStep.Code(digits = 8, attemptsLeft = 3), state.step)
        assertEquals(ScanNote.OutOfMemory, state.note)
        assertEquals(ScanNote.Failed, typing.afterCodeRefused(TransferException.Failed(), standing(7, 7)).note)
    }

    @Test
    fun aCodeTypedTooEarlyGoesBackToScanning() {
        val state = typing.afterCodeRefused(TransferException.Incomplete(), standing(5, 7))
        assertEquals(ScanStep.Scanning(5, 7), state.step)
    }

    @Test
    fun theCodeFieldTakesDigitsOnlyAndNoMoreThanTheCodeHas() {
        assertEquals("4821", codeInput("48 21", 8))
        assertEquals("48213377", codeInput("4821-3377-99", 8))
        assertEquals("", codeInput("abc", 8))
    }

    @Test
    fun theCameraOnlyRunsWhileThereIsSomethingToScanAndItIsAllowed() {
        assertTrue(scanning.scanning)
        assertFalse(scanning.copy(camera = CameraNotice.Denied).scanning)
        assertFalse(typing.scanning)
        assertFalse(ScanState(ScanStep.Checking(8, 3)).scanning)
    }
}
