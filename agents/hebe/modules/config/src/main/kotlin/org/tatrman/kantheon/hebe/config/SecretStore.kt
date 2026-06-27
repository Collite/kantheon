@file:Suppress("detekt:all")

package org.tatrman.kantheon.hebe.config

import org.tatrman.kantheon.hebe.api.SecretLookup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.logging.Level
import java.util.logging.Logger
import javax.crypto.spec.SecretKeySpec

interface SecretStoreProvider {
    suspend fun get(key: String): ByteArray?

    suspend fun set(
        key: String,
        value: ByteArray,
    )

    suspend fun delete(key: String): Boolean

    suspend fun list(): List<String>
}

class OsKeychainSecretStore private constructor(
    private val delegate: SecretStoreImpl,
) : SecretStoreProvider,
    SecretLookup {
    private val logger = Logger.getLogger(OsKeychainSecretStore::class.java.name)

    companion object {
        private const val SECRET_PREFIX = "hebe.secret."

        fun create(dataDir: Path): OsKeychainSecretStore {
            val os = System.getProperty("os.name").lowercase()
            val delegate =
                when {
                    os.contains("mac") || os.contains("darwin") -> MacKeychainImpl()
                    os.contains("linux") -> LinuxKeychainImpl()
                    else -> PassphraseBasedImpl(dataDir)
                }
            return OsKeychainSecretStore(delegate)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun get(key: String): ByteArray? =
        try {
            delegate.get(SECRET_PREFIX + key)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get secret: $key", e)
            null
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        try {
            delegate.set(SECRET_PREFIX + key, value)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to set secret: $key", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun delete(key: String): Boolean =
        try {
            delegate.delete(SECRET_PREFIX + key)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to delete secret: $key", e)
            false
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun list(): List<String> =
        try {
            delegate.list().filter { it.startsWith(SECRET_PREFIX) }.map { it.removePrefix(SECRET_PREFIX) }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to list secrets", e)
            emptyList()
        }

    override fun secret(name: String): String? =
        kotlinx.coroutines.runBlocking {
            get(name)?.let { String(it, Charsets.UTF_8) }
        }
}

private interface SecretStoreImpl {
    suspend fun get(key: String): ByteArray?

    suspend fun set(
        key: String,
        value: ByteArray,
    )

    suspend fun delete(key: String): Boolean

    suspend fun list(): List<String>
}

private class MacKeychainImpl : SecretStoreImpl {
    private val logger = Logger.getLogger(MacKeychainImpl::class.java.name)
    private val cache = mutableMapOf<String, ByteArray>()

    @Suppress("TooGenericExceptionCaught")
    override suspend fun get(key: String): ByteArray? {
        cache[key]?.let { return it }
        return try {
            val process = ProcessBuilder("security", "find-generic-password", "-a", key, "-w").redirectErrorStream(true).start()
            val output = process.inputStream.readBytes()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val value = output.toString(Charsets.UTF_8).trim()
                if (value.isNotEmpty()) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    cache[key] = bytes
                    return bytes
                }
            }
            null
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get from macOS keychain: $key", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        cache[key] = value
        try {
            // -U overwrites an existing item with the same account attribute
            val process =
                ProcessBuilder(
                    "security",
                    "add-generic-password",
                    "-a",
                    key,
                    "-w",
                    String(value, Charsets.UTF_8),
                    "-U",
                ).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor()
        } catch (e: Exception) {
            logger.warning("Failed to store in macOS keychain, using in-memory cache: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun delete(key: String): Boolean {
        cache.remove(key)
        return try {
            val process =
                ProcessBuilder(
                    "security",
                    "delete-generic-password",
                    "-a",
                    key,
                ).redirectErrorStream(true).start()
            process.waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun list(): List<String> = cache.keys.toList()
}

private class LinuxKeychainImpl : SecretStoreImpl {
    private val logger = Logger.getLogger(LinuxKeychainImpl::class.java.name)
    private val cache = mutableMapOf<String, ByteArray>()

    @Suppress("TooGenericExceptionCaught")
    override suspend fun get(key: String): ByteArray? {
        cache[key]?.let { return it }
        return try {
            val process = ProcessBuilder("secret-tool", "lookup", "attribute", key).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty()) {
                cache[key] = output
                return output
            }
            null
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get from secret-service: $key", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        cache[key] = value
        try {
            val process =
                ProcessBuilder("secret-tool", "store", "collection", "default", "attribute", key).redirectErrorStream(true).start()
            process.outputStream.write(value)
            process.outputStream.close()
            process.waitFor()
        } catch (e: Exception) {
            logger.warning("Failed to store in secret-service, using in-memory cache: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun delete(key: String): Boolean {
        cache.remove(key)
        return try {
            val process = ProcessBuilder("secret-tool", "remove", "attribute", key).redirectErrorStream(true).start()
            process.waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun list(): List<String> = cache.keys.toList()
}

private const val BUFFER_SIZE = 32

private class PassphraseBasedImpl(
    dataDir: Path,
) : SecretStoreImpl {
    private val logger = Logger.getLogger(PassphraseBasedImpl::class.java.name)
    private val keystoreFile: Path
    private val keyStore: KeyStore
    private val masterKey: ByteArray
    private val keyStorePassword: CharArray
    private val secureRandom = SecureRandom()

    init {
        val dir = dataDir.resolve(".hebe")
        Files.createDirectories(dir)
        keystoreFile = dir.resolve("secrets.jceks")

        val passphrasePath = dir.resolve("master.passphrase")
        val passphraseBytes =
            if (Files.exists(passphrasePath)) {
                Files.readAllBytes(passphrasePath)
            } else {
                val passphrase = ByteArray(BUFFER_SIZE)
                secureRandom.nextBytes(passphrase)
                Files.write(passphrasePath, passphrase)
                Files.setPosixFilePermissions(passphrasePath, PosixFilePermissions.fromString("600"))
                passphrase
            }
        keyStorePassword = passphraseBytes.toString(Charsets.UTF_8).toCharArray()
        masterKey = passphraseBytes

        keyStore = KeyStore.getInstance("JCEKS")
        if (Files.exists(keystoreFile)) {
            Files.newInputStream(keystoreFile).use { stream ->
                keyStore.load(stream, keyStorePassword)
            }
        } else {
            keyStore.load(null)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun saveKeyStore() {
        try {
            Files.newOutputStream(keystoreFile).use { stream ->
                keyStore.store(stream, keyStorePassword)
            }
            Files.setPosixFilePermissions(keystoreFile, PosixFilePermissions.fromString("600"))
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to persist KeyStore", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun get(key: String): ByteArray? {
        return try {
            val entry = keyStore.getEntry(key, null) as? KeyStore.SecretKeyEntry
            val encrypted = entry?.secretKey?.encoded ?: return null
            AeadEncryptor.decrypt(encrypted, masterKey)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to get secret: $key", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        try {
            val encrypted = AeadEncryptor.encrypt(value, masterKey)
            val secretKey = SecretKeySpec(encrypted, "AES")
            keyStore.setEntry(key, KeyStore.SecretKeyEntry(secretKey), null)
            saveKeyStore()
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to set secret: $key", e)
        }
    }

    override suspend fun delete(key: String): Boolean =
        try {
            keyStore.deleteEntry(key)
            saveKeyStore()
            true
        } catch (e: KeyStoreException) {
            logger.log(Level.WARNING, "Failed to delete secret: $key", e)
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun list(): List<String> =
        try {
            keyStore.aliases().toList()
        } catch (e: Exception) {
            emptyList()
        }
}

object AeadEncryptor {
    private const val KEY_SIZE_BYTES = BUFFER_SIZE
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val ALGORITHM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val spec = javax.crypto.spec.GCMParameterSpec(TAG_SIZE_BITS, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance(ALGORITHM)
        val iv = ciphertext.sliceArray(0 until IV_SIZE_BYTES)
        val encrypted = ciphertext.sliceArray(IV_SIZE_BYTES until ciphertext.size)
        val spec = javax.crypto.spec.GCMParameterSpec(TAG_SIZE_BITS, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encrypted)
    }
}
