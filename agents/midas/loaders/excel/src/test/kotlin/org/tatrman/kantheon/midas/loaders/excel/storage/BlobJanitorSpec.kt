package org.tatrman.kantheon.midas.loaders.excel.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * Stage 1.5 T7 — the retention sweep deletes blobs older than the TTL and keeps
 * fresh ones (the `upload_blob_ref` cleanup, contracts §4.1 / plan T7).
 */
class BlobJanitorSpec :
    StringSpec({

        "sweep deletes blobs older than the TTL and keeps recent ones" {
            val root = tempdir()
            val store = FsBlobStore(root)
            store.put("t/p/alpha/old.xlsx", byteArrayOf(1, 2, 3))
            store.put("t/p/alpha/fresh.xlsx", byteArrayOf(4, 5, 6))

            // Age the "old" blob two days into the past.
            java.io.File(root, "t/p/alpha/old.xlsx").setLastModified(
                Instant.now().minus(Duration.ofDays(2)).toEpochMilli(),
            )

            val janitor = BlobJanitor(store, ttl = Duration.ofHours(24), interval = Duration.ofHours(1))
            janitor.sweep() shouldBe 1

            store.get("t/p/alpha/old.xlsx") shouldBe null
            (store.get("t/p/alpha/fresh.xlsx") != null) shouldBe true
        }

        "sweep is a no-op when nothing is stale" {
            val store = FsBlobStore(tempdir())
            store.put("t/p/alpha/fresh.xlsx", byteArrayOf(1))
            BlobJanitor(store, ttl = Duration.ofHours(24), interval = Duration.ofHours(1)).sweep() shouldBe 0
        }
    })
