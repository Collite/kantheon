package org.tatrman.kantheon.pythia.evaluate

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.plan.Prompts
import org.tatrman.kantheon.pythia.plan.PythiaModels
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import java.util.UUID

/** The evaluated verdict for a hypothesis. */
data class EvaluatedHypothesis(
    val hypId: String,
    val status: HypStatus,
    val confidence: Double,
    val rationale: String,
)

/**
 * Hypothesis evaluation. **Rules-first stays primary** (Stage 2.3 predicates); a
 * NON_APPLICABLE or absent predicate falls back to a **CHEAP-tier** structured
 * verdict (Stage 3.1 T1) only when an [executor] is wired — the LLM never fires
 * when a predicate decides. Emits the `hypothesis_*` events and rolls each
 * terminal status into `pythia_hypotheses_total{terminal_status}` (architecture §8).
 */
class HypothesisEvaluator(
    private val emitter: EventEmitter,
    private val executor: PromptExecutor? = null,
    private val prompts: Prompts = Prompts(),
    private val metrics: MeterRegistry? = null,
    private val locale: String = "en",
) {
    suspend fun evaluate(
        investigationId: UUID,
        hypotheses: List<Hypothesis>,
        rowsByStep: Map<String, JsonArray>,
    ): List<EvaluatedHypothesis> =
        hypotheses.map { hyp ->
            emitter.emit(investigationId, Events.hypothesisUnderTest(hyp.id, hyp.testStepIdsList))
            val evaluated = evaluateOne(hyp, rowsByStep)
            emitVerdict(investigationId, hyp, evaluated)
            metrics?.counter("pythia_hypotheses_total", "terminal_status", evaluated.status.name)?.increment()
            evaluated
        }

    private suspend fun evaluateOne(
        hyp: Hypothesis,
        rowsByStep: Map<String, JsonArray>,
    ): EvaluatedHypothesis {
        val rows = hyp.testStepIdsList.firstNotNullOfOrNull { rowsByStep[it] } ?: JsonArray(emptyList())
        if (hyp.hasPredicate()) {
            when (PredicateEvaluator.evaluate(hyp.predicate, rows)) {
                PredicateVerdict.PASS ->
                    return EvaluatedHypothesis(
                        hyp.id,
                        HypStatus.HYP_SUPPORTED,
                        0.9,
                        "predicate ${hyp.predicate.kind.name} passed",
                    )
                PredicateVerdict.FAIL ->
                    return EvaluatedHypothesis(
                        hyp.id,
                        HypStatus.HYP_REFUTED,
                        0.1,
                        "predicate ${hyp.predicate.kind.name} failed",
                    )
                PredicateVerdict.NON_APPLICABLE -> {} // fall through to the CHEAP fallback
            }
        }
        // No predicate, or NON_APPLICABLE → CHEAP-tier verdict when wired; else INCONCLUSIVE.
        return executor?.let { cheapVerdict(hyp, rows, it) }
            ?: EvaluatedHypothesis(
                hyp.id,
                HypStatus.HYP_INCONCLUSIVE,
                0.5,
                "no applicable predicate (no LLM fallback wired)",
            )
    }

    private suspend fun cheapVerdict(
        hyp: Hypothesis,
        rows: JsonArray,
        exec: PromptExecutor,
    ): EvaluatedHypothesis {
        val template = prompts.load(locale, "evaluator")
        val user = Prompts.substitute(template, mapOf("statement" to hyp.statement, "data" to rows.toString()))
        val p =
            prompt("pythia-eval") {
                system(
                    "You judge whether the data supports the hypothesis. Reply with a JSON object {verdict, confidence, rationale}.",
                )
                user(user)
            }
        val reply =
            exec
                .execute(p, PythiaModels.Cheap, emptyList())
                .filterIsInstance<Message.Assistant>()
                .joinToString("\n") { it.content }
        val verdict = VerdictCodec.decode(reply)
        return EvaluatedHypothesis(
            hyp.id,
            verdict.status,
            verdict.confidence,
            verdict.rationale.ifBlank {
                "CHEAP-tier verdict"
            },
        )
    }

    private fun emitVerdict(
        investigationId: UUID,
        hyp: Hypothesis,
        evaluated: EvaluatedHypothesis,
    ) {
        val event =
            when (evaluated.status) {
                HypStatus.HYP_SUPPORTED ->
                    Events.hypothesisSupported(hyp.id, hyp.testStepIdsList, evaluated.confidence)
                HypStatus.HYP_REFUTED ->
                    Events.hypothesisRefuted(hyp.id, hyp.testStepIdsList.firstOrNull() ?: "", evaluated.rationale)
                else -> Events.hypothesisInconclusive(hyp.id, evaluated.rationale)
            }
        emitter.emit(investigationId, event)
    }
}
