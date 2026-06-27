package org.tatrman.kantheon.hebe.security.receipts

import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Security
import java.security.spec.NamedParameterSpec

private const val ED25519_KEY_SIZE = 32

/** Fixed DER prefix of an Ed25519 X.509 SubjectPublicKeyInfo (12 bytes before the 32-byte point). */
private val ED25519_SPKI_PREFIX: ByteArray =
    java.util.HexFormat
        .of()
        .parseHex("302a300506032b6570032100")

object SigningKey {
    private const val PRIVATE_KEY_NAME = "receipts.signing_key"

    init {
        Security.addProvider(
            org.bouncycastle.jce.provider
                .BouncyCastleProvider(),
        )
    }

    suspend fun bootstrap(secretStore: SecretStoreProvider): Ed25519PrivateKey {
        val existing = secretStore.get(PRIVATE_KEY_NAME)
        if (existing != null) {
            return Ed25519PrivateKey.load(existing)
        }

        val privateKey = Ed25519PrivateKey.generate()
        secretStore.set(PRIVATE_KEY_NAME, privateKey.encode())
        return privateKey
    }

    suspend fun load(secretStore: SecretStoreProvider): Ed25519PrivateKey? {
        val bytes = secretStore.get(PRIVATE_KEY_NAME) ?: return null
        return try {
            Ed25519PrivateKey.load(bytes)
        } catch (e: Exception) {
            null
        }
    }
}

class Ed25519PrivateKey(
    private val seed: ByteArray,
) {
    init {
        require(seed.size == ED25519_KEY_SIZE) { "Ed25519 seed must be 32 bytes" }
    }

    fun sign(message: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("EdDSA", "BC")
        val privateKey =
            keyFactory.generatePrivate(
                java.security.spec.EdECPrivateKeySpec(NamedParameterSpec("Ed25519"), seed),
            )
        val signature = java.security.Signature.getInstance("EdDSA", "BC")
        signature.initSign(privateKey)
        signature.update(message)
        return signature.sign()
    }

    /**
     * The X.509 `SubjectPublicKeyInfo` (DER) of the public key **derived from this
     * seed** — accepted directly by [Ed25519Verifier] (`X509EncodedKeySpec`). The
     * earlier implementation generated a *fresh random* keypair on every call, so the
     * persisted `public.key` could never verify the receipt chain (P3 S3.2 fix). The
     * derived raw public point is wrapped in the fixed 12-byte Ed25519 SPKI prefix.
     */
    fun publicKeyBytes(): ByteArray {
        val raw =
            org.bouncycastle.crypto.params
                .Ed25519PrivateKeyParameters(seed, 0)
                .generatePublicKey()
                .encoded
        return ED25519_SPKI_PREFIX + raw
    }

    fun encode(): ByteArray = seed.copyOf()

    companion object {
        init {
            // Idempotent: the production path registers BC via SigningKey's init, but
            // the key is also used directly (PostgresReceiptsStore, specs) without it.
            if (Security.getProvider("BC") == null) {
                Security.addProvider(
                    org.bouncycastle.jce.provider
                        .BouncyCastleProvider(),
                )
            }
        }

        fun generate(): Ed25519PrivateKey {
            val seed = ByteArray(ED25519_KEY_SIZE)
            SecureRandom().nextBytes(seed)
            return Ed25519PrivateKey(seed)
        }

        fun load(bytes: ByteArray): Ed25519PrivateKey {
            require(bytes.size == ED25519_KEY_SIZE) { "Ed25519 seed must be 32 bytes" }
            return Ed25519PrivateKey(bytes.copyOf())
        }
    }
}

object Ed25519Verifier {
    fun verify(
        publicKeyBytes: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            val keyFactory = KeyFactory.getInstance("EdDSA", "BC")
            val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
            val sig = java.security.Signature.getInstance("EdDSA", "BC")
            sig.initVerify(publicKey)
            sig.update(message)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
}
