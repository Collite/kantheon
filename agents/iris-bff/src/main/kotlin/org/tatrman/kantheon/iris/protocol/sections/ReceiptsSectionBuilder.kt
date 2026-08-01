package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.ReceiptsSection
import org.tatrman.kantheon.protocol.v1.SourceReceipt

/**
 * The receipts (PT-13/S-6) — **mandatory, never configurable, always last**.
 *
 * This is what makes a degraded protocol honest rather than merely thin. Without
 * it, a document missing its LLM section and a document whose LLM section was
 * switched off look identical to the reader, and neither can be told from one
 * where the gateway was simply down. Every source that *could* have contributed
 * gets a line, including the ones that contributed nothing and why.
 *
 * Note it takes no verbosity: there is no profile value to read, because
 * [ProtocolProfile.verbosityFor] answers `FULL` for the receipts key no matter
 * what any profile says.
 */
object ReceiptsSectionBuilder {
    const val KEY: String = SectionRegistry.RECEIPTS

    /** The `records` source is the BFF's own table — always consulted, never remote. */
    private const val RECORDS = "records"

    /** Not a source: facts about what the document itself covers (A-9, max-turns cap). */
    private const val SCOPE = "scope"

    fun build(
        records: List<ProtocolRecord>,
        sources: ProtocolSources,
        profile: ProtocolProfile,
        estate: String,
        assemblerVersion: String,
        /** Turn seq → id, in document order; used for the A-9 scope receipt. */
        turnIds: List<String> = emptyList(),
        turnsDroppedByCap: Int = 0,
    ): ReceiptsSection {
        val b =
            ReceiptsSection
                .newBuilder()
                .setProfileName(profile.name)
                .setGeneratedBy("iris-bff/$assemblerVersion $estate")

        // The record store first: it is the spine, so its shortfall explains every
        // downstream one. Zero rows is degraded, not ok — a scope with turns but no
        // records means capture was not running, which the reader must be told.
        b.addSources(
            SourceReceipt
                .newBuilder()
                .setSource(RECORDS)
                .setStatus(if (records.isEmpty()) SourceStatus.DEGRADED.wire else SourceStatus.OK.wire)
                .setDetail(
                    if (records.isEmpty()) {
                        "no protocol record rows for this scope — capture may not have been running"
                    } else {
                        "${records.size} record row(s)"
                    },
                ),
        )

        // **Which turn the federated sources describe (A-9).** v1 fetches them once, for
        // the anchor turn; every other turn's source-backed sections degrade. Before this
        // line existed the assembler's own KDoc claimed "the receipts say how much was
        // consulted" and nothing did — the reader had no way to learn that twelve of
        // thirteen turns were never queried. Only emitted for multi-turn documents,
        // where the distinction exists.
        if (turnIds.size > 1) {
            val at = turnIds.indexOf(sources.anchorTurnId)
            b.addSources(
                SourceReceipt
                    .newBuilder()
                    .setSource(SCOPE)
                    .setStatus(SourceStatus.PARTIAL.wire)
                    .setDetail(
                        if (at >= 0) {
                            "federated sources consulted for turn ${at + 1} of ${turnIds.size} " +
                                "(v1 fetches per document, not per turn)"
                        } else {
                            "no turn in scope carried a record; no federated source was consulted"
                        },
                    ),
            )
        }

        // A capped scope is a shortfall like any other, and belongs where the reader
        // already looks for shortfalls rather than in a log line nobody reads.
        if (turnsDroppedByCap > 0) {
            b.addSources(
                SourceReceipt
                    .newBuilder()
                    .setSource(SCOPE)
                    .setStatus(SourceStatus.PARTIAL.wire)
                    .setDetail("$turnsDroppedByCap older turn(s) dropped by the max-turns cap"),
            )
        }

        sources.statuses().forEach { (name, status) ->
            b.addSources(
                SourceReceipt
                    .newBuilder()
                    .setSource(name)
                    .setStatus(status.wire)
                    .setDetail(detailFor(name, sources)),
            )
        }

        // Capture gaps are a source-level fact too (A-1): the reader needs to know a
        // capture was structurally unavailable, not merely empty.
        records
            .flatMap { it.pointers.captureGapsList }
            .distinctBy { it.capture }
            .forEach { gap ->
                b.addSources(
                    SourceReceipt
                        .newBuilder()
                        .setSource("capture:${gap.capture}")
                        .setStatus(SourceStatus.DEGRADED.wire)
                        .setDetail(gap.reason),
                )
            }

        return b.build()
    }

    private fun detailFor(
        name: String,
        s: ProtocolSources,
    ): String =
        when (name) {
            "llm-gateway" -> s.gateway.detail.ifBlank { "${s.gateway.items.size} call row(s)" }
            "loki" -> s.loki.detail.ifBlank { "${s.loki.groups.sumOf { g -> g.lines.size }} line(s)" }
            "tempo" -> s.tempo.detail.ifBlank { "${s.tempo.spans.size} span(s)" }
            // The blank-detail fallback has to follow the STATUS, not guess. With the
            // client unwired the source is `skipped-by-config` with no detail, and the
            // old fallback answered "plan carried" — a claim about the plan on a line
            // that means "we never looked" (review-079 R13).
            //
            // The `reconstructed` and "plan carried" arms that used to sit here were
            // UNREACHABLE: every path that sets those also sets a detail, so `ifBlank`
            // never fired. Dead reasoning in a receipts builder is worse than none —
            // it reads as a case somebody covered (review-080 R8).
            "translate-explain" ->
                s.explain.detail.ifBlank {
                    if (s.explain.status == SourceStatus.SKIPPED_BY_CONFIG) "not wired in this deployment" else ""
                }
            else -> ""
        }
}
