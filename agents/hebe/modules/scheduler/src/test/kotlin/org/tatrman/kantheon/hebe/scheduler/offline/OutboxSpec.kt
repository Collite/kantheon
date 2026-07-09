package org.tatrman.kantheon.hebe.scheduler.offline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Outbox idempotency + ordering (P2 Stage 2.5 T3) — the two sharp edges.
 */
class OutboxSpec :
    StringSpec({

        "enqueue is idempotent on the logical key" {
            runTest {
                val outbox = Outbox()
                outbox.enqueue(OutboxItem("turn:r:100", "iris", "p")) shouldBe true
                outbox.enqueue(OutboxItem("turn:r:100", "iris", "p")) shouldBe false // no double-queue
                outbox.pendingCount() shouldBe 1
            }
        }

        "drain sends pending rows and removes them; a second drain is a no-op" {
            runTest {
                val outbox = Outbox()
                outbox.enqueue(OutboxItem("a", "iris", "1"))
                outbox.enqueue(OutboxItem("b", "iris", "2"))
                val sent = mutableListOf<String>()
                outbox
                    .drain {
                        sent.add(it.key)
                        true
                    }.sent shouldBe 2
                outbox.pendingCount() shouldBe 0
                outbox
                    .drain {
                        sent.add(it.key)
                        true
                    }.sent shouldBe 0 // nothing left
                sent shouldBe listOf("a", "b")
            }
        }

        "a send failure leaves the row for retry and preserves per-destination order" {
            runTest {
                val outbox = Outbox()
                outbox.enqueue(OutboxItem("a", "iris", "1"))
                outbox.enqueue(OutboxItem("b", "iris", "2"))
                // First drain: 'a' fails → 'b' is held back (same destination, ordering).
                val r1 = outbox.drain { it.key != "a" }
                r1.sent shouldBe 0
                outbox.pendingCount() shouldBe 2
                // Recovery: now everything sends, in order.
                val order = mutableListOf<String>()
                outbox
                    .drain {
                        order.add(it.key)
                        true
                    }.sent shouldBe 2
                order shouldBe listOf("a", "b")
            }
        }

        "independent destinations are not blocked by each other" {
            runTest {
                val outbox = Outbox()
                outbox.enqueue(OutboxItem("i1", "iris", "x"))
                outbox.enqueue(OutboxItem("t1", "telegram", "y"))
                // iris send fails, telegram succeeds.
                val r = outbox.drain { it.destination == "telegram" }
                r.sent shouldBe 1
                outbox.pendingCount() shouldBe 1 // only the iris row remains
            }
        }
    })
