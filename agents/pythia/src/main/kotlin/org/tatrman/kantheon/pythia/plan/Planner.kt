package org.tatrman.kantheon.pythia.plan

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.resolve.HandoffAnchor
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.ResolutionResult

/** Outcome of the bounded compose→validate→retry loop. */
sealed interface PlanResult {
    data class Drafted(
        val plan: PlanDag,
    ) : PlanResult

    data class Halt(
        val reason: String,
    ) : PlanResult
}

/**
 * The planning loop (Stage 2.1 T4): compose a [PlanDag], validate it, and on any
 * decode/validation error re-prompt with the errors as feedback — bounded at
 * [maxAttempts] (default 3), then HALT with a Rule-6 explanation.
 */
class Planner(
    private val composer: PlanComposer,
    private val validator: PlanValidator,
    private val maxAttempts: Int = 3,
    /** Master-of-Golems Shem reads (Stage 5.1); null → no domain context injected. */
    private val shemReader: ShemReader? = null,
) {
    private val log = LoggerFactory.getLogger(Planner::class.java)

    suspend fun plan(
        resolution: ResolutionResult,
        anchor: HandoffAnchor,
        constraints: Constraints,
        locale: String,
        capabilities: List<String>,
    ): PlanResult {
        // Master-of-Golems: pull the relevant Golems' Shem context once (relevance =
        // area_entities ∩ resolved entities) and inject it into every planner attempt.
        val shems =
            shemReader?.let { reader ->
                val entities =
                    resolution.resolvedIntent.entitiesList
                        .map { it.entityType }
                        .filter { it.isNotBlank() }
                reader.render(reader.relevantShems(entities))
            } ?: ""
        var feedback: String? = null
        repeat(maxAttempts) { attempt ->
            val context =
                PlanContext(
                    locale = locale,
                    question = resolution.resolvedIntent.resolvedParamsJson,
                    intent = resolution.resolvedIntent.kind.name,
                    capabilities = capabilities,
                    anchor = if (anchor.isPresent) "queryRef=${anchor.queryRef} sql=${anchor.sql}" else "",
                    feedback = feedback,
                    shems = shems,
                )
            val plan =
                try {
                    composer.compose(context)
                } catch (e: PlanDecodeException) {
                    feedback =
                        "Your previous output was invalid: ${e.message}. Return ONLY a valid PlanDag JSON object."
                    log.info("plan attempt {} decode-failed: {}", attempt + 1, e.message)
                    return@repeat
                }
            val errors = validator.validate(plan, constraints)
            if (errors.isEmpty()) return PlanResult.Drafted(plan)
            feedback =
                "Validation errors: ${errors.joinToString("; ")}. Fix them and return a valid PlanDag JSON object."
            log.info("plan attempt {} invalid: {}", attempt + 1, errors)
        }
        return PlanResult.Halt("plan validation failed after $maxAttempts attempts")
    }
}
