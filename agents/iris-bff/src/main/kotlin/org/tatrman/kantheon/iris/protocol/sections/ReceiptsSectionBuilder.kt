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

    fun build(
        records: List<ProtocolRecord>,
        sources: ProtocolSources,
        profile: ProtocolProfile,
        estate: String,
        assemblerVersion: String,
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
            "translate-explain" ->
                s.explain.detail.ifBlank {
                    if (s.explain.reconstructed) "plan reconstructed (S-1)" else "plan carried"
                }
            else -> ""
        }
}
