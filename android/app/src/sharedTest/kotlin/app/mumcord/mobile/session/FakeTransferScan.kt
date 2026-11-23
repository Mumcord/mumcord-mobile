// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.session

import app.mumcord.mobile.bridge.BridgeException
import app.mumcord.mobile.bridge.ImportReport
import app.mumcord.mobile.bridge.NoHandle
import app.mumcord.mobile.bridge.TransferException
import app.mumcord.mobile.bridge.TransferPreview
import app.mumcord.mobile.bridge.TransferProgress
import app.mumcord.mobile.bridge.TransferScan
import java.util.concurrent.atomic.AtomicInteger

/**
 * A "Send to your phone" scan that follows the rules of the bridge's one without its
 * cryptography: [parts] are the texts of the QR codes, [code] opens them, [preview] is what they
 * hold and [import] what importing does. The real format is tested in the engine and, with
 * codes made by it, in the end-to-end test.
 */
class FakeTransferScan(
    private val parts: List<String>,
    private val code: String,
    private val preview: TransferPreview,
    private val import: () -> ImportReport,
) : TransferScan(NoHandle) {
    private val collected = LinkedHashSet<String>()
    private val attempts = AtomicInteger(MAX_ATTEMPTS)

    @Volatile
    private var opened = false

    /** Set to make the next step fail with it, once. */
    @Volatile
    var failNext: TransferException? = null

    /** How often the scan was thrown away. */
    val resets = AtomicInteger()

    /** The codes typed so far, to check what reached the bridge. */
    val checkedCodes = AtomicInteger()

    private fun failure(): TransferException? = failNext?.also { failNext = null }

    @Synchronized
    override fun addPart(text: String): TransferProgress {
        failure()?.let { failed ->
            if (failed is TransferException.Expired || failed is TransferException.ClockSkew) clear()
            throw failed
        }
        if (text !in parts) throw TransferException.NotATransfer()
        collected += text
        return TransferProgress(collected.size.toUByte(), parts.size.toUByte())
    }

    @Synchronized
    override fun progress(): TransferProgress? =
        if (collected.isEmpty()) null else TransferProgress(collected.size.toUByte(), parts.size.toUByte())

    override fun codeDigits(): UByte = code.length.toUByte()

    override fun attemptsLeft(): UByte = attempts.get().toUByte()

    @Synchronized
    override fun reset() {
        resets.incrementAndGet()
        clear()
    }

    private fun clear() {
        collected.clear()
        attempts.set(MAX_ATTEMPTS)
        opened = false
    }

    override suspend fun `open`(code: String): TransferPreview = synchronized(this) {
        checkedCodes.incrementAndGet()
        failure()?.let { failed ->
            if (failed !is TransferException.OutOfMemory && failed !is TransferException.Failed) clear()
            throw failed
        }
        if (collected.size < parts.size) throw TransferException.Incomplete()
        if (code.length != this.code.length) throw TransferException.InvalidCode(this.code.length.toUByte())
        if (code != this.code) {
            if (attempts.decrementAndGet() == 0) {
                clear()
                throw TransferException.RescanRequired()
            }
            throw TransferException.WrongCode(attempts.get().toUByte())
        }
        opened = true
        preview
    }

    override suspend fun importOpened(): ImportReport = synchronized(this) {
        if (!opened) throw BridgeException.InvalidInput("no transfer is open")
        import()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
