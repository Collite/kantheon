package org.tatrman.kantheon.iris.protocol.sources

import org.tatrman.translate.v1.ExplainRequest
import org.tatrman.translate.v1.ExplainResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.TranslateRequest

/**
 * The plan fallback (S-1). When a turn carried no plan, the SQL it *did* carry is
 * handed to translate's `Explain`, whose per-stage artefacts are rendered as
 * the RelPlan text.
 *
 * **Everything from this path is `reconstructed = true`, without exception.**
 * That is enforced by the return type — [ReconstructedPlan] has no way to say
 * otherwise — because the distinction is the whole honesty of the section: a
 * reconstruction is *a* plan for that SQL, not provably the one that ran, and
 * presenting it as the original would be the single most misleading thing the
 * document could do.
 */
class ExplainClient(
    private val enabled: Boolean,
    private val explain: suspend (ExplainRequest) -> ExplainResponse,
) {
    /** A plan recovered after the fact. There is deliberately no `reconstructed = false` case. */
    data class ReconstructedPlan(
        val text: String,
    )

    suspend fun explainSql(
        sql: String,
        dialect: SqlDialect = SqlDialect.SQL_DIALECT_UNSPECIFIED,
    ): SourceOutcome<ReconstructedPlan> {
        if (!enabled) return SourceOutcome.SkippedByConfig()
        if (sql.isBlank()) return SourceOutcome.Degraded("translate-explain: turn carried no SQL to explain")
        return guardSource("translate-explain") {
            val req =
                ExplainRequest
                    .newBuilder()
                    .setTranslate(
                        TranslateRequest
                            .newBuilder()
                            // SQL in, SQL out: Explain is being used purely to walk the
                            // stages, not to change languages. The dialect the turn ran
                            // against is passed through when the record knows it.
                            .setSource(sql)
                            .setSourceLanguage(Language.SQL)
                            .setTargetLanguage(Language.SQL)
                            .setTargetDialect(dialect),
                    ).build()
            val res = explain(req)
            ReconstructedPlan(text = res.stagesList.joinToString("\n\n") { it.render() })
        }
    }

    /** One stage as a labelled block — stage code, timing, then its canonical form. */
    private fun org.tatrman.translate.v1.StageArtifact.render(): String =
        buildString {
            append("── ")
                .append(stageCode)
                .append(" (")
                .append(durationMs)
                .append(" ms)\n")
            append(canonicalForm)
        }

    /** Build the source payload the builders consume; always flags reconstruction. */
    fun toSource(outcome: SourceOutcome<ReconstructedPlan>): ExplainSource =
        when (outcome) {
            is SourceOutcome.Ok ->
                ExplainSource(
                    status = SourceStatus.OK,
                    detail = "plan reconstructed (S-1)",
                    relPlanText = outcome.payload.text,
                    reconstructed = true,
                )

            is SourceOutcome.Degraded ->
                ExplainSource(status = SourceStatus.DEGRADED, detail = outcome.reason)

            is SourceOutcome.SkippedByConfig ->
                ExplainSource(status = SourceStatus.SKIPPED_BY_CONFIG, detail = "translate-explain disabled by config")
        }
}
