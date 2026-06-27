package org.tatrman.kantheon.iris.audit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

/** Tests for [Ed25519Signer.fromKeyRef] — Secret-loaded signing key (Stage 1.4 A2). */
class Ed25519KeyLoaderSpec :
    StringSpec({

        fun KeyPair.toPem(): String {
            val b64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            return buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                appendLine(b64.encodeToString(private.encoded))
                appendLine("-----END PRIVATE KEY-----")
                appendLine("-----BEGIN PUBLIC KEY-----")
                appendLine(b64.encodeToString(public.encoded))
                appendLine("-----END PUBLIC KEY-----")
            }
        }

        // A record signed by `signer` and chained from GENESIS must verify under the
        // same signer — proves the loaded private+public are a matching pair.
        fun roundTrips(signer: Ed25519Signer): Boolean {
            val rec = signedRecord(1L, Instant.EPOCH, "u1", "turn", """{"q":"a"}""", GENESIS, signer)
            return verifyChain(listOf(rec), signer)
        }

        "loads a keypair from inline PEM and signs verifiably" {
            val pem = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().toPem()
            roundTrips(Ed25519Signer.fromKeyRef(pem)) shouldBe true
        }

        "loads a keypair from a PEM file path" {
            val pem = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().toPem()
            val file = Files.createTempFile("iris-audit-key", ".pem")
            Files.writeString(file, pem)
            try {
                roundTrips(Ed25519Signer.fromKeyRef(file.toString())) shouldBe true
            } finally {
                Files.deleteIfExists(file)
            }
        }

        "a null or blank ref yields an ephemeral, self-consistent signer" {
            roundTrips(Ed25519Signer.fromKeyRef(null)) shouldBe true
            roundTrips(Ed25519Signer.fromKeyRef("   ")) shouldBe true
        }

        "a ref missing the PUBLIC KEY block is a hard error" {
            val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val privOnly =
                "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getEncoder().encodeToString(kp.private.encoded) +
                    "\n-----END PRIVATE KEY-----"
            shouldThrow<IllegalStateException> { Ed25519Signer.fromKeyRef(privOnly) }
                .message!! shouldContain "PUBLIC KEY"
        }

        "a malformed private key is a hard error" {
            val pem =
                "-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5\n-----END PRIVATE KEY-----\n" +
                    "-----BEGIN PUBLIC KEY-----\nbm90LWEta2V5\n-----END PUBLIC KEY-----"
            shouldThrow<IllegalStateException> { Ed25519Signer.fromKeyRef(pem) }
        }
    })
