package org.tatrman.kantheon.iris.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class AuditChainSpec :
    StringSpec({

        "appended rows form a verifiable hash chain" {
            val signer = Ed25519Signer()
            val store = InMemoryAuditStore(signer)
            store.append("u1", "turn", """{"q":"a"}""", Instant.parse("2026-06-17T09:00:00Z"))
            store.append("u1", "turn", """{"q":"b"}""", Instant.parse("2026-06-17T09:01:00Z"))
            store.append("u1", "typed_action", """{"k":"sort"}""", Instant.parse("2026-06-17T09:02:00Z"))

            val rows = store.all()
            rows.size shouldBe 3
            rows[0].prevHash shouldBe "GENESIS"
            rows[1].prevHash shouldBe rows[0].selfHash
            rows[2].prevHash shouldBe rows[1].selfHash
            rows[0].segment shouldBe "2026-06"
            verifyChain(rows, signer) shouldBe true
        }

        "a tampered payload breaks the chain" {
            val signer = Ed25519Signer()
            val store = InMemoryAuditStore(signer)
            store.append("u1", "turn", """{"q":"a"}""", Instant.parse("2026-06-17T09:00:00Z"))
            store.append("u1", "turn", """{"q":"b"}""", Instant.parse("2026-06-17T09:01:00Z"))

            val tampered = store.all().toMutableList()
            tampered[0] = tampered[0].copy(payloadJson = """{"q":"HACKED"}""")
            verifyChain(tampered, signer) shouldBe false
        }

        "a forged signature is rejected" {
            val signer = Ed25519Signer()
            val other = Ed25519Signer()
            val store = InMemoryAuditStore(signer)
            store.append("u1", "turn", """{"q":"a"}""", Instant.parse("2026-06-17T09:00:00Z"))
            // Verifying against a different key fails.
            verifyChain(store.all(), other) shouldBe false
        }

        "a deleted middle row (seq gap) breaks the chain" {
            val signer = Ed25519Signer()
            val store = InMemoryAuditStore(signer)
            store.append("u1", "turn", """{"q":"a"}""", Instant.parse("2026-06-17T09:00:00Z"))
            store.append("u1", "turn", """{"q":"b"}""", Instant.parse("2026-06-17T09:01:00Z"))
            store.append("u1", "turn", """{"q":"c"}""", Instant.parse("2026-06-17T09:02:00Z"))

            // Drop the middle row: seq becomes 1,3 — a gap the verifier must catch.
            val withGap = store.all().filter { it.seq != 2L }
            verifyChain(withGap, signer) shouldBe false
        }

        "the self_hash is canonical — key order / whitespace independent" {
            val signer = Ed25519Signer()
            val a = InMemoryAuditStore(signer)
            val b = InMemoryAuditStore(signer)
            // Same content, different key order + whitespace → identical canonical hash.
            val rowA = a.append("u1", "turn", """{"agentId":"golem-v2","status":"done"}""", Instant.EPOCH)
            val rowB = b.append("u1", "turn", """{ "status":"done" , "agentId":"golem-v2" }""", Instant.EPOCH)
            rowA.selfHash shouldBe rowB.selfHash
            rowA.payloadJson shouldBe rowB.payloadJson
        }

        // --- verifySegment: the /v1/audit/verify path (PD-8), with neighbour anchoring ---

        // Three monthly segments: 2026-04 (seq 1..2), 2026-05 (seq 3..4), 2026-06 (seq 5..6).
        fun threeSegments(signer: Ed25519Signer): List<AuditRecord> {
            val store = InMemoryAuditStore(signer)

            fun ts(month: Int) = Instant.parse("2026-%02d-15T12:00:00Z".format(month))
            store.append("u1", "turn", """{"q":"a"}""", ts(4))
            store.append("u1", "turn", """{"q":"b"}""", ts(4))
            store.append("u1", "turn", """{"q":"c"}""", ts(5))
            store.append("u1", "turn", """{"q":"d"}""", ts(5))
            store.append("u1", "turn", """{"q":"e"}""", ts(6))
            store.append("u1", "turn", """{"q":"f"}""", ts(6))
            return store.all()
        }

        fun seg(
            rows: List<AuditRecord>,
            s: String,
        ) = rows.filter { it.segment == s }

        fun prior(
            rows: List<AuditRecord>,
            s: String,
        ) = seg(rows, s).firstOrNull()?.let { f -> rows.lastOrNull { it.seq < f.seq } }

        fun next(
            rows: List<AuditRecord>,
            s: String,
        ) = seg(rows, s).lastOrNull()?.let { l -> rows.firstOrNull { it.seq > l.seq } }

        "verifySegment accepts each intact segment (with its neighbour anchors)" {
            val signer = Ed25519Signer()
            val rows = threeSegments(signer)
            for (s in listOf("2026-04", "2026-05", "2026-06")) {
                verifySegment(seg(rows, s), signer, prior(rows, s), next(rows, s)).ok shouldBe true
            }
        }

        "verifySegment detects a mutated payload inside the segment" {
            val signer = Ed25519Signer()
            val rows = threeSegments(signer).toMutableList()
            rows[2] = rows[2].copy(payloadJson = """{"q":"TAMPERED"}""") // seq 3, first of 2026-05
            val r = verifySegment(seg(rows, "2026-05"), signer, prior(rows, "2026-05"), next(rows, "2026-05"))
            r.ok shouldBe false
            r.brokenAtSeq shouldBe 3L
        }

        "verifySegment detects a deleted TRAILING row of a non-final segment via the next anchor" {
            val signer = Ed25519Signer()
            val full = threeSegments(signer)
            val rows = full.filterNot { it.seq == 4L } // drop last of 2026-05
            // Without the next-anchor this truncation would pass (chain 3 still links clean).
            verifySegment(seg(rows, "2026-05"), signer, prior(full, "2026-05"), next(full, "2026-05")).ok shouldBe false
        }

        "verifySegment detects a deleted LEADING row of a segment via the prior anchor" {
            val signer = Ed25519Signer()
            val full = threeSegments(signer)
            val rows = full.filterNot { it.seq == 5L } // drop first of 2026-06
            verifySegment(seg(rows, "2026-06"), signer, prior(full, "2026-06"), next(full, "2026-06")).ok shouldBe false
        }

        "verifySegment detects a forged signature" {
            val signer = Ed25519Signer()
            val rows = threeSegments(signer).toMutableList()
            rows[5] = rows[5].copy(sig = "AAAA") // seq 6
            verifySegment(seg(rows, "2026-06"), signer, prior(rows, "2026-06"), next(rows, "2026-06")).ok shouldBe false
        }
    })
