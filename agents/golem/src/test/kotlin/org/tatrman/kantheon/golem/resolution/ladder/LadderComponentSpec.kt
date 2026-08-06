package org.tatrman.kantheon.golem.resolution.ladder

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.resolver.v1.GapKind

/**
 * RV-P5.2 T5 + T6 — the loop end to end on a **recorded core**, with the spans asserted in
 * the same run.
 *
 * H2's lattice is the fixture: *"Zobraz prvních 10 čerpacích stanic v Praze podle tržby za
 * 12 měsíců."* carries a `G1_UNBOUND` on the SUBJECT (*čerpacích stanic* — the vocabulary
 * genuinely does not have it pre-learning) and a `G3_UNATTRIBUTED` on *Praze*. That is
 * exactly the shape the task list asks for: a gap the broad pass cannot close.
 */
private fun testSdk(exporter: InMemorySpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()

private fun h2State(): LadderState {
    val lattice = RecordedResolutionCore.lattice("h2-cs")
    return LadderState(lattice = lattice, gaps = lattice.gapsList.toList())
}

private fun longAttr(n: String) = AttributeKey.longKey(n)

private fun stringAttr(n: String) = AttributeKey.stringKey(n)

class LadderComponentSpec :
    StringSpec({

        "CHAT_QUICK climbs, local proposes, the gate binds, the loop terminates covered at 1 LLM call" {
            runTest {
                val config = shippedLadder()
                val start = h2State()
                val g3 = start.gaps.single { it.kind == GapKind.GAP_KIND_G3_UNATTRIBUTED }
                val proposed = hypothesis("čerpacích stanic", "md.dimension.Station", start = 21)

                // `lookup` no-ops on the first pass (nothing learned yet — the T3 ruling);
                // `local` proposes; the gate binds and hands back only the carryable G3.
                val lookup = ScriptedRung()
                val local = ScriptedRung(RungProposal(listOf(proposed)))
                val gate =
                    ScriptedGate(
                        gateResult(
                            bindings = listOf(binding("md.dimension.Station")),
                            updatedGaps = listOf(g3),
                            outcomes = listOf(HypothesisVerdict(proposed, accepted = true, reason = "")),
                        ),
                    )

                val outcome =
                    runLadderLoop(
                        initial = start,
                        config = config,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(config, "CHAT_QUICK"),
                        rungs = mapOf("lookup" to lookup, "local" to local),
                        gate = gate,
                    )

                // Covered: the SUBJECT gap is gone, only the carryable G3 on `Praze` remains,
                // and a G3 with no roles is not load-bearing — so the turn EMITs rather than
                // asking. (Without the value/mention split H2 spends its question on `Praze`.)
                outcome.verdict shouldBe Verdict.EMIT
                outcome.state.gaps.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
                outcome.state.llmInvocations shouldBe 1
                outcome.state.gatedBindings.map { it.ref } shouldContainExactly listOf("md.dimension.Station")
                outcome.state.degraded.shouldBeNull()

                // The rung log tells the story in order: lookup tried and proposed nothing,
                // then local's escalate→gate pair.
                outcome.state.rungLog.map { it.rung } shouldContainExactly listOf("lookup", "local")
                outcome.state.rungLog.map { it.action } shouldContainExactly listOf("no-proposal", "regate")
            }
        }

        "the SAME lattice under the zero-rung config asks instead — the config is the difference" {
            runTest {
                val open = LadderConfig.parse(openLadderYaml())
                val start = h2State()

                val outcome =
                    runLadderLoop(
                        initial = start,
                        config = open,
                        profileName = "CHAT_QUICK",
                        budgets = Budgets(open.profile("CHAT_QUICK"), 0) { 0 },
                        rungs = emptyMap(),
                        gate = ScriptedGate(gateResult()),
                    )

                // No rung may run, so the loop never climbs; the load-bearing SUBJECT gap is
                // still open and the ask budget is unspent.
                outcome.verdict shouldBe Verdict.ASK
                outcome.rounds shouldBe 0
                outcome.state.llmInvocations shouldBe 0
                askableGaps(outcome.state.gaps, open).first().span.text shouldBe "čerpacích stanic"
            }
        }

        "the ask is spent once — a second pass with the budget gone refuses under `strict`" {
            runTest {
                val open = LadderConfig.parse(openLadderYaml())
                val spent = h2State().copy(hitlRounds = 1) // CHAT_QUICK allows 1

                val outcome =
                    runLadderLoop(
                        initial = spent,
                        config = open,
                        profileName = "CHAT_QUICK",
                        budgets = Budgets(open.profile("CHAT_QUICK"), 0) { 0 },
                        rungs = emptyMap(),
                        gate = ScriptedGate(gateResult()),
                    )

                outcome.verdict shouldBe Verdict.REFUSE
            }
        }

        // ------------------------------------------------------------------ T5, spans

        "every rung invocation produces a span, and every gate call carries the two health numbers" {
            runTest {
                val exporter = InMemorySpanExporter.create()
                val config = shippedLadder()
                val start = h2State()
                val g3 = start.gaps.single { it.kind == GapKind.GAP_KIND_G3_UNATTRIBUTED }
                val proposed = hypothesis("čerpacích stanic", "md.dimension.Station", start = 21)

                runLadderLoop(
                    initial = start,
                    config = config,
                    profileName = "CHAT_QUICK",
                    budgets = budgetsFor(config, "CHAT_QUICK"),
                    rungs =
                        mapOf(
                            "lookup" to ScriptedRung(),
                            "local" to ScriptedRung(RungProposal(listOf(proposed))),
                        ),
                    gate =
                        CoreGateCall(
                            RecordedResolutionCore.RecordingClient(
                                answer = { RecordedResolutionCore.response("h2-cs") },
                                gateAnswer = {
                                    org.tatrman.resolver.v1.GateResponse
                                        .newBuilder()
                                        .addGatedBindings(binding("md.dimension.Station"))
                                        .addUpdatedGaps(g3)
                                        .addOutcomes(
                                            org.tatrman.resolver.v1.HypothesisOutcome
                                                .newBuilder()
                                                .setHypothesis(proposed)
                                                .setAccepted(true),
                                        ).build()
                                },
                            ),
                            testSdk(exporter),
                        ),
                    otel = testSdk(exporter),
                )

                val rungSpans = exporter.finishedSpanItems.filter { it.name == RV_SPAN_RUNG }
                rungSpans shouldHaveSize 2
                rungSpans.map { it.attributes.get(stringAttr(RV_RUNG)) } shouldContainExactly listOf("lookup", "local")
                rungSpans[0].attributes.get(longAttr(RV_HYPOTHESES_OUT)) shouldBe 0L
                rungSpans[1].attributes.get(longAttr(RV_HYPOTHESES_OUT)) shouldBe 1L
                // The gap kinds the rung was handed — what a rising G1 count is queried on.
                rungSpans[0].attributes.get(stringAttr(RV_GAP_KINDS)) shouldBe "G1_UNBOUND,G3_UNATTRIBUTED"

                val gateSpans = exporter.finishedSpanItems.filter { it.name == RV_SPAN_GATE }
                gateSpans shouldHaveSize 1
                // P2.4 T5's two numbers, now LIVE because kantheon has an SDK.
                gateSpans.single().attributes.get(longAttr(RV_HYPOTHESES_IN)) shouldBe 1L
                gateSpans.single().attributes.get(longAttr(RV_HYPOTHESES_SURVIVED)) shouldBe 1L
                gateSpans.single().attributes.get(longAttr(RV_GAPS_OPEN_AFTER)) shouldBe 1L
            }
        }

        "a failed rung is an ERROR span carrying the reason — not a silent zero-proposal" {
            runTest {
                val exporter = InMemorySpanExporter.create()
                val config = shippedLadder()

                climbLadder(
                    state = h2State(),
                    config = config,
                    profileName = "CHAT_QUICK",
                    budgets = budgetsFor(config, "CHAT_QUICK"),
                    rungs =
                        mapOf(
                            "lookup" to ScriptedRung(),
                            "local" to ScriptedRung(RungProposal(emptyList(), failure = "timed out after 3000ms")),
                        ),
                    gate = ScriptedGate(gateResult()),
                    otel = testSdk(exporter),
                )

                val failed = exporter.finishedSpanItems.single { it.attributes.get(stringAttr(RV_RUNG)) == "local" }
                failed.attributes.get(stringAttr(RV_RUNG_FAILURE)) shouldBe "timed out after 3000ms"
                failed.status.statusCode shouldBe io.opentelemetry.api.trace.StatusCode.ERROR
            }
        }

        "the rung log and the spans tell the same story — that is what makes either trustworthy" {
            runTest {
                val exporter = InMemorySpanExporter.create()
                val config = shippedLadder()

                val after =
                    climbLadder(
                        state = h2State(),
                        config = config,
                        profileName = "CHAT_QUICK",
                        budgets = budgetsFor(config, "CHAT_QUICK"),
                        rungs = mapOf("lookup" to ScriptedRung(), "local" to ScriptedRung()),
                        gate = ScriptedGate(gateResult()),
                        otel = testSdk(exporter),
                    )

                val spanRungs =
                    exporter.finishedSpanItems
                        .filter { it.name == RV_SPAN_RUNG }
                        .map { it.attributes.get(stringAttr(RV_RUNG)) }
                after.rungLog.map { it.rung } shouldContainExactly spanRungs
            }
        }
    })
