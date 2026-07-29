package org.tatrman.kantheon.llmguard

import org.tatrman.validator.spi.Door
import org.tatrman.validator.spi.PlanValidatorPlugin
import org.tatrman.validator.spi.PrincipalInfo
import org.tatrman.validator.spi.ValidationContext
import org.tatrman.validator.spi.Verdict

/**
 * The kantheon LLM-guard — a semantic last-mile review of a QUERY plan, shipped as a C-5-i plugin on the
 * OPEN `org.tatrman:ttr-validator-spi` (contracts §9). kantheon (a separate consumer) extending the
 * platform's validation via the open plugin contract is the P2 clarification made real.
 *
 * GREENFIELD (PL-P4.S2.T5) against the SPI — deliberately NOT the ai-platform `LlmGuard` stage (coupled to
 * that validator's internals + off-limits). **QUERY-only:** a PROGRAM context passes untouched (a program
 * island carries no `plan.v1` PlanNode to judge). The judgment is delegated to a [SemanticJudge] (a fake in
 * tests); the live gateway wiring (`ttr-llm-client` → the ttr-llm-gateway) is the ⑥ adoption step.
 */
class LlmGuardPlugin(
    private val judge: SemanticJudge,
    private val failurePosture: FailurePosture = FailurePosture.FAIL_CLOSED,
) : PlanValidatorPlugin {
    /** ServiceLoader no-arg constructor: the gateway-backed judge from env (skeleton until ⑥). */
    constructor() : this(GatewaySemanticJudge.fromEnv())

    override val id: String = "kantheon-llm-guard"
    override val spiVersion: Int = PlanValidatorPlugin.SPI_VERSION

    override fun validate(ctx: ValidationContext): Verdict {
        if (ctx.door != Door.QUERY) return Verdict.Pass // QUERY-only; a PROGRAM plan has nothing to judge.
        return when (val review = judge.review(ctx.plan, ctx.principal)) {
            SemanticReview.Approve -> Verdict.Pass
            is SemanticReview.Caveat -> Verdict.Advise("llm_guard_caveat", review.reason)
            is SemanticReview.Reject -> Verdict.Deny("llm_guard_rejected", review.reason)
            SemanticReview.Unavailable ->
                when (failurePosture) {
                    FailurePosture.FAIL_CLOSED ->
                        Verdict.Deny(
                            "llm_guard_unavailable",
                            "semantic guard unavailable — fail-closed",
                        )
                    FailurePosture.FAIL_OPEN ->
                        Verdict.Advise(
                            "llm_guard_unavailable",
                            "semantic guard unavailable — fail-open",
                        )
                }
        }
    }
}

/** Failure posture when the judge can't render a decision (gateway down / unconfigured). */
enum class FailurePosture { FAIL_CLOSED, FAIL_OPEN }

/** The semantic-judgment port. The live impl calls the ttr-llm-gateway; tests supply a fake. */
fun interface SemanticJudge {
    fun review(
        plan: ByteArray,
        principal: PrincipalInfo,
    ): SemanticReview
}

/** The model's verdict on a plan. */
sealed interface SemanticReview {
    data object Approve : SemanticReview

    data class Caveat(
        val reason: String,
    ) : SemanticReview

    data class Reject(
        val reason: String,
    ) : SemanticReview

    data object Unavailable : SemanticReview
}

/**
 * The gateway-backed judge. SKELETON at S2.T5 — with no gateway configured it returns
 * [SemanticReview.Unavailable] (so the plugin applies its [FailurePosture]). The live call
 * (`ttr-llm-client` → the ttr-llm-gateway: send the plan + principal, map approve/caveat/reject, treat a
 * network error as Unavailable) is the ⑥ adoption step.
 */
class GatewaySemanticJudge private constructor(
    private val gatewayUrl: String?,
) : SemanticJudge {
    override fun review(
        plan: ByteArray,
        principal: PrincipalInfo,
    ): SemanticReview {
        if (gatewayUrl.isNullOrBlank()) return SemanticReview.Unavailable
        // PL-P6: live proof at adoption — wire ttr-llm-client to the ttr-llm-gateway here.
        return SemanticReview.Unavailable
    }

    companion object {
        fun fromEnv(): GatewaySemanticJudge = GatewaySemanticJudge(System.getenv("LLM_GUARD_GATEWAY_URL"))
    }
}
