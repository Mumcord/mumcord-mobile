// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mumcord.mobile.bridge.SecureStorageException
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecureStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "securestore-test")
    private val storage = KeystoreSecureStorage(directory)

    private val account = "identity/3/0"
    private val secretText = "TOP-SECRET-CERTIFICATE-BYTES"
    private val secret = secretText.toByteArray()

    @Before
    @After
    fun clear() {
        directory.deleteRecursively()
    }

    private fun entryFile(name: String) = File(directory, EntryCodec.fileName(name))

    private fun failureOf(block: () -> Unit): SecureStorageException.Failed =
        assertThrows(SecureStorageException.Failed::class.java) { block() }

    @Test
    fun storedSecretComesBackUnchanged() {
        storage.set(account, secret)

        assertArrayEquals(secret, storage.get(account))
    }

    @Test
    fun missingItemIsNullAndDeletingItIsNoError() {
        assertNull(storage.get(account))
        storage.delete(account)
    }

    @Test
    fun settingAgainReplacesTheSecret() {
        storage.set(account, secret)
        storage.set(account, byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), storage.get(account))
    }

    @Test
    fun deletedItemIsMissing() {
        storage.set(account, secret)
        storage.delete(account)

        assertNull(storage.get(account))
        assertFalse(entryFile(account).exists())
    }

    @Test
    fun binaryEmptyAndLargeSecretsRoundTrip() {
        val allBytes = ByteArray(256) { it.toByte() }
        val large = Random(1).nextBytes(8 * 1024)
        storage.set("binary", allBytes)
        storage.set("empty", ByteArray(0))
        storage.set("large", large)

        assertArrayEquals(allBytes, storage.get("binary"))
        assertArrayEquals(ByteArray(0), storage.get("empty"))
        assertArrayEquals(large, storage.get("large"))
    }

    @Test
    fun accountsAreKeptApart() {
        storage.set("identity/1/0", byteArrayOf(1))
        storage.set("identity/1/1", byteArrayOf(2))
        storage.delete("identity/1/0")

        assertNull(storage.get("identity/1/0"))
        assertArrayEquals(byteArrayOf(2), storage.get("identity/1/1"))
    }

    @Test
    fun secretIsNotStoredInTheClearAndNoTemporaryFileStays() {
        storage.set(account, secret)

        val files = directory.listFiles().orEmpty()
        assertEquals(listOf(EntryCodec.fileName(account)), files.map { it.name })
        assertFalse(String(files.single().readBytes(), Charsets.ISO_8859_1).contains(secretText))
    }

    @Test
    fun sameSecretIsEncryptedDifferentlyEachTime() {
        storage.set(account, secret)
        val first = entryFile(account).readBytes()
        storage.set(account, secret)

        assertFalse(first.contentEquals(entryFile(account).readBytes()))
    }

    @Test
    fun anotherInstanceReadsTheSameItems() {
        storage.set(account, secret)

        assertArrayEquals(secret, KeystoreSecureStorage(directory).get(account))
    }

    @Test
    fun tamperedEntryIsAnErrorNotAMissingItem() {
        storage.set(account, secret)
        val file = entryFile(account)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertNoSecretIn(failureOf { storage.get(account) })
    }

    @Test
    fun truncatedEntryIsAnError() {
        storage.set(account, secret)
        entryFile(account).writeBytes(byteArrayOf(1, 2, 3))

        assertNoSecretIn(failureOf { storage.get(account) })
    }

    @Test
    fun entryCopiedToAnotherAccountIsAnError() {
        storage.set(account, secret)
        storage.set("identity/3/1", byteArrayOf(9))
        entryFile(account).copyTo(entryFile("identity/3/1"), overwrite = true)

        assertNoSecretIn(failureOf { storage.get("identity/3/1") })
        assertArrayEquals(secret, storage.get(account))
    }

    @Test
    fun lostKeyIsAnErrorAndNewItemsWorkAgain() {
        storage.set(account, secret)
        KeyStore.getInstance(KeystoreSecureStorage.KEYSTORE).apply { load(null) }
            .deleteEntry(KeystoreSecureStorage.KEY_ALIAS)

        assertNoSecretIn(failureOf { storage.get(account) })
        storage.set(account, secret)
        assertArrayEquals(secret, storage.get(account))
    }

    @Test
    fun unwritableStorageIsAnErrorWithoutSecretOrAccount() {
        // A plain file where the directory should be makes every write fail.
        directory.writeBytes(byteArrayOf(0))

        assertNoSecretIn(failureOf { storage.set(account, secret) })
    }

    @Test
    fun keyStaysInsideTheKeystore() {
        storage.set(account, secret)
        val key = KeyStore.getInstance(KeystoreSecureStorage.KEYSTORE).apply { load(null) }
            .getKey(KeystoreSecureStorage.KEY_ALIAS, null)

        assertNotNull(key)
        assertNull(key.encoded)
    }

    @Test
    fun concurrentCallsFromBackgroundThreadsStayConsistent() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val shared = "shared"
        val sharedValues = (0 until threads).map { byteArrayOf(it.toByte()) }
        val jobs = (0 until threads).map { index ->
            Callable {
                val own = "identity/$index/0"
                repeat(25) { round ->
                    val value = Random(index * 1000 + round).nextBytes(512)
                    storage.set(own, value)
                    assertArrayEquals(value, storage.get(own))
                    storage.set(shared, sharedValues[index])
                    val seen = storage.get(shared)
                    assertTrue(sharedValues.any { it.contentEquals(seen) })
                    storage.delete(own)
                    assertNull(storage.get(own))
                }
            }
        }
        try {
            // get() rethrows whatever failed inside a job.
            pool.invokeAll(jobs, 120, TimeUnit.SECONDS).forEach {
                assertFalse("timed out", it.isCancelled)
                it.get()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun assertNoSecretIn(failure: SecureStorageException.Failed) {
        val text = failure.detail + failure.message
        assertFalse(text, text.contains(secretText))
        assertFalse(text, text.contains(account))
        assertFalse(text, text.contains(EntryCodec.fileName(account).removeSuffix(".bin")))
        assertFalse(text, text.contains(directory.name))
    }
}
