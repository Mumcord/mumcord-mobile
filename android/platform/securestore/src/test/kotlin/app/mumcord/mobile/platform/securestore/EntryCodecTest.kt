// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryCodecTest {
    private val iv = ByteArray(EntryCodec.IV_LENGTH) { it.toByte() }
    private val ciphertext = ByteArray(EntryCodec.TAG_LENGTH + 5) { (it * 7).toByte() }

    @Test
    fun encodedEntryDecodesToTheSameParts() {
        val decoded = EntryCodec.decode(EntryCodec.encode(Entry(iv, ciphertext)))

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun entryOfAnEmptySecretDecodes() {
        val tagOnly = ByteArray(EntryCodec.TAG_LENGTH)

        assertArrayEquals(tagOnly, EntryCodec.decode(EntryCodec.encode(Entry(iv, tagOnly))).ciphertext)
    }

    @Test
    fun truncatedEntryIsMalformed() {
        val encoded = EntryCodec.encode(Entry(iv, ByteArray(EntryCodec.TAG_LENGTH)))

        assertThrows(MalformedEntryException::class.java) { EntryCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(MalformedEntryException::class.java) { EntryCodec.decode(ByteArray(0)) }
    }

    @Test
    fun unknownVersionIsMalformed() {
        val encoded = EntryCodec.encode(Entry(iv, ciphertext))
        encoded[0] = 2

        assertThrows(MalformedEntryException::class.java) { EntryCodec.decode(encoded) }
    }

    @Test
    fun nonceOfTheWrongLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EntryCodec.encode(Entry(ByteArray(8), ciphertext)) }
    }

    @Test
    fun fileNameIsStableAndHidesTheAccount() {
        val name = EntryCodec.fileName("identity/3/0")

        assertEquals(name, EntryCodec.fileName("identity/3/0"))
        assertTrue(Regex("[0-9a-f]{64}\\.bin").matches(name))
        assertFalse(name.contains("identity"))
    }

    @Test
    fun accountsGetDifferentFilesAndAssociatedData() {
        assertNotEquals(EntryCodec.fileName("identity/3/0"), EntryCodec.fileName("identity/3/1"))
        assertFalse(
            EntryCodec.associatedData("identity/3/0").contentEquals(EntryCodec.associatedData("identity/3/1")),
        )
    }
}
