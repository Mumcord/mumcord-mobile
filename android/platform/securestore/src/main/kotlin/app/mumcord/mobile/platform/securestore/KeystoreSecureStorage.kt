// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.platform.securestore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.mumcord.mobile.bridge.SecureStorage
import app.mumcord.mobile.bridge.SecureStorageException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.read
import kotlin.concurrent.write

/** The Keystore key is gone although entries encrypted with it still exist. */
private class MissingKeyException : Exception()

/**
 * Secure storage of the phone: every secret is encrypted with an AES-256-GCM key that lives in
 * the Android Keystore and cannot be exported, and is kept as one file per account in
 * app-private storage. The directory is excluded from cloud backup and device-to-device
 * transfer by the app's backup rules; the key never leaves the device in any case.
 *
 * The key needs no unlocked screen, because voice must keep working while the phone is locked.
 *
 * A damaged or undecryptable entry is an error, never a missing item: the engine would
 * otherwise silently create a new identity.
 */
class KeystoreSecureStorage internal constructor(private val directory: File) : SecureStorage {

    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    override fun set(account: String, secret: ByteArray) = reporting("store") {
        lock.write {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            cipher.updateAAD(EntryCodec.associatedData(account))
            val ciphertext = cipher.doFinal(secret)
            writeAtomically(entryFile(account), EntryCodec.encode(Entry(cipher.iv, ciphertext)))
        }
    }

    override fun get(account: String): ByteArray? = reporting("read") {
        lock.read {
            val file = entryFile(account)
            if (!file.exists()) return@read null
            val entry = EntryCodec.decode(file.readBytes())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey() ?: throw MissingKeyException(),
                GCMParameterSpec(EntryCodec.TAG_LENGTH * 8, entry.iv),
            )
            cipher.updateAAD(EntryCodec.associatedData(account))
            cipher.doFinal(entry.ciphertext)
        }
    }

    override fun delete(account: String) = reporting("delete") {
        lock.write {
            Files.deleteIfExists(entryFile(account).toPath())
            Unit
        }
    }

    private fun entryFile(account: String) = File(directory, EntryCodec.fileName(account))

    /** A crash or power loss leaves either the old entry or the new one, never half of one. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("cannot create the storage directory")
        }
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as SecretKey?

    private fun createKey(): SecretKey =
        try {
            generateKey(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            // Many phones have no separate security chip; the key then lives in the trusted
            // execution environment, which is still outside the app and not exportable.
            generateKey(strongBox = false)
        }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /**
     * Turns every failure into the bridge's error. The text is logged by the engine, so it names
     * only the operation and the kind of failure: exception messages can contain file paths, and
     * neither the secret nor the account may appear.
     */
    private inline fun <T> reporting(operation: String, block: () -> T): T =
        try {
            block()
        } catch (failure: Exception) {
            throw SecureStorageException.Failed(
                "could not $operation the item (${failure.javaClass.simpleName})",
            )
        }

    internal companion object {
        const val DIRECTORY = "securestore"
        const val KEY_ALIAS = "mumcord.securestore.v1"
        const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256

        // Process-wide, because every instance works on the same key and the same files.
        // Writers also create the key, so two first writes cannot create two keys.
        private val lock = ReentrantReadWriteLock()
    }
}
