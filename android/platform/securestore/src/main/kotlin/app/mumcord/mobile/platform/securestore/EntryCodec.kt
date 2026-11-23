// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import java.security.MessageDigest

/** An entry file that is not in the expected format: truncated, overwritten or from a newer app. */
internal class MalformedEntryException : Exception()

/** The encrypted parts of one stored entry. */
internal class Entry(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * On-disk format of one secure storage entry: one version byte, the 12-byte GCM nonce, then the
 * ciphertext with its 16-byte authentication tag.
 */
internal object EntryCodec {
    private const val VERSION: Byte = 1
    const val IV_LENGTH = 12
    const val TAG_LENGTH = 16
    private const val HEADER_LENGTH = 1 + IV_LENGTH

    /**
     * File name for an account. A hash, because account names contain `/` and because the name
     * must not grow with the account.
     */
    fun fileName(account: String): String = "${hex(accountHash(account))}.bin"

    /**
     * Data that is authenticated together with the secret. Binding the account makes an entry
     * that was copied over another account's file fail to decrypt instead of returning the wrong
     * secret.
     */
    fun associatedData(account: String): ByteArray = byteArrayOf(VERSION) + accountHash(account)

    fun encode(entry: Entry): ByteArray {
        require(entry.iv.size == IV_LENGTH) { "unexpected nonce length" }
        return byteArrayOf(VERSION) + entry.iv + entry.ciphertext
    }

    fun decode(bytes: ByteArray): Entry {
        if (bytes.size < HEADER_LENGTH + TAG_LENGTH || bytes[0] != VERSION) {
            throw MalformedEntryException()
        }
        return Entry(
            iv = bytes.copyOfRange(1, HEADER_LENGTH),
            ciphertext = bytes.copyOfRange(HEADER_LENGTH, bytes.size),
        )
    }

    private fun accountHash(account: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
