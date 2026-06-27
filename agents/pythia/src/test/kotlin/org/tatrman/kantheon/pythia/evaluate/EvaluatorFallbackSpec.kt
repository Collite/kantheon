package org.tatrman.kantheon.pythia.evaluate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.Predicate
import java.util.UUID

/**
 * Stage 3.1 T1 — the CHEAP-tier evaluator fallback fires only for the gap
 * (no-predicate / NON_APPLICABLE); a predicate that decides never calls the LLM.
 */
class EvaluatorFallbackSpec :
    StringSpec({

        fun rows(s: String) = Json.parseToJsonElement(s) as JsonArray

        fun emitter() = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())

        val id = UUID.randomUUID()

        "a no-predicate hypothesis gets a CHEAP-tier verdict" {
            runTest {
                val exec =
                    ScriptedPromptExecutor(listOf("""{"verdict":"SUPPORTED","confidence":0.8,"rationale":"clear"}"""))
                val evaluator = HypothesisEvaluator(emitter(), executor = exec)
                val hyp =
                    Hypothesis
                        .newBuilder()
                        .setId("H1")
                        .addTestStepIds("S1")
                        .build()
                val verdict = evaluator.evaluate(id, listOf(hyp), mapOf("S1" to rows("""[{"x":1}]"""))).single()
                verdict.status shouldBe HypStatus.HYP_SUPPORTED
                verdict.confidence shouldBe 0.8
                exec.callCount shouldBe 1
            }
        }

        "a predicate-applicable hypothesis does NOT call the LLM (rules-first stays primary)" {
            runTest {
                val exec = ScriptedPromptExecutor(listOf("unused"))
                val p =
                    Predicate
                        .newBuilder()
                        .setKind(Predicate.Kind.ROW_COUNT_GT)
                        .setThreshold(0.0)
                        .build()
                val hyp =
                    Hypothesis
                        .newBuilder()
                        .setId("H1")
                        .setPredicate(p)
                        .addTestStepIds("S1")
                        .build()
                HypothesisEvaluator(emitter(), executor = exec)
                    .evaluate(
                        id,
                        listOf(hyp),
                        mapOf(
                            "S1" to rows("""[{"x":1}]"""),
                        ),
                    ).single()
                exec.callCount shouldBe 0 // zero gateway calls — the predicate decided
            }
        }

        "a NON_APPLICABLE predicate falls back to the CHEAP verdict" {
            runTest {
                val exec = ScriptedPromptExecutor(listOf("""{"verdict":"REFUTED","confidence":0.6,"rationale":"no"}"""))
                val p =
                    Predicate
                        .newBuilder()
                        .setKind(Predicate.Kind.METRIC_DELTA_RATIO)
                        .setThreshold(-0.05)
                        .setParametersJson("""{"column":"missing"}""")
                        .build()
                val hyp =
                    Hypothesis
                        .newBuilder()
                        .setId("H1")
                        .setPredicate(p)
                        .addTestStepIds("S1")
                        .build()
                val verdict =
                    HypothesisEvaluator(emitter(), executor = exec)
                        .evaluate(
                            id,
                            listOf(hyp),
                            mapOf(
                                "S1" to rows("""[{"x":1},{"x":2}]"""),
                            ),
                        ).single()
                verdict.status shouldBe HypStatus.HYP_REFUTED
                exec.callCount shouldBe 1
            }
        }

        "terminal verdicts roll into the pythia_hypotheses_total metric" {
            runTest {
                val registry = SimpleMeterRegistry()
                val p =
                    Predicate
                        .newBuilder()
                        .setKind(Predicate.Kind.ROW_COUNT_GT)
                        .setThreshold(0.0)
                        .build()
                val hyp =
                    Hypothesis
                        .newBuilder()
                        .setId("H1")
                        .setPredicate(p)
                        .addTestStepIds("S1")
                        .build()
                HypothesisEvaluator(emitter(), metrics = registry).evaluate(
                    id,
                    listOf(hyp),
                    mapOf(
                        "S1" to rows("""[{"x":1}]"""),
                    ),
                )
                registry.counter("pythia_hypotheses_total", "terminal_status", "HYP_SUPPORTED").count() shouldBe 1.0
            }
        }
    })
