package org.tatrman.kantheon.pythia.evaluate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.Predicate
import java.util.UUID

/**
 * Stage 2.3 T4 — rules-first hypothesis evaluation: predicate PASS → SUPPORTED,
 * FAIL → REFUTED, NON_APPLICABLE / no-predicate → INCONCLUSIVE; the hypothesis_*
 * events are emitted.
 */
class HypothesisEvaluatorSpec :
    StringSpec({

        fun rows(s: String) = Json.parseToJsonElement(s) as JsonArray

        fun hyp(
            id: String,
            predicate: Predicate? = null,
            steps: List<String> = listOf("S1"),
        ) = Hypothesis
            .newBuilder()
            .setId(id)
            .addAllTestStepIds(steps)
            .apply { predicate?.let { setPredicate(it) } }
            .build()

        fun harness(): Pair<HypothesisEvaluator, EventRepository> {
            val events = InMemoryEventRepository()
            return HypothesisEvaluator(EventEmitter(events, RecordingNatsPublisher())) to events
        }

        val id = UUID.randomUUID()

        "a passing predicate marks the hypothesis SUPPORTED + emits hypothesis_supported" {
            val (evaluator, events) = harness()
            val p =
                Predicate
                    .newBuilder()
                    .setKind(Predicate.Kind.ROW_COUNT_GT)
                    .setThreshold(1.0)
                    .build()
            val verdicts = evaluator.evaluate(id, listOf(hyp("H1", p)), mapOf("S1" to rows("""[{},{},{}]""")))
            verdicts.single().status shouldBe HypStatus.HYP_SUPPORTED
            val kinds = events.replay(id, 0L).map { it.kind }
            kinds.contains("HYPOTHESIS_UNDER_TEST") shouldBe true
            kinds.contains("HYPOTHESIS_SUPPORTED") shouldBe true
        }

        "a failing predicate marks REFUTED" {
            val (evaluator, _) = harness()
            val p =
                Predicate
                    .newBuilder()
                    .setKind(Predicate.Kind.ROW_COUNT_GT)
                    .setThreshold(5.0)
                    .build()
            evaluator.evaluate(id, listOf(hyp("H1", p)), mapOf("S1" to rows("""[{}]"""))).single().status shouldBe
                HypStatus.HYP_REFUTED
        }

        "a hypothesis with no predicate is INCONCLUSIVE (CHEAP fallback is Phase 3)" {
            val (evaluator, _) = harness()
            evaluator.evaluate(id, listOf(hyp("H1")), emptyMap()).single().status shouldBe HypStatus.HYP_INCONCLUSIVE
        }

        "a non-applicable predicate is INCONCLUSIVE" {
            val (evaluator, _) = harness()
            val p =
                Predicate
                    .newBuilder()
                    .setKind(Predicate.Kind.METRIC_DELTA_RATIO)
                    .setThreshold(-0.05)
                    .setParametersJson("""{"column":"missing"}""")
                    .build()
            evaluator
                .evaluate(
                    id,
                    listOf(hyp("H1", p)),
                    mapOf("S1" to rows("""[{"x":1},{"x":2}]""")),
                ).single()
                .status shouldBe
                HypStatus.HYP_INCONCLUSIVE
        }
    })
