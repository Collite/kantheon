package org.tatrman.kantheon.iris.protocol.model

import org.tatrman.kantheon.iris.protocol.config.ProtocolConfig
import org.tatrman.kantheon.iris.protocol.record.SchemaVersion
import org.tatrman.kantheon.iris.protocol.sections.ErrorsSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.ExecutionSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.HeaderSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.LlmCallsSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.ParticipantsSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.PlanSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.QuerySectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.ReceiptsSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.ResolutionSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.SectionInput
import org.tatrman.kantheon.iris.protocol.sections.SectionRegistry
import org.tatrman.kantheon.iris.protocol.sections.SecuritySectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.ServiceLogsSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.SqlSectionBuilder
import org.tatrman.kantheon.iris.protocol.sections.TurnFacts
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.protocol.v1.ProtocolDocument
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.ProtocolTurn
import org.tatrman.kantheon.protocol.v1.Scope
import org.tatrman.kantheon.protocol.v1.SessionHeader

/**
 * Assembles a [ProtocolDocument] from records + canned sources (architecture §3.1).
 *
 * Pure: no I/O, no clock, no randomness — `protocolId` and `generatedAt` are
 * supplied by the caller. That is what lets the golden-fixture corpus (PT-22)
 * compare a whole document byte-for-byte; a builder that stamped its own
 * timestamp could only ever be tested field by field.
 *
 * Sections are emitted in [SectionRegistry] order and **receipts are appended
 * here, outside the spine loop**, so no builder and no profile can move or drop
 * them (PT-13).
 */
object DocumentBuilder {
    data class Request(
        val protocolId: String,
        val sessionId: String,
        val scope: Scope,
        val generatedAt: String,
        val turns: List<TurnInput>,
        val sources: ProtocolSources,
        val config: ProtocolConfig = ProtocolConfig(),
        val profileName: String? = null,
        val turnCountTotal: Int = turns.size,
        val sessionCreatedAt: String = "",
        val estate: String = "kantheon",
        val assemblerVersion: String = "1.0",
    )

    /** One turn's inputs: the row's own facts plus its record (absent if never captured). */
    data class TurnInput(
        val facts: TurnFacts,
        val record: ProtocolRecord = ProtocolRecord.getDefaultInstance(),
    )

    fun build(req: Request): ProtocolDocument {
        val profile = req.config.profile(req.profileName)
        val sessionScope = req.scope.kindCase != Scope.KindCase.LAST_TURN

        val turns =
            req.turns.map { t ->
                val input =
                    SectionInput(
                        record = t.record,
                        sources = req.sources,
                        turn = t.facts,
                        profile = profile,
                        caps = req.config.caps,
                    )
                val sections =
                    listOf(
                        HeaderSectionBuilder.build(input),
                        ResolutionSectionBuilder.build(input),
                        LlmCallsSectionBuilder.build(input),
                        QuerySectionBuilder.build(input),
                        PlanSectionBuilder.build(input),
                        SqlSectionBuilder.build(input),
                        SecuritySectionBuilder.build(input),
                        ExecutionSectionBuilder.build(input),
                        ServiceLogsSectionBuilder.build(input),
                        ErrorsSectionBuilder.build(input),
                    )

                // The spine is the registry's, not the list literal's — a builder added
                // out of order above would still render in contract order, and a missing
                // one shows up as a failing SectionRegistrySpec rather than a silent gap.
                val byKey = sections.associateBy { it.key }
                ProtocolTurn
                    .newBuilder()
                    .setTurnId(t.facts.turnId)
                    .setSeq(t.facts.seq)
                    .addAllSections(SectionRegistry.spineFor(sessionScope).mapNotNull { byKey[it] })
                    .build()
            }

        val records = req.turns.map { it.record }.filter { it != ProtocolRecord.getDefaultInstance() }

        // Participants is built ONCE for the whole document (A-5). It needs a
        // SectionInput for its verbosity resolution, so it borrows the first turn's
        // — the profile and caps are document-wide, and the section reads neither
        // the record nor the sources.
        val participants =
            req.turns.firstOrNull()?.let { first ->
                ParticipantsSectionBuilder.build(
                    req.turns.map { it.facts },
                    SectionInput(
                        record = first.record,
                        sources = req.sources,
                        turn = first.facts,
                        profile = profile,
                        caps = req.config.caps,
                    ),
                )
            }

        return ProtocolDocument
            .newBuilder()
            .setProtocolId(req.protocolId)
            .setSessionId(req.sessionId)
            .setScope(req.scope)
            .setHeader(header(req, profileTitle(req)))
            .addAllTurns(turns)
            .apply { if (sessionScope && participants != null) setParticipants(participants) }
            .setReceipts(
                ReceiptsSectionBuilder.build(
                    records = records,
                    sources = req.sources,
                    profile = profile,
                    estate = req.estate,
                    assemblerVersion = req.assemblerVersion,
                ),
            ).setSchemaVersion(SchemaVersion.CURRENT)
            .setGeneratedAt(req.generatedAt)
            .build()
    }

    private fun header(
        req: Request,
        title: String,
    ): SessionHeader =
        SessionHeader
            .newBuilder()
            .setTitle(title)
            .setUserId(
                req.turns
                    .firstOrNull()
                    ?.facts
                    ?.userId
                    .orEmpty(),
            ).setTenantId(
                req.turns
                    .firstOrNull()
                    ?.facts
                    ?.tenantId
                    .orEmpty(),
            ).addAllAgentIds(
                req.turns
                    .map { it.facts.agentId }
                    .filter { it.isNotBlank() }
                    .distinct(),
            ).setSessionCreatedAt(req.sessionCreatedAt)
            .setTurnCountInScope(req.turns.size)
            .setTurnCountTotal(req.turnCountTotal)
            .build()

    /** S-8: `Protocol — <session title> — <scope>`. */
    private fun profileTitle(req: Request): String {
        val sessionTitle =
            req.turns
                .firstOrNull()
                ?.facts
                ?.question
                ?.take(60)
                .orEmpty()
                .ifBlank { "session" }
        val scope =
            when (req.scope.kindCase) {
                Scope.KindCase.LAST_TURN -> "last turn"
                Scope.KindCase.WHOLE_SESSION -> "whole session"
                Scope.KindCase.LAST_N -> "last ${req.scope.lastN} turns"
                else -> "unspecified scope"
            }
        return "Protocol — $sessionTitle — $scope"
    }
}
