@file:Suppress("TooGenericExceptionCaught", "MagicNumber", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.signature

import org.tatrman.kantheon.hebe.config.PluginSignatureMode
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SignatureVerifierTest {
    private val log = LoggerFactory.getLogger(SignatureVerifierTest::class.java)

    private lateinit var verifier: SignatureVerifier

    @BeforeEach
    fun setup() {
        verifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.OPTIONAL,
                trustedPublisherKeys = emptyList(),
                log = log,
            )
    }

    @Test
    fun `verify returns Verified when signature mode is DISABLED`() {
        val disabledVerifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.DISABLED,
                trustedPublisherKeys = emptyList(),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val archiveHash = "test".toByteArray()

        val result = disabledVerifier.verify(manifest, archiveHash)

        assertTrue(result is SignatureResult.Verified)
        assertEquals("disabled", (result as SignatureResult.Verified).publisherKey)
    }

    @Test
    fun `verify returns Unsigned when optional mode and no signature`() {
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )
        val archiveHash = "test".toByteArray()

        val result = verifier.verify(manifest, archiveHash)

        assertTrue(result is SignatureResult.Unsigned)
        assertEquals("no signature provided", (result as SignatureResult.Unsigned).reason)
    }

    @Test
    fun `verify returns BadSignature when required mode and no signature`() {
        val requiredVerifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.REQUIRED,
                trustedPublisherKeys = listOf("abc123"),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = null,
                publisherKey = null,
            )

        val result = requiredVerifier.verify(manifest, "test".toByteArray())

        assertTrue(result is SignatureResult.BadSignature)
        assertEquals("signature required but not present", (result as SignatureResult.BadSignature).reason)
    }

    @Test
    fun `verify returns BadSignature when publisher not in trusted keys`() {
        val requiredVerifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.REQUIRED,
                trustedPublisherKeys = listOf("trusted_key"),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = "some_signature",
                publisherKey = "untrusted_key",
            )

        val result = requiredVerifier.verify(manifest, "test".toByteArray())

        assertTrue(result is SignatureResult.BadSignature)
        assertEquals("untrusted publisher key", (result as SignatureResult.BadSignature).reason)
    }

    @Test
    fun `verify returns BadSignature for invalid base64 signature`() {
        val verifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.OPTIONAL,
                trustedPublisherKeys = emptyList(),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = "not-valid-base64!!!",
                publisherKey = "abc123",
            )

        val result = verifier.verify(manifest, "test".toByteArray())

        assertTrue(result is SignatureResult.BadSignature)
        assertTrue((result as SignatureResult.BadSignature).reason.contains("invalid base64"))
    }

    @Test
    fun `verify returns BadSignature for invalid hex publisher key`() {
        val verifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.OPTIONAL,
                trustedPublisherKeys = emptyList(),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = "dGVzdA==",
                publisherKey = "not-valid-hex!!!",
            )

        val result = verifier.verify(manifest, "test".toByteArray())

        assertTrue(result is SignatureResult.BadSignature)
        assertTrue((result as SignatureResult.BadSignature).reason.contains("invalid hex publisher key"))
    }

    @Test
    fun `hexDecode throws for invalid hex characters`() {
        val verifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.OPTIONAL,
                trustedPublisherKeys = emptyList(),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = "dGVzdA==",
                publisherKey = "xyz123",
            )

        val result = verifier.verify(manifest, "test".toByteArray())

        assertTrue(result is SignatureResult.BadSignature)
    }

    @Test
    fun `hexDecode throws for odd-length hex string`() {
        val verifier =
            SignatureVerifier(
                signatureMode = PluginSignatureMode.OPTIONAL,
                trustedPublisherKeys = emptyList(),
                log = log,
            )
        val manifest =
            PluginManifest(
                hebeApiVersion = "0.1.x",
                capabilities = emptySet(),
                permissions = emptySet(),
                allowlistDomains = emptyList(),
                signature = "dGVzdA==",
                publisherKey = "abc12", // odd length
            )

        val result = verifier.verify(manifest, "test".toByteArray())

        assertTrue(result is SignatureResult.BadSignature)
        assertTrue((result as SignatureResult.BadSignature).reason.contains("Hex string must have even length"))
    }
}
