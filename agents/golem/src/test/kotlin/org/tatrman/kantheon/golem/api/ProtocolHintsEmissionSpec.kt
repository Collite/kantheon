package org.tatrman.kantheon.golem.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.golem.execution.ExecutionResult
import org.tatrman.kantheon.golem.graph.GolemTurnState
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.QueryNode
import org.tatrman.kantheon.golem.v1.ResourceUsage
import org.tatrman.kantheon.golem.v1.Status
import org.tatrman.kantheon.golem.v1.StepRecord

/**
 * PT-25/S-5 (contracts §4): what golem claims about its own turn. The recurring
 * assertion is negative — a field golem cannot know first-hand must come back
 * EMPTY rather than guessed, because the BFF stores this block verbatim and the
 * assembler treats it as a lead to verify, not as truth.
 */
class ProtocolHintsEmissionSpec :
    StringSpec({

        fun state(
            plan: MiniPlan? = null,
            execution: ExecutionResult? = null,
        ): GolemTurnState =
            GolemTurnState(
                request =
                    GolemRequest
                        .newBuilder()
                        .setId("turn-1")
                        .setGolemId("golem-hartland")
                        .build(),
                plan = plan,
                execution = execution,
            )

        fun plan(vararg nodeIds: String): MiniPlan =
            MiniPlan
                .newBuilder()
                .apply {
                    nodeIds.forEach { id ->
                        addNodes(
                            MiniPlanNode
                                .newBuilder()
                                .setNodeId(id)
                                .setQuery(QueryNode.newBuilder().setSource("SELECT 1").setSourceLanguage("sql")),
                        )
                    }
                }.build()

        fun execution(
            sql: String = "SELECT margin FROM p_and_l",
            steps: List<Pair<String, Long>> = listOf("q1" to 180L),
        ): ExecutionResult =
            ExecutionResult(
                envelopes = emptyList(),
                stepRecords =
                    steps.map { (id, ms) ->
                        StepRecord
                            .newBuilder()
                            .setNodeId(
                                id,
                            ).setNodeKind("query")
                            .setStatus("COMPLETED")
                            .setLatencyMs(ms)
                            .build()
                    },
                resourceUsage = ResourceUsage.getDefaultInstance(),
                status = Status.STATUS_DONE,
                currentView = ViewProvenance.newBuilder().setSql(sql).build(),
            )

        "response carries protocol_hints with plan_ids from the executed plan" {
            val hints = ProtocolHintsBuilder.from(state(plan = plan("q1", "r1"), execution = execution()))

            hints.planIdsList shouldContainExactly listOf("q1", "r1")
        }

        "llm_call_refs include gateway row ids when the gateway returned them, else empty (never null)" {
            // ttr-llm-client's complete() returns no per-call metadata (PT-24, the
            // same gap as the X-Call-Purpose header), so golem has no gateway row id
            // to forward. The contract's "else empty" branch is the live one today.
            val hints = ProtocolHintsBuilder.from(state(plan = plan("q1"), execution = execution()))

            hints.llmCallRefsList.shouldBeEmpty()
            hints.llmCallRefsList shouldBe emptyList() // non-null by proto construction
        }

        "sql_inline set for small SQL, sql_ref for large — exactly one of the pair" {
            val small = ProtocolHintsBuilder.from(state(execution = execution(sql = "SELECT 1")))
            small.sqlInline shouldBe "SELECT 1"
            small.sqlRef shouldBe ""

            // Over the cap: NEITHER is set. golem has no SQL store to mint a ref
            // into, and a truncated inline would read as complete SQL — the one
            // outcome worse than absence.
            val huge = ProtocolHintsBuilder.from(state(execution = execution(sql = "x".repeat(20_001))))
            huge.sqlInline shouldBe ""
            huge.sqlRef shouldBe ""

            // Exactly at the cap still inlines.
            ProtocolHintsBuilder.from(state(execution = execution(sql = "y".repeat(20_000)))).sqlInline.length shouldBe
                20_000
        }

        "timings contain one HintTiming per pipeline step with duration_ms > 0" {
            val hints =
                ProtocolHintsBuilder.from(
                    state(execution = execution(steps = listOf("q1" to 180L, "r1" to 45L))),
                )

            hints.timingsList.map { it.step } shouldContainExactly listOf("q1", "r1")
            hints.timingsList.map { it.durationMs } shouldContainExactly listOf(180L, 45L)
            hints.timingsList.all { it.durationMs > 0 } shouldBe true
        }

        "a turn that never executed yields an empty-but-present block, not a fabricated one" {
            val hints = ProtocolHintsBuilder.from(state())

            hints.planIdsList.shouldBeEmpty()
            hints.timingsList.shouldBeEmpty()
            hints.sqlInline shouldBe ""
            hints shouldBe
                org.tatrman.kantheon.protocol.v1.ProtocolHints
                    .getDefaultInstance()
        }

        "a negative latency is clamped rather than emitted as a nonsense duration" {
            val hints = ProtocolHintsBuilder.from(state(execution = execution(steps = listOf("q1" to -5L))))

            hints.timingsList.single().durationMs shouldBe 0L
        }
    })
