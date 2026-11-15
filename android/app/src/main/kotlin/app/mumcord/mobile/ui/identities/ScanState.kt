// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.ui.identities

import app.mumcord.mobile.bridge.ImportReport
import app.mumcord.mobile.bridge.TransferException
import app.mumcord.mobile.bridge.TransferPreview
import app.mumcord.mobile.bridge.TransferProgress
import app.mumcord.mobile.bridge.TransferSkipReason
import app.mumcord.mobile.session.ConnectionError

/** What the scan screen says about the camera while it cannot be used. */
enum class CameraNotice {
    /** Say why the camera is needed, then let the system ask. */
    Explain,

    /** The user said no; the screen offers to ask again. */
    Denied,

    /** The system no longer asks: only the system settings help. */
    Blocked,
}

/** Why the last QR code or the typed code was not taken, in the user's terms. */
enum class ScanNote {
    /** A QR code of something else. */
    NotATransfer,

    /** Read with an error; the next look at it helps. */
    Unreadable,
    NewerVersion,
    Expired,
    ClockSkew,

    /** The code was wrong three times. */
    RescanRequired,
    Damaged,
    OutOfMemory,
    Failed,

    /** The camera of the phone could not be started. */
    NoCamera,
}

/** Where the scan stands. */
sealed interface ScanStep {
    /** Collecting the QR codes; [total] is 0 before the first one. */
    data class Scanning(val have: Int = 0, val total: Int = 0) : ScanStep

    /**
     * All QR codes are there: the user types the code. [typed] is the only place the code ever
     * is, and it is emptied as soon as it has been checked.
     */
    data class Code(
        val digits: Int,
        val typed: String = "",
        val attemptsLeft: Int,
        /** The code typed before was wrong. */
        val wrong: Boolean = false,
    ) : ScanStep

    /** The typed code is being checked, which is slow on purpose. */
    data class Checking(val digits: Int, val attemptsLeft: Int) : ScanStep

    /** The code was right: what would arrive, waiting for the user's "Import". */
    data class Preview(
        val identities: List<String>,
        /** Null when the server list is not part of the transfer. */
        val servers: List<String>?,
        val importing: Boolean = false,
        val error: ConnectionError? = null,
    ) : ScanStep

    data class Done(
        val identitiesImported: Int,
        val serversImported: Int,
        val skipped: List<SkippedEntry>,
    ) : ScanStep
}

/** An entry of the transfer that was not imported. */
data class SkippedEntry(val name: String, val alreadyThere: Boolean)

data class ScanState(
    val step: ScanStep = ScanStep.Scanning(),
    /** Null while the camera may be used. */
    val camera: CameraNotice? = null,
    val note: ScanNote? = null,
) {
    /** The camera only runs while there is something to scan. */
    val scanning: Boolean
        get() = step is ScanStep.Scanning && camera == null
}

/** What the scan says after a step that the bridge did not refuse. */
data class ScanStanding(val progress: TransferProgress?, val codeDigits: Int, val attemptsLeft: Int)

/** After a QR code was taken: more to scan, or all there and the code can be typed. */
fun ScanState.afterPart(standing: ScanStanding): ScanState {
    val progress = standing.progress ?: return copy(step = ScanStep.Scanning(), note = null)
    val have = progress.have.toInt()
    val total = progress.total.toInt()
    val step = if (total > 0 && have >= total) {
        ScanStep.Code(digits = standing.codeDigits, attemptsLeft = standing.attemptsLeft)
    } else {
        ScanStep.Scanning(have, total)
    }
    return copy(step = step, note = null)
}

/** After a QR code was refused. What was collected stays unless the bridge started over. */
fun ScanState.afterPartRefused(error: TransferException): ScanState = when (error) {
    is TransferException.NotATransfer -> copy(note = ScanNote.NotATransfer)
    is TransferException.BadChecksum -> copy(note = ScanNote.Unreadable)
    is TransferException.UnsupportedVersion -> copy(note = ScanNote.NewerVersion)
    else -> startedOver(error)
}

/** After the typed code was checked and refused. */
fun ScanState.afterCodeRefused(error: TransferException, standing: ScanStanding): ScanState = when (error) {
    is TransferException.WrongCode -> copy(
        step = ScanStep.Code(standing.codeDigits, attemptsLeft = error.attemptsLeft.toInt(), wrong = true),
        note = null,
    )
    // Not counted as an attempt: the code just has another length than was typed.
    is TransferException.InvalidCode -> copy(
        step = ScanStep.Code(error.digits.toInt(), attemptsLeft = standing.attemptsLeft),
        note = null,
    )
    is TransferException.Incomplete -> afterPart(standing)
    // The phone could not do the check; the code can be typed again.
    is TransferException.OutOfMemory, is TransferException.Failed -> copy(
        step = ScanStep.Code(standing.codeDigits, attemptsLeft = standing.attemptsLeft),
        note = if (error is TransferException.OutOfMemory) ScanNote.OutOfMemory else ScanNote.Failed,
    )
    else -> startedOver(error)
}

/** The bridge threw the collected codes away: the scan begins again, saying why. */
private fun ScanState.startedOver(error: TransferException): ScanState = copy(
    step = ScanStep.Scanning(),
    note = when (error) {
        is TransferException.Expired -> ScanNote.Expired
        is TransferException.ClockSkew -> ScanNote.ClockSkew
        is TransferException.RescanRequired -> ScanNote.RescanRequired
        is TransferException.Damaged -> ScanNote.Damaged
        is TransferException.UnsupportedVersion -> ScanNote.NewerVersion
        else -> ScanNote.Failed
    },
)

fun TransferPreview.toStep(): ScanStep.Preview = ScanStep.Preview(
    identities = identities.map { it.label },
    servers = servers?.map { it.name },
)

fun ImportReport.toStep(): ScanStep.Done = ScanStep.Done(
    identitiesImported = identitiesImported.size,
    serversImported = serversImported.size,
    skipped = identitiesSkipped.map { SkippedEntry(it.label, it.reason == TransferSkipReason.DUPLICATE) } +
        serversSkipped.map { SkippedEntry(it.name, it.reason == TransferSkipReason.DUPLICATE) },
)

/** Only digits, and never more than the code has. */
fun codeInput(text: String, digits: Int): String = text.filter { it in '0'..'9' }.take(digits)

/** "n of m" as a share of 100, rounded, as the mockup shows beside the count. */
fun ScanStep.Scanning.percent(): Int = if (total == 0) 0 else (have * 100 + total / 2) / total
