package org.tatrman.kantheon.pythia.dataplane

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.WorkerKind
import org.tatrman.kantheon.pythia.handles.HandleTable

/**
 * Stage 4.1 T2 — each Charon-backed handle kind resolves to the correct Charon
 * `Location` (charon/contracts.md §7); the two Pythia-internal kinds (LiveQueryRef /
 * PgResultSnapshot) are never sent to Charon and throw on mapping.
 */
class HandleLocationMappingSpec :
    StringSpec({

        val handles = HandleTable()

        "SeaweedArrowBlob → SeaweedBlob (bucket/key parsed from the url)" {
            val h = handles.putSeaweed("h1", url = "pythia-evidence/inv-1/h1.arrow")
            val loc = HandleLocationMapping.toLocation(h)
            loc.kindCase shouldBe Location.KindCase.SEAWEED
            loc.seaweed.bucket shouldBe "pythia-evidence"
            loc.seaweed.key shouldBe "inv-1/h1.arrow"
        }

        "RedisArrowEntry → RedisEntry" {
            val h = handles.putRedis("h2", key = "k:abc")
            val loc = HandleLocationMapping.toLocation(h)
            loc.kindCase shouldBe Location.KindCase.REDIS
            loc.redis.key shouldBe "k:abc"
        }

        "WorkerSessionDF → WorkerSessionDf with the requested worker kind" {
            val h = handles.putWorkerDf("h3", workerPod = "pod-1", sessionId = "s1", dfName = "df1")
            val loc = HandleLocationMapping.toLocation(h, WorkerKind.METIS)
            loc.kindCase shouldBe Location.KindCase.WORKER_DF
            loc.workerDf.sessionId shouldBe "s1"
            loc.workerDf.dfName shouldBe "df1"
            loc.workerDf.workerKind shouldBe WorkerKind.METIS
        }

        "DbTableRef → DbTable (schema.table split)" {
            val h = handles.putDbTable("h4", connection = "erp", table = "dbo.orders")
            val loc = HandleLocationMapping.toLocation(h)
            loc.kindCase shouldBe Location.KindCase.DB_TABLE
            loc.dbTable.connectionId shouldBe "erp"
            loc.dbTable.schema shouldBe "dbo"
            loc.dbTable.table shouldBe "orders"
        }

        "a PgResultSnapshot handle is Pythia-internal and never maps to a Charon Location" {
            val snap =
                handles
                    .putSnapshot(
                        "h5",
                        kotlinx.serialization.json.Json
                            .parseToJsonElement("""[{"id":1}]""") as kotlinx.serialization.json.JsonArray,
                    ).handle
            shouldThrow<IllegalArgumentException> { HandleLocationMapping.toLocation(snap) }
        }

        "a LiveQueryRef handle is Pythia-internal and never maps to a Charon Location" {
            val live = handles.putLiveQuery("h6", "q.x", "{}")
            shouldThrow<IllegalArgumentException> { HandleLocationMapping.toLocation(live) }
        }
    })
