package org.tatrman.kantheon.iris.protocol.sections

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.iris.protocol.FixtureLoader
import org.tatrman.kantheon.iris.protocol.config.ProtocolProfile
import org.tatrman.kantheon.iris.protocol.record.ProtocolRecorder
import org.tatrman.kantheon.iris.protocol.sources.ProtocolSources
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * Per-builder behaviour (architecture §3.1). The whole-document equality is
 * covered by `GoldenCorpusSpec`; what is asserted here is the three properties
 * every builder must hold individually, because a golden diff tells you
 * *something* changed and these tell you *which rule* broke:
 *
 *  1. it produces the expected payload from real inputs,
 *  2. a missing source degrades it rather than throwing,
 *  3. verbosity is honoured, and `off` yields `SECTION_OFF` with no payload.
 */
class SectionBuildersSpec :
    StringSpec({

        val case = "H1-full"

        fun input(
            sources: ProtocolSources = FixtureLoader.sources(case),
            profile: ProtocolProfile = FixtureLoader.config(case).profile(),
        ): SectionInput =
            SectionInput(
                record = FixtureLoader.records(case).first(),
                sources = sources,
                turn = FixtureLoader.turns(case).first(),
                profile = profile,
                caps = FixtureLoader.config(case).caps,
            )

        /** Every builder, as (key, build-fn) — the table the cases below iterate. */
        val builders: List<Pair<String, (SectionInput) -> Section>> =
            listOf(
                HeaderSectionBuilder.KEY to HeaderSectionBuilder::build,
                ResolutionSectionBuilder.KEY to ResolutionSectionBuilder::build,
                LlmCallsSectionBuilder.KEY to LlmCallsSectionBuilder::build,
                QuerySectionBuilder.KEY to QuerySectionBuilder::build,
                PlanSectionBuilder.KEY to PlanSectionBuilder::build,
                SqlSectionBuilder.KEY to SqlSectionBuilder::build,
                SecuritySectionBuilder.KEY to SecuritySectionBuilder::build,
                ExecutionSectionBuilder.KEY to ExecutionSectionBuilder::build,
                ServiceLogsSectionBuilder.KEY to ServiceLogsSectionBuilder::build,
                ErrorsSectionBuilder.KEY to ErrorsSectionBuilder::build,
            )

        "every builder emits its own registry key and a resolved verbosity" {
            val i = input()
            builders.forEach { (key, build) ->
                withClue(key) {
                    val s = build(i)
                    s.key shouldBe key
                    s.appliedVerbosity shouldBe i.profile.verbosityFor(key)
                    key shouldContain "protocol.section."
                }
            }
        }

        "missing source input -> degraded or honestly-empty, never a thrown exception" {
            // Every federated source unavailable, and a record with no captures.
            val starved =
                input(sources = ProtocolSources()).copy(
                    record =
                        org.tatrman.kantheon.protocol.v1.ProtocolRecord
                            .getDefaultInstance(),
                )

            builders.forEach { (key, build) ->
                withClue(key) {
                    val s = build(starved)
                    s.key shouldBe key
                    // The status is a fact about the document, not an exception — which
                    // is the whole of P-4 in one assertion.
                    (
                        s.status in
                            setOf(SectionStatus.SECTION_OK, SectionStatus.SECTION_DEGRADED, SectionStatus.SECTION_OFF)
                    ) shouldBe
                        true
                }
            }
        }

        "applied_verbosity honors profile: off -> SECTION_OFF with no payload" {
            val allOff =
                ProtocolProfile(sections = ProtocolProfile.DEFAULT_SECTIONS.mapValues { Verbosity.VERBOSITY_OFF })
            val i = input(profile = allOff)

            builders.forEach { (key, build) ->
                withClue(key) {
                    val s = build(i)
                    s.status shouldBe SectionStatus.SECTION_OFF
                    s.appliedVerbosity shouldBe Verbosity.VERBOSITY_OFF
                    s.payloadCase shouldBe Section.PayloadCase.PAYLOAD_NOT_SET
                }
            }
        }

        "resolution: renders the F2 capture (function_id, args, bindings, confidence, layer_hit)" {
            val r = ResolutionSectionBuilder.build(input()).resolution

            r.functionId shouldBe "margin_by_period"
            r.argsJson shouldContain "2026-Q3"
            r.confidence shouldBe 0.94
            r.layerHit shouldBe 2
            r.bindingsList.map { it.mention } shouldBe listOf("Q3", "gross margin")
            // A domain binding reports its catalog id; a universal one its normalised value.
            r.bindingsList.map { it.boundRef } shouldBe listOf("2026-Q3", "metric.gross_margin")
        }

        "resolution at summary drops the prose but keeps the decision" {
            val summary =
                ProtocolProfile(sections = mapOf(ResolutionSectionBuilder.KEY to Verbosity.VERBOSITY_SUMMARY))
            val r = ResolutionSectionBuilder.build(input(profile = summary)).resolution

            r.functionId shouldBe "margin_by_period" // what it did survives
            r.rationale shouldBe "" // why, at length, does not
            r.argsJson shouldBe ""
            r.layerHit shouldBe 2
        }

        "plan: reconstructed flag is passed through, never inferred (S-1)" {
            PlanSectionBuilder.build(input()).plan.reconstructed shouldBe false

            val reconstructed = FixtureLoader.sources("reconstructed-plan")
            PlanSectionBuilder.build(input(sources = reconstructed)).plan.reconstructed shouldBe true
        }

        // Live finding (hartland): the explain client is unwired in that deployment, and
        // the section vanished from the document entirely — no heading, no explanation,
        // with the only trace a line in the receipts. The profile had asked for
        // `plan = full`.
        "plan: an UNWIRED source degrades in place; only the PROFILE may switch it off" {
            val unwired =
                FixtureLoader.sources(case).copy(
                    explain =
                        org.tatrman.kantheon.iris.protocol.sources.ExplainSource(
                            status = org.tatrman.kantheon.iris.protocol.sources.SourceStatus.SKIPPED_BY_CONFIG,
                        ),
                )

            // Degraded, NOT off: the heading survives and says it is unavailable (P-4).
            PlanSectionBuilder.build(input(sources = unwired)).status shouldBe SectionStatus.SECTION_DEGRADED

            // The operator's own `off` is still honoured, and still yields SECTION_OFF.
            val off = ProtocolProfile(sections = mapOf(PlanSectionBuilder.KEY to Verbosity.VERBOSITY_OFF))
            PlanSectionBuilder.build(input(sources = unwired, profile = off)).status shouldBe
                SectionStatus.SECTION_OFF
        }

        // Live finding (hartland): with the tatrman-server hops untraced, no span carried
        // `dispatch.target`, so the old "otherwise take the longest span" fallback picked
        // the BFF's own request and the document stated `Worker: iris-bff` with the whole
        // TURN's duration as the query's. Confidently wrong beats absent, and this
        // document cannot afford that.
        "execution: no dispatch span -> degraded, never the longest span as a guess" {
            val noDispatch =
                FixtureLoader.sources(case).copy(
                    tempo =
                        org.tatrman.kantheon.iris.protocol.sources.TempoSource(
                            status = org.tatrman.kantheon.iris.protocol.sources.SourceStatus.OK,
                            spans =
                                listOf(
                                    org.tatrman.kantheon.iris.protocol.sources.SpanData(
                                        spanId = "a",
                                        name = "POST /v1/chat/stream",
                                        serviceName = "iris-bff",
                                        durationMs = 17403,
                                    ),
                                ),
                        ),
                )

            val e = ExecutionSectionBuilder.build(input(sources = noDispatch))
            e.status shouldBe SectionStatus.SECTION_DEGRADED
            // Nothing invented from the span that happened to be longest.
            e.execution.worker shouldBe ""
            e.execution.durationMs shouldBe 0
        }

        "security: capture-absent marker -> degraded, and its reason is carried (Amendment A-1)" {
            val s = SecuritySectionBuilder.build(input())

            s.status shouldBe SectionStatus.SECTION_DEGRADED
            s.security.policySource shouldContain "does not propagate"
            s.security.rulesCount shouldBe 0
        }

        "security: NO marker and empty capture -> OK with zero rules ('no rules applied' is an answer)" {
            // The distinction Amendment A-1 exists to preserve: same empty capture,
            // opposite meanings, told apart only by the presence of the gap marker.
            val noMarker =
                FixtureLoader
                    .records("H1-full")
                    .first()
                    .toBuilder()
                    .apply { pointersBuilder.clearCaptureGaps() }
                    .build()
            val s = SecuritySectionBuilder.build(input().copy(record = noMarker))

            s.status shouldBe SectionStatus.SECTION_OK
            s.security.rulesCount shouldBe 0
        }

        "llm-calls: attribution is by turn ref or trace id; foreign rows raise unattributable_count" {
            val l = LlmCallsSectionBuilder.build(input()).llmCalls

            l.callsList.map { it.callRef } shouldBe listOf("gw-100")
            // The fixture's second row belongs to another turn entirely.
            l.unattributableCount shouldBe 1
            l.callsList.single().turnRef shouldBe FixtureLoader.turns(case).first().turnId
        }

        "service-logs: cap overflow is counted and flagged, not silently dropped" {
            val i =
                SectionInput(
                    record = FixtureLoader.records("truncation").first(),
                    sources = FixtureLoader.sources("truncation"),
                    turn = FixtureLoader.turns("truncation").first(),
                    profile = FixtureLoader.config("truncation").profile(),
                    caps = FixtureLoader.config("truncation").caps,
                )
            val s = ServiceLogsSectionBuilder.build(i)

            s.truncated shouldBe true
            // 40 already dropped upstream + 2 dropped by the cap of 1.
            s.serviceLogs.groupsList.sumOf { it.droppedByCap } shouldBe 42
            s.serviceLogs.groupsList
                .single()
                .linesCount shouldBe 1
        }

        "errors: does NOT degrade when Loki is missing — it reports what the turn itself said" {
            val failed =
                input(
                    sources = ProtocolSources(),
                ).copy(turn = FixtureLoader.turns(case).first().copy(status = "failed"))
            val s = ErrorsSectionBuilder.build(failed)

            // An errors section that said 'degraded' would read as 'there may have
            // been errors'; what it can always state truthfully is the turn's status.
            s.status shouldBe SectionStatus.SECTION_OK
            s.errors.itemsList.map { it.code } shouldBe listOf("TURN_FAILED")
        }

        "the A-1 gap the recorder writes is exactly the one the security builder reads" {
            // Guards the seam between S1.2 and S2.1: if either side renames the
            // capture, this fails rather than the section silently going OK.
            ProtocolRecorder.SECURITY_APPLIED_GAP.capture shouldBe SecuritySectionBuilder.CAPTURE
        }
    })
