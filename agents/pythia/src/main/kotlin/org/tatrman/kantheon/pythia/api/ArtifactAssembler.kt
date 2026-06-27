package org.tatrman.kantheon.pythia.api

import org.tatrman.kantheon.pythia.persistence.HypothesisRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRecord
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.persistence.StepRepository
import org.tatrman.kantheon.pythia.v1.Conclusion
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationArtifact
import org.tatrman.kantheon.pythia.v1.InvestigationSummary
import org.tatrman.kantheon.pythia.v1.ResolutionResult
import org.tatrman.kantheon.pythia.v1.ResourceUsage
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.StepRecord
import java.time.format.DateTimeFormatter

/**
 * Assembles an [InvestigationArtifact] (contracts §1) from the persisted record +
 * aggregates. Phase 1 fills what the stubs persist (status, resolution/plan
 * snapshots, conclusion, usage); steps/hypotheses populate as Phases 2–3 land.
 */
class ArtifactAssembler(
    private val investigations: InvestigationRepository,
    private val hypotheses: HypothesisRepository,
    private val steps: StepRepository,
) {
    fun assemble(rec: InvestigationRecord): InvestigationArtifact {
        val builder =
            InvestigationArtifact
                .newBuilder()
                .setId(rec.id.toString())
                .setStatus(Status.valueOf(rec.status))
                .setCreatedAt(iso(rec.createdAt))
        rec.parentId?.let { builder.setParentId(it.toString()) }
        rec.finalisedAt?.let { builder.setFinalisedAt(iso(it)) }
        rec.resolutionJson?.let { ProtoJson.parseInto(it, ResolutionResult.newBuilder()).let(builder::setResolution) }
        rec.conclusionJson?.let { ProtoJson.parseInto(it, Conclusion.newBuilder()).let(builder::setConclusion) }
        runCatching { ProtoJson.parseInto(rec.resourceUsageJson, ResourceUsage.newBuilder()).build() }
            .getOrNull()
            ?.let(builder::setResourceUsage)
        hypotheses.findByInvestigation(rec.id).forEach {
            builder.addHypotheses(ProtoJson.parseInto(it.bodyJson, Hypothesis.newBuilder()).build())
        }
        steps.findByInvestigation(rec.id).forEach {
            builder.addSteps(ProtoJson.parseInto(it.bodyJson, StepRecord.newBuilder()).build())
        }
        return builder.build()
    }

    fun summary(rec: InvestigationRecord): InvestigationSummary {
        val caller =
            runCatching {
                ProtoJson
                    .parseInto(
                        rec.callerJson,
                        org.tatrman.kantheon.pythia.v1.Caller
                            .newBuilder(),
                    ).build()
            }.getOrNull()
        val builder =
            InvestigationSummary
                .newBuilder()
                .setId(rec.id.toString())
                .setQuestion(rec.question)
                .setStatus(Status.valueOf(rec.status))
                .setCreatedAt(iso(rec.createdAt))
                .setUpdatedAt(iso(rec.updatedAt))
        caller?.let { builder.setCallerKind(it.kind) }
        runCatching { ProtoJson.parseInto(rec.resourceUsageJson, ResourceUsage.newBuilder()).build() }
            .getOrNull()
            ?.let(builder::setResourceUsage)
        return builder.build()
    }

    /** The owning user of an investigation (PD-8 visibility re-check). */
    fun ownerUserId(rec: InvestigationRecord): String =
        runCatching {
            ProtoJson
                .parseInto(rec.requestJson, Investigation.newBuilder())
                .build()
                .caller.userId
        }.getOrDefault("")

    private fun iso(instant: java.time.Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
}
