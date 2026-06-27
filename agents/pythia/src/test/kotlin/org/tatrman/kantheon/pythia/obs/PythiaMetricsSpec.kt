package org.tatrman.kantheon.pythia.obs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * Stage 5.3 T4 — the observability metric set emits under the architecture §8 names
 * with the documented tag dimensions (asserted against a SimpleMeterRegistry; the
 * live Prometheus registry is the integration concern).
 */
class PythiaMetricsSpec :
    StringSpec({

        "the counters + histograms register under their architecture §8 names + tags" {
            val reg = SimpleMeterRegistry()
            val m = PythiaMetrics(reg)

            m.investigationTerminal("DONE", "RCA", "IRIS")
            m.investigationDuration("RCA", 8200)
            m.step("query", "completed")
            m.batchParallelism(3)
            m.llmCall("strong", "planning", 0.12)
            m.budgetHalt("90")
            m.planRevision("PRUNE")
            m.awaitingEntered("AWAITING_PLAN_APPROVAL")
            m.awaitingDuration("AWAITING_PLAN_APPROVAL", 1200)
            m.handleMaterialisation("pg_snapshot", "worker_df")
            m.checkpointBytes(4096)

            reg
                .get("pythia_investigations_total")
                .tags("status", "DONE", "intent_kind", "RCA", "caller_kind", "IRIS")
                .counter()
                .count() shouldBe 1.0
            reg
                .get("pythia_steps_total")
                .tags("node_kind", "query", "status", "completed")
                .counter()
                .count() shouldBe
                1.0
            reg
                .get("pythia_llm_cost_usd_total")
                .tag("task_kind", "planning")
                .counter()
                .count() shouldBe 0.12
            reg
                .get("pythia_awaiting_total")
                .tag("state", "AWAITING_PLAN_APPROVAL")
                .counter()
                .count() shouldBe 1.0
            reg
                .get("pythia_handle_materialisations_total")
                .tags("from", "pg_snapshot", "to", "worker_df")
                .counter()
                .count() shouldBe 1.0
        }
    })
