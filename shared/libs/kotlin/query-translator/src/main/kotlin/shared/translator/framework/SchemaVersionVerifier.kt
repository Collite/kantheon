package shared.translator.framework

import org.tatrman.plan.v1.PipelineContext

/**
 * Schema-version verifier.
 *
 * The Validator passes a [PipelineContext] separately from the
 * [org.tatrman.plan.v1.PlanNode] it received; this helper checks the
 * `model_version` carried in the context against the metadata service's
 * current model version. It NEVER fails by itself — it returns a
 * [VerificationResult] and the caller (typically the Validator) decides what
 * to do (log + proceed for minor differences, fail with
 * `model_version_mismatch` for major ones).
 */
object SchemaVersionVerifier {
    fun verifyContext(
        context: PipelineContext,
        currentVersion: String,
    ): VerificationResult {
        val incoming = context.modelVersion
        return when {
            incoming.isEmpty() -> VerificationResult.Missing(currentVersion)
            incoming == currentVersion -> VerificationResult.Ok
            else -> VerificationResult.MismatchWarn(expected = currentVersion, got = incoming)
        }
    }
}

sealed interface VerificationResult {
    data object Ok : VerificationResult

    data class Missing(
        val current: String,
    ) : VerificationResult

    data class MismatchWarn(
        val expected: String,
        val got: String,
    ) : VerificationResult
}
