@file:Suppress("TooGenericExceptionCaught", "MagicNumber")

package org.tatrman.kantheon.hebe.plugins.signature

import org.tatrman.kantheon.hebe.config.PluginSignatureMode
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import java.security.KeyFactory
import java.security.Security
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.Logger

sealed class SignatureResult {
    data class Verified(
        val publisherKey: String,
    ) : SignatureResult()

    data class Unsigned(
        val reason: String,
    ) : SignatureResult()

    data class BadSignature(
        val reason: String,
    ) : SignatureResult()
}

open class SignatureVerifier(
    private val signatureMode: PluginSignatureMode,
    private val trustedPublisherKeys: List<String>,
    private val log: Logger,
) {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun verify(
        manifest: PluginManifest,
        archiveHash: ByteArray,
    ): SignatureResult {
        val publisherKey = manifest.publisherKey
        val signature = manifest.signature

        return when (signatureMode) {
            PluginSignatureMode.DISABLED -> SignatureResult.Verified("disabled")

            PluginSignatureMode.OPTIONAL -> {
                when {
                    publisherKey == null || signature == null -> {
                        log.warn(
                            "Plugin loaded without signature (optional mode). " +
                                "Set a signature in plugin.toml for production use.",
                        )
                        SignatureResult.Unsigned("no signature provided")
                    }

                    else -> verifySignature(publisherKey, archiveHash, signature)
                }
            }

            PluginSignatureMode.REQUIRED -> {
                when {
                    publisherKey == null || signature == null -> {
                        log.error("Plugin signature required but not present in manifest")
                        return SignatureResult.BadSignature("signature required but not present")
                    }

                    publisherKey !in trustedPublisherKeys -> {
                        log.error(
                            "Plugin publisher key '{}' not in trusted list: {}",
                            publisherKey,
                            trustedPublisherKeys,
                        )
                        return SignatureResult.BadSignature("untrusted publisher key")
                    }

                    else -> verifySignature(publisherKey, archiveHash, signature)
                }
            }
        }
    }

    private fun verifySignature(
        publisherKey: String,
        archiveHash: ByteArray,
        signatureBase64: String,
    ): SignatureResult {
        val signatureBytes =
            try {
                base64UrlDecode(signatureBase64)
            } catch (e: Exception) {
                return SignatureResult.BadSignature("invalid base64 in signature: ${e.message}")
            }

        val publicKeyBytes =
            try {
                hexDecode(publisherKey)
            } catch (e: Exception) {
                return SignatureResult.BadSignature("invalid hex publisher key: ${e.message}")
            }

        val ok = verifyEd25519(publicKeyBytes, archiveHash, signatureBytes)
        return if (ok) {
            SignatureResult.Verified(publisherKey)
        } else {
            SignatureResult.BadSignature("signature verification failed")
        }
    }

    private fun verifyEd25519(
        publicKeyBytes: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            val keyFactory = KeyFactory.getInstance("EdDSA", "BC")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val sig = java.security.Signature.getInstance("EdDSA", "BC")
            sig.initVerify(publicKey)
            sig.update(message)
            sig.verify(signature)
        } catch (e: Exception) {
            log.debug("Ed25519 verification failed: {}", e.message)
            false
        }

    private fun base64UrlDecode(input: String): ByteArray =
        java.util.Base64
            .getUrlDecoder()
            .decode(input)

    private fun hexDecode(input: String): ByteArray {
        val hexChars = "0123456789abcdefABCDEF"
        require(input.all { it in hexChars }) { "Invalid hex character: $input" }
        require(input.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(input.length / 2) { i ->
            val hi = hexCharToInt(input[i * 2])
            val lo = hexCharToInt(input[i * 2 + 1])
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun hexCharToInt(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("Invalid hex char: $c")
        }
}
