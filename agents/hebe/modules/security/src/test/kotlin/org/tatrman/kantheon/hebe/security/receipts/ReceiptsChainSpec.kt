package org.tatrman.kantheon.hebe.security.receipts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The receipts hash-chain + Ed25519 contract (Hebe P3 S3.2 T1/T4), backend-agnostic
 * via [ReceiptChain] — the algorithm both the file log and [PostgresReceiptsStore]
 * use. Proves: genesis prev-hash linkage, `prev_hash` chaining, `self_hash` recompute,
 * tamper detection, and that the Ed25519 signature validates against the public key
 * **derived from the seed** (the P3 S3.2 `publicKeyBytes` fix). Live-Postgres append
 * is the integration suite (planning-conventions §4).
 */
class ReceiptsChainSpec :
    StringSpec({
        val key = Ed25519PrivateKey.generate()
        val pub = key.publicKeyBytes()

        fun payload(
            turn: String,
            prevHash: String,
        ): List<Pair<String, Any?>> =
            listOf(
                "turnId" to turn,
                "tool" to "echo",
                "ok" to true,
                "prevHash" to prevHash,
            )

        "genesis link chains from ZERO_HASH and self_hash recomputes" {
            val link = ReceiptChain.link(payload("t1", ReceiptChain.ZERO_HASH), key)
            ReceiptChain.selfHashOf(link.canonicalPayload) shouldBe link.selfHash
            link.canonicalPayload.contains(ReceiptChain.ZERO_HASH) shouldBe true
        }

        "each link's prev_hash is the prior link's self_hash" {
            val l1 = ReceiptChain.link(payload("t1", ReceiptChain.ZERO_HASH), key)
            val l2 = ReceiptChain.link(payload("t2", l1.selfHash), key)
            l2.canonicalPayload.contains(l1.selfHash) shouldBe true
            l2.selfHash shouldNotBe l1.selfHash
        }

        "the Ed25519 signature validates against the seed-derived public key" {
            val link = ReceiptChain.link(payload("t1", ReceiptChain.ZERO_HASH), key)
            ReceiptChain.verifySignature(link.selfHash, link.sig, pub).shouldBeTrue()
        }

        "tampering the payload breaks the recomputed self_hash" {
            val link = ReceiptChain.link(payload("t1", ReceiptChain.ZERO_HASH), key)
            val tampered = link.canonicalPayload.replace("\"echo\"", "\"rm-rf\"")
            ReceiptChain.selfHashOf(tampered) shouldNotBe link.selfHash
        }

        "a signature over a different self_hash does not verify" {
            val link = ReceiptChain.link(payload("t1", ReceiptChain.ZERO_HASH), key)
            val other = ReceiptChain.link(payload("t2", link.selfHash), key)
            ReceiptChain.verifySignature(other.selfHash, link.sig, pub).shouldBeFalse()
        }
    })
